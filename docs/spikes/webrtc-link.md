# WebRTC DataChannel 直推 · 技术验证（spike）

- 2026-11-07 · 桌面 JVM + Edge 156 headless · Worker `wss://huchuan-a9f.pages.dev/signal`
- 代码 `spikes/webrtc-link/`（gitignore）；证据 `artifacts/webrtc-link/`（gitignore）
- **结论：通。** JVM 起 DataChannel，浏览器收 8 MiB 真实文件并独立 SHA-256，3/3 通过；不是只握手。

## 1. 结论

- 桌面：`dev.onvoid.webrtc:webrtc-java:1.0.0` 可用；8 MiB / 512 块，浏览器回 `verify{ok:true}`，SHA 对得上。
- Android：`io.github.webrtc-sdk:android:150.7871.01` 在 Mi MIX 2S/API 33/arm64 真机加载原生库、建 DataChannel、生成 470 B offer；**未跑 Android 传输**。
- §4.2 的「选甲」成立：安卓原生库可用；乙（WebView）零 APK 体积，但要前台服务保活、拿不到 ICE 统计、WebView 版本不可控；本轮未实测。

## 2. 选型对比

| 候选 | 平台 | 能用 | 体积 | 许可 | 结论 |
|---|---|---|---|---|---|
| 甲 `io.github.webrtc-sdk:android:150.7871.01` | Android | 是（真机探针） | AAR 49.1 MB；arm64 `.so` 12.29 MB；arm64-only APK 13.40 MB | BSD-3-Clause（POM；AAR 未带 LICENSE） | 安卓选它；`abiFilters arm64-v8a`；16 KB 页对齐 |
| 乙 应用内 WebView 跑同一页 | Android | 未实测；机制可行 | 0 | 系统 WebView | 需前台服务；无 ICE 统计；版本不可控 |
| 桌面 `dev.onvoid.webrtc:webrtc-java:1.0.0` | Windows/Linux/macOS | 是（本轮 E2E） | 主 jar 0.5 MB + windows-x86_64 原生 jar 9.1 MB（DLL 22.8 MB 解包） | Apache-2.0 | 本轮唯一跑通的桌面路线；不是 Android AAR |
| 桌面 WebView2（乙的 Windows 对应） | Windows | 未实测；基线已有 WebView2 | 0 | 系统 | 零成本复用 UI 壳，但同样拿不到 ICE 统计 |

## 3. 端到端读数（3 次，8 MiB）

- 文件 SHA-256 `bf21501c895a586fdc60ae2b73b5e7ab4606a66d513e8201e1c4748c879e1d8e`；512 个 16 KiB 块；浏览器回 `verify`，本地 `/report` 也留证。

| 指标 | 读数（3 次 / 中位数） |
|---|---|
| JVM 发送耗时 | 353 / 360 / 359 ms → 中位 359 ms（22.28 MiB/s） |
| 浏览器首→末字节 | 638 / 648 / 656 ms → 中位 648 ms（12.35 MiB/s） |
| 候选对 | JVM host；浏览器 host/prflx；UDP；RTT 0.12–0.149 s |
| 建链（浏览器 boot→通道 open） | 8.5 / 10.2 / 11.8 s → 中位 10.2 s（含 3 s answer 聚合等待与 Edge 冷启动） |
| 峰值 bufferedAmount | 3.16 MB（发送窗口 128×16 KiB，上限 8 MiB） |
| JVM 堆峰值 / RSS | 25.5 MB / 53→144 MB（含 WebRTC 原生） |
| 浏览器 usedJSHeapSize | 17.8 MB（512 块 + 连续 8 MiB + digest） |

- **口径**：同机回环，host/prflx，不是 WiFi/蜂窝；STUN 未参与选路。设计里旧的 7–10 MB/s 是另一环境，未复测。

## 4. 信令客户端（可复用）

- 位置 `spikes/webrtc-link/signaling/`：`Crypto.kt`（Crockford B32、P-256、signBytes）、`ShareApi.kt`（创建/查询/撤销）、`SignalSession.kt`（房间、offer/answer/ice、断线、超时、bye）。
- 房间：`POST /api/share` 客户端生成 26 字符 token；分享者 WS 用 `signBytes("huchuan/ws1/v1",[deviceId,nonce,ts])`；访客匿名 `ep`。
- offer 签名：`signBytes("huchuan/share1/v1",[token,dtlsFp,expiresAt])`，`dtlsFp` 取 SDP `a=fingerprint:` 整段；浏览器用注册表 `pub` 验。

```kotlin
val share = ShareApi(keys = keys).createShare(files, ttlSec = 3600)
val s = SignalSession(role = SignalRole.SHARER, shareId = share.shareId, keys = keys); s.start()
s.await(15_000) { it is SignalEvent.Incoming }
s.sendOffer(sdp, keys.signHex("huchuan/share1/v1", share.token, dtlsFp, share.expiresAt))
s.sendAnswer(sdp); s.sendIce(candidateJson)
// Open / Reconnecting / Reconnected / Joined / Incoming / Offer / Answer / Ice / Bye / Err / Closed
```

- **关键坑**：webrtc-java 1.0.0 高层 API 是 trickle（offer 458 B 无候选）；必须实现 `Signaling.attach` 把 answer/ice 回灌，只有 `send` 会永不 open。
- **协议缺口**：Worker 房间不重放 `share-incoming`；分享者重连后访客须重新 join（无 resume 帧）。

## 5. 失败模式

- `--relay-only`（无 TURN）：应用层 channel-open 15 s 超时；浏览器 25.99 s 报「数据通道超时」；PeerState 只有 NEW→CLOSED，15 s 内没有 FAILED。
- 真实对称 NAT：**未测**。本机 STUN 4/4（Cloudflare + Google，197–210 ms），两个目标映射同一外部 IP:port → 锥形/端点无关；relay-only 复现的是「无可用候选对」这一类的失败，不是真对称 NAT 的时序。
- 对端离开：信令层实测 `bye{peer-left}` / `bye{gone}` 与 `Bye` 事件；传输中断开的 15 s/30 s 超时按客户端预算，未做真机断网测试。

## 6. Android 路径

- 真机：Mi MIX 2S，Android 13 / API 33，arm64-v8a；APK 13.40 MB（arm64-only）。
- AAR：49.1 MB，含 armeabi-v7a 6.81 / arm64 12.29 / x86 12.82 / x86_64 16.17 MB 四个 `.so`；`abiFilters arm64-v8a` 后 APK 只带 12.29 MB。
- AAR manifest minSdk 21/targetSdk 23；app minSdk 26/target 37，真机跑通；arm64 `.so` LOAD align=0x4000（16 KB 页）。
- 探针输出：`ok label=huchuan/1 state=CONNECTING id=-1`、`offer=470B app=true`；无 crash。
- **未做** Android 信令与 8 MiB 传输；`java.net.http` 在 Android 不可用，若要复用 `SignalSession` 需先把 WS/HTTP 换成 OkHttp 或抽 Transport。
- API 37 的 `ACCESS_LOCAL_NETWORK` 是运行时权限；生产在 `createPeerConnection` 前申请，否则本地候选/绑定会失败（本轮设备是 API 33，未验）。

## 7. 风险与许可

- 1 GiB 浏览器路径按本轮实现不可行：512 块 + 连续 buffer 的峰值已 17.8 MB，1 GiB 会到 GB 级；需流式落盘或分片接收。W1 的 1 GiB 上限必须配流式实现。
- 8 MiB 是单文件、单访客、同机回环；跨网受上行带宽、NAT、浏览器后台节流影响；设计里的 15 s 建链预算在本机中位 10.2 s，余量不大。
- 无 TURN：对称 NAT / CGNAT 如实失败（`--relay-only` 的 15 s/25 s 是这一类的上界）；M3 再议。
- 许可：webrtc-sdk BSD-3-Clause（AAR 未带 LICENSE，发布前补 WebRTC LICENSE/PATENTS 到 THIRD_PARTY_NOTICES）；webrtc-java Apache-2.0（原生 jar 自带 WebRTC LICENSE/PATENTS）；Gson Apache-2.0 仅 spike。
- 桌面原生体积：windows-x86_64 DLL 22.8 MB 解包（jar 9.1 MB），Windows 壳/daemon 需接受这个增量；Android 12.29 MB `.so`。

## 8. 复现

```bash
./gradlew -p spikes/webrtc-link :demo:installDist
java -cp "spikes/webrtc-link/demo/build/install/demo/lib/*" app.huchuan.link.demo.MainKt --tag r1
bash spikes/webrtc-link/run.sh   # 3 次 + STUN + 汇总
java -cp "spikes/webrtc-link/demo/build/install/demo/lib/*" app.huchuan.link.demo.MainKt --relay-only --connect-timeout 15000 --report-timeout 40000 --tag fail-relay
./gradlew -p spikes/webrtc-link/android-probe :app:assembleDebug
```

- 证据：`artifacts/webrtc-link/result-e2e-r{1,2,3}.json`、`result-fail-relay.json`、`summary.json`、`stun-nat-probe.json`、`android-probe.{log,png}`、`android-sizes.txt`。

## 9. 关键片段

- 浏览器验签：`signBytes` 字段是 `UTF8(domain)||0x00||u16be(len)||bytes`，数字用 8 字节大端；P-256 SPKI 前缀必须含末尾 `04`，否则 WebCrypto 报 `DataError`（本轮踩过）。
- 浏览器接收：每帧 `u32be(index)||16 KiB`；`meta` 里带 SHA-256；收完拼连续 buffer 做 `crypto.subtle.digest`，回 `verify`。
- 发送端背压：`bufferedAmount` 窗口 128 帧（约 2 MiB），`channelSendLimit` 8 MiB；实测峰值 3.16 MB，未触发 ChannelFull。
- 完整最小实现见 `spikes/webrtc-link/{signaling,demo,web}/`；本文只留 API 与踩坑，避免双份代码。

- 未改 `app/`、`daemon/`、`windows/`、`tools/`、`scripts/`、`docs/design/`、`AGENTS.md`、`docs/STATE.md`；未给 `core/` 加模块。理由：`SignalSession` 依赖 `java.net.http` + Gson，Android 用不了；先留在 spike，进 core 前需抽 Transport 或换 OkHttp。

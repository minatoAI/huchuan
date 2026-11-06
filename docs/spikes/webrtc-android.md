# WebRTC DataChannel 发送端 · Android 真机验证（spike）

- 2026-11-07 · Mi MIX 2S `5b94673a`（Android 13 / API 33 / arm64-v8a / WiFi 192.168.31.39）→ 本机 Edge 156 headless 收；信令 `wss://huchuan-a9f.pages.dev/signal`
- 代码 `spikes/webRTC-android/`、证据 `artifacts/webrtc-android/`（均 gitignore）；接收页复用 `spikes/webrtc-link/web`
- **结论：通。** OkHttp 连信令、`org.webrtc` 建 DataChannel、512×16 KiB 帧推给浏览器；浏览器独立 SHA-256 对上，4/4 通过；offer 的 P-256 签名由浏览器 `verifyOffer` 验过（不过会直接失败）。

## 1. 读数（8 MiB，r2 / r3 / r4）

| 指标 | 读数（3 次 → 中位） |
|---|---|
| 建链：浏览器入房 → 通道 open | 1806 / 1659 / 1014 ms → **1659 ms** |
| 建链：offer → open | 1745 / 1590 / 944 ms → 1590 ms |
| 手机发送 512 块 | 1346 / 1594 / 1357 ms → **1357 ms（5.90 MiB/s）** |
| 浏览器首→末字节 | 1750 / 1905 / 1651 ms → **1750 ms（4.57 MiB/s）** |
| 候选对 | host↔prflx，UDP，RTT 9–15 ms（手机 host 192.168.31.39 / srflx 222.208.209.39） |
| 峰值 bufferedAmount | 2.11 MB ×3 → 2.02 MiB（窗口 2 MiB，未触 ChannelFull） |
| 手机内存 | VmHWM 154.8 / 157.1 / 160.3 → **157.1 MiB**；Java 堆峰 21.6 MiB；native 14.2 MiB |
| 浏览器 usedJSHeapSize | 17.0 MiB |

- 口径：手机 WiFi ↔ PC 同网段，不是蜂窝也不是回环；STUN `stun:stun.cloudflare.com:3478` 只用来拿 srflx。
- 端到端另有一层抖动在信令 WS：浏览器 boot→WS open 2.2 / 2.4 / 2.7 s，第 4 次（r5）31.4 s；于是 boot→通道 open 4.2 s，r5 32.4 s。链路本身只占 0.9–1.8 s。

## 2. 与桌面的差异

| 项 | 桌面（已验） | Android（本轮） |
|---|---|---|
| HTTP/WS | `java.net.http` + Gson | **必须 OkHttp**（Android 无 `java.net.http`）+ `org.json` |
| WebRTC API | 高层 `Peer` + `Signaling.attach` | 低层 `org.webrtc`：trickle 回灌自己写（`answer→setRemoteDescription`、`candidate→addIceCandidate`） |
| 8 MiB 发送 | 353–360 ms（22.3 MiB/s，同机回环） | 1346–1594 ms（5.9 MiB/s，WiFi）——环境不同，只比数量级 |
| 签名公钥 | `ECPublicKey.getW()` 可用 | **`getW()` 返回 null**（logcat `KEY source=spki`），改从 X.509 SPKI 尾部取裸 64 B |
| 建链 | 浏览器 boot→open 8.5–11.8 s（含 Edge 冷启与 3 s answer 聚合） | 4.2 s（headless Edge；boot→WS 就占 2.2–2.7 s） |
| 内存 | JVM 堆峰 25.5 MB / RSS 144 MB | VmHWM 157 MiB（含 12.3 MB `.so` + native），Java 堆 21.6 MiB |

## 3. 失败模式（都实测过）

- **注册表离线**：浏览器比分享者 `/signal` 早上线 → `/api/share` 回 `status=offline`，页面立刻「对方暂时离线」且不重试，手机白等 120 s（`logcat-r1.txt`）。生产必须「先连 WS 再发链接」，页面要退避重试。
- **无可用候选对**：`iceTransportsType=RELAY` 且无 TURN → 本地候选 0 个、`iceConnectionState=CHECKING`；手机 30 s 应用层超时报 `data channel not open in 30 s`，浏览器 25 s 报「数据通道超时」，收尾时浏览器再收 `bye peer-left`/`gone`（`summary-fail-relay.json`）。与桌面 `--relay-only` 同类：两边都**不会自己进 FAILED**。
- 真对称 NAT / 蜂窝 CGNAT：**未测**（只有一条 WiFi）。

## 4. APK 体积

- debug arm64-only 13,986,632 B；同机同配置、无 OkHttp 的 webrtc 探针 13,403,952 B → **网络层 +582,680 B（569 KiB）**（OkHttp 4.12.0 + Okio + Kotlin stdlib，未跑 R8）；`libjingle_peerconnection_so.so` 12,287,312 B 是主体。

## 5. 许可（BSD-3-Clause；本轮不改 NOTICES）

- Android AAR 里**没有** LICENSE/PATENTS（只有 manifest、classes.jar、4 个 `.so`）；POM 只写 `The 3-Clause BSD License`。
- 文本位置：① 桌面 `dev.onvoid.webrtc:webrtc-java:1.0.0` 的 `*-windows-x86_64.jar` 内 `META-INF/licenses/webrtc/LICENSE.md`（132,641 B，BSD-3 正文 + 打包的第三方许可）与 `META-INF/licenses/webrtc/PATENTS`（1,412 B），已抽出到 `artifacts/webrtc-android/licenses/`；② 上游 `webrtc.googlesource.com/src/+/refs/heads/main/{LICENSE,PATENTS}`（本机 curl 返回 000，打不开）。

## 6. 进生产前必须做

1. **Transport 抽象**：`SignalSession`/`ShareApi` 现在有 `java.net.http` 与 OkHttp 两份实现，进 `core/` 前先抽接口、协议只留一份（帧格式已一致，浏览器验签成功即证）。
2. **API 37 `ACCESS_LOCAL_NETWORK` 未验**：设备只有 API 33。清单已声明、申请按 `SDK_INT>=37` 守卫（logcat `LOCAL_NETWORK perm skipped: sdk=33`）；生产必须在 `createPeerConnection` **之前**拿到，否则本地候选/绑定失败 —— 同 M1 的 mDNS 教训，带错状态活到进程结束不恢复。
3. **许可补登**：WebRTC `LICENSE` + `PATENTS` 进 `THIRD_PARTY_NOTICES.md`（AAR 不带）。
4. **后台保活**：本轮靠 Activity 前台 + `FLAG_KEEP_SCREEN_ON`；产品要前台服务，否则熄屏断链。
5. **流式收发**：8 MiB 是整块（浏览器 17 MB 堆）；1 GiB 上限必须分片落盘，手机侧也要流式读。
6. **失败话术**：无候选对要 30 s 才报，界面如实说「无法直连」并给重试，不许停在「正在连接」。

## 7. 复现

`./gradlew -p spikes/webRTC-android :app:assembleDebug` → `node spikes/webRTC-android/host/run.mjs --tag r6 --size 8` → 失败用例加 `--ice relay --expect-fail` → `node spikes/webRTC-android/host/summarize.mjs r2 r3 r4` 出中位。未改 `app/`、`core/`、`daemon/`、`windows/`、`pages/`、`worker/`、`tools/`、`scripts/`、`docs/design/`、`AGENTS.md`、`docs/STATE.md`。

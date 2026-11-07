# 链接分享改由 WebView 承载（删掉原生 WebRTC）

- 2026-11-07 · Mi MIX 2S `5b94673a`（Android 13 / API 33 / arm64-v8a / WiFi）· WebView `com.android.webview/117.0.5938.140` · 接收页 `https://huchuan-a9f.pages.dev`
- 代码：`app/src/main/kotlin/app/huchuan/link/`（`LinkWebSender` / `LinkWebHost` / `LinkHttpServer` / `LinkWebCapability`）+ `app/src/main/assets/link/`；证据：`artifacts/link-share-webview/`

## 1. 体积（同机、debug 未混淆、arm64-only）

| 项 | 原生 `io.github.webrtc-sdk` | 本分支（WebView） |
|---|---|---|
| `app-debug.apk` | 39,430,494 B（37.60 MiB） | **26,918,748 B（25.67 MiB）** |
| APK 未压缩总量 | 60,211,723 B | 47,433,425 B |
| `libjingle_peerconnection_so.so` | 12,287,312 B | 0 |
| dex 里 `org/webrtc` 符号 | 有 | 0 |
| 新增 | — | `sharer.js` 9,509 B ＋ `index.html` 525 B ＋ 4 个 Kotlin 文件 |

净省 **11.93 MiB**（两次都在删掉 APK 后重建再量：增量重建时 AGP 不截断旧文件，会把上一次的多余尾巴留成 100–280 KB 的空洞）。"省 24 MB" 是 spike 的估算口径（AAR 里 .so 12.29 ＋ classes9.dex 12.3）；实测这个包的 WebRTC Java 类只占约 0.5 MB，那 12.3 MB 的 dex 是 Compose，所以按字节算 12.5 MB。

## 2. 实测（8 MiB 单文件、线上接收页；浏览器 SHA `baaeea25…` 与源文件一致）

| 指标 | 本轮（10 次） | 原生 spike 同机 |
|---|---|---|
| 接收结果 | 10/10 成功、三方 SHA 一致（另 1 次撤销后 gated） | 4/4 |
| 浏览器首→末字节 | 中位 1654 ms（993–3262 ms ≈ 2.6–8.5 MiB/s；另 2 次 6.2 s / 52 s 属接收端抖动） | 1750 ms / 4.57 MiB/s |
| offer 发出 → 通道 open | 1102–1351 ms | 1590 ms |
| 手机侧推完 8 MiB | 379–2600 ms（3.1–21 MiB/s 入队） | 1357 ms |
| 页面 boot（loopback 出页面） | 196–227 ms | — |
| 内存 PSS | ~176 MB（app 进程） | 未测（VmHWM 157 MB） |

- 10 次 = wv-r1..r3、wv-r4-back（离开 P8 时照收）、wv-mem、wv-final、wv-script/script2（脚本从零建）、wv-ship（最终包）、wv-rotate（旋转后）。
- loopback 服务另验：Range 206 / 后缀 206 / 越界 416 / HEAD / 错 token 404 / 访客走完端口关闭（`range-*.json`）。

## 3. 与原生实现的差异（只换 RTC 那一层）

- **没动的**：设备身份、`ShareRegistry` 建/撤、core 的 `SignalClient` 与签名域、前台服务 / 通知 / P8 P9 / 离页提示 / 重连退避 / 等待预算。保活三条不变式复验通过：通知在、离开 P8 后仍 available（期间照收 8 MiB）、通知栏"结束分享"后链接立刻失效且服务停；旋转致 Activity 重建后分享照常收（页面被摘下再挂回）。
- **换掉的**：`PeerConnection` ＋ `DataChannel` ＋ 分帧循环（529 行）→ 页面 JS；原生只剩 WebView 宿主、loopback HTTP 服务、文件读取。
- **仍由原生签名**：页面把 offer SDP 交回，`SignalClient.sendOffer` 算 DTLS 指纹并用设备私钥签 `[token, dtlsFp, expiresAt]`。spike 让页面自建临时钥匙并直连信令，那样 offer 签名会与 `/api/share` 登记的 pub 脱钩（访客验签失败、撤销也签不了），所以没有采用。
- 三个坑的落地：`networkSecurityConfig` 只给 127.0.0.1/localhost 开明文；页面与文件同源出自 loopback；帧固定 16 KiB。

## 4. 已知限制

1. **WebView 版本跟随系统**（本机 AOSP 117，无更新通道）。启动时探 `RTCPeerConnection`/`createDataChannel`，不支持则 P8 只写一句「这台设备的浏览器内核不支持分享链接」；分支靠日志断言 `event=link_capability_selftest ok=true`（真机造不出坏内核）。
2. **内存更高**：WebView 是 app ＋ 渲染两个进程，PSS ~176 MB 起。
3. **渲染进程可能被系统回收**：`onRenderProcessGone` 直接判本次失败（P8 一句话、按退避重开），不像原生只在进程内失败。
4. 每个访客一个隐藏 WebView ＋ 一个一次性端口；多条分享并存 = 多个 WebView 与端口。
5. SDP/ICE 经 JavaScript 桥过一遍；远程调试只在 debug 构建开（`chrome://inspect`）。
6. `ACCESS_LOCAL_NETWORK`（API 37）本机走不到，与原生实现同样未验。

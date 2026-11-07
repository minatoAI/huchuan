# WebView 承载 WebRTC · 技术验证（spike）

- 2026-11-07 · Mi MIX 2S `5b94673a`（Android 13 / API 33 / arm64-v8a，WiFi）· 信令 `wss://huchuan-a9f.pages.dev/signal` · 接收页 `https://huchuan-a9f.pages.dev/#s=<token>`
- 代码 `spikes/webview-link/`，证据 `artifacts/webview-link/`（均 gitignore）
- **结论：可行。** 系统 WebView 里的 JS 起 DataChannel，8 MiB 真文件推给线上接收页，浏览器 SHA-256 / 手机 result / 宿主重算三方一致；APK 从 13.37 MiB 降到 880 KiB。

## 1. 结论

- 分享端整条链路（P-256 设备身份、`signBytes` 签名、`POST /api/share`、信令 WS、PeerConnection、DataChannel、16 KiB 分帧）全在页面 JS 里；原生只剩 WebView 宿主、一个 loopback HTTP 服务和文件读取。
- WebRTC 依赖可以整块删：本轮 A/B 省 13.1 MB；按正式包口径（AAR 49.1 MB，.so 12.29 + classes9.dex 12.3）可省 ~24 MB。
- 代价是内存：WebView 是「App 进程 + `com.android.webview:sandboxed_process0`」两个进程，PSS 合计 ~190 MB（原生没测 PSS，其 VmHWM 是 157 MB）。

## 2. 体积对比（同一台机器、AGP 9.5.1 / compileSdk 37 / minSdk 26 / target 37 / arm64-only / debug 未混淆）

| 项 | 原生 WebRTC spike | WebView spike |
|---|---|---|
| APK | 14,015,355 B（13.37 MiB） | **900,887 B（880 KiB）** |
| WebRTC 原生库 | `libjingle_peerconnection_so.so` 12,287,312 B | 0（用系统 WebView） |
| Java 类增量 | classes.dex 4.1 MB（比基线多 ~1.7 MB：AAR + OkHttp + Kotlin） | classes.dex 2.4 MB（框架基线） |
| 我们自己新增 | — | `sharer.js` 18,555 B + `index.html` 724 B + 4 个 Java 类（~40 KB dex） |

## 3. 实测读数（8 MiB 单文件，r4–r6）

- 文件 SHA-256 `bf21501c895a586fdc60ae2b73b5e7ab4606a66d513e8201e1c4748c879e1d8e`，512×16 KiB，与 `spikes/webRTC-android` 同种子同字节。
- 三方验：浏览器 `verify` 帧的 sha256 = 手机 result 的 sha256 = `adb pull` 回来在宿主重算的 sha256。

| 指标 | WebView（3 次 → 中位） | 原生 webrtc-android（3 次 → 中位） |
|---|---|---|
| 成功率 | 3/3（调试期共 10 次传输、9 次成功；唯一失败是发送端分片 bug，见坑 6） | 4/4 |
| offer 发出 → 通道 open | **743 ms** | 1590 ms |
| 浏览器入房 → 通道 open | 795 ms | 未单列 |
| 页面 boot → 通道 open | 9251 ms（含无头 Edge 冷启 8.4 s） | 1659 ms（接收页不同，仅数量级） |
| 浏览器首 → 末字节 | 1443 ms（5.54 MiB/s） | 1750 ms（4.57 MiB/s） |
| 页面侧发 512 帧 | 373 ms（21.5 MiB/s） | 1357 ms（发送循环含读盘） |
| 峰值 bufferedAmount | 2.09 MB（窗口 2 MiB） | 2.02 MiB |
| 内存 VmHWM | App 225,696 KB ＋ 渲染 151,384 KB ＝ 375,080 KB（两进程相加，共享页重复计，偏高） | 157.1 MiB（单进程） |
| 内存 PSS | 118,726 ＋ 75,333 ＝ 194,059 KB（原生未测 PSS） | — |
| 浏览器 JS 堆 | 18.5 MB | 17.0 MiB |

## 4. WebView 里 WebRTC 确实可用（不是只支持 getUserMedia）

- `com.android.webview` **117.0.5938.140**（AOSP 构建，无 Play 商店更新通道），UA 里是 `Chrome/117.0.0.0`。
- 页面实测：`isSecureContext=true`、`RTCPeerConnection`/`RTCDataChannel` 均为 function、`createDataChannel`/`createOffer`/`addIceCandidate`/`getStats` 都在；`createDataChannel` 真开出了通道（`CHANNEL_OPEN`，之后 512 帧走完）。
- 没用 `getUserMedia`、没有摄像头/麦克风权限，`INTERNET` 一条权限足够。

## 5. 坑（都是本机实测，不是推测）

1. **明文 HTTP 默认被禁**：targetSdk≥28 时 `loadUrl("http://127.0.0.1:…")` 直接 `net::ERR_CLEARTEXT_NOT_PERMITTED`；要写 `networkSecurityConfig` 只给 127.0.0.1/localhost 开例外（已验：去掉就白屏，加上就好）。
2. **但 127.0.0.1 仍是可信来源**：页面 `isSecureContext=true`，`crypto.subtle` 与 `RTCPeerConnection` 可用。用 `appassets.androidplatform.net`（AssetLoader）反而要处理到 127.0.0.1 的跨源。
3. **跨源只影响 HTTP 面**：WS 不走 CORS（页面 `http://127.0.0.1` 连 `wss://` 实测通）；但页面里 `POST /api/share` 是跨源，预检 `OPTIONS /api/*` 由 Worker 回 CORS，实测可用。页面若加 CSP，必须放行 `connect-src https://huchuan-a9f.pages.dev wss://huchuan-a9f.pages.dev`。
4. **文件怎么给 JS**：选 loopback HTTP（同源给页面和 `/file`），页面 `fetch().body.getReader()` 流式读，无 base64 膨胀；AssetLoader 只能给内置 assets、给不了用户文件，`@JavascriptInterface` 只能回 String。
5. **远程调试**：`WebView.setWebContentsDebuggingEnabled(true)` 才有 `@webview_devtools_remote_<pid>` socket（实测存在），chrome://inspect 能进；release 必须关。
6. **帧必须整 16 KiB**：线上接收页把块直接 `set` 进 256 KiB 的 coalesce 缓冲，一条非 16 KiB 的碎帧会让后续帧越界，页面报 `offset is out of bounds` 并断链（r4 就是这样挂的，改成固定分帧后 3/3 稳过）。
7. **后台/熄屏**：熄屏 40 s、HOME 后台 30 s 都没掉线；但没进 Doze、没被回收，长时（分钟级、深度休眠）未测。
8. **权限**：要 `INTERNET` 即可；API 37 的 `ACCESS_LOCAL_NETWORK` 在本机（API 33）走不到，仍未验。
9. **版本不可控**：WebView 是系统组件，本机是 AOSP 117 且没有更新通道；设备上 WebView 过旧/被禁用时 `RTCPeerConnection` 直接没有，能力探测要在启动时做并给出可读的失败原因。

## 6. 与原生方案的取舍

| 维度 | 原生 `io.github.webrtc-sdk` | WebView |
|---|---|---|
| APK | +12.3 MB `.so` + Java 类 | 0（页面 19 KB） |
| 建链（offer→open） | 1590 ms | 743 ms |
| 8 MiB 浏览器收 | 1750 ms / 4.57 MiB/s | 1443 ms / 5.54 MiB/s |
| 内存（PSS） | 未测（VmHWM 157 MB） | ~190 MB，两进程 |
| WebRTC 版本 | 钉死 150.7871.01 | 跟随系统，可旧可新 |
| ICE 统计 | 进程内 `getStats` 可取 | 页面里有，App 拿不到，要经桥回传 |
| 后台保活 | 前台服务即够 | 前台服务 + 渲染进程可能被冻结/回收 |
| 调试 | logcat + JNI | chrome://inspect |
| 许可 | BSD-3，AAR 不带 LICENSE，要自己补 NOTICES | 无新增许可 |

## 7. 复现

```bash
./gradlew -p spikes/webview-link :app:assembleDebug
node spikes/webview-link/host/run.mjs --tag r1      # 起 App → 线上接收页 → CDP 读 verify → 三方对 SHA
node spikes/webview-link/host/summarize.mjs r4 r5 r6  # 出中位数
```

- 证据：`artifacts/webview-link/` 下 `summary-r*.json`（含浏览器 verify、内存）、`logcat-r*.txt`、`medians-r4-r5-r6.json`、`sizes.txt`、`screenshot-waiting.png`。

# 链接分享 · Android 接线（P8/P9）

- 2026-11-07 · Mi MIX 2S `5b94673a`（Android 13 / API 33）· `./gradlew :app:assembleDebug` 通过
- 范围：只改 `app/`；证据 `artifacts/link-share-app/`（截图、logcat、接收页结果、soak-10min/、live-r1/）

## 1. 落点

| 件 | 文件 | 要点 |
|---|---|---|
| Transport | `link/OkHttpTransport.kt` | OkHttp WebSocket 实现 core 的 `Transport`/`TransportFactory`；握手 410/403 原样交给 core |
| 发送端 | `link/LinkSender.kt` | `org.webrtc`；answer 与远端 ICE 回灌 PeerConnection、本地候选 trickle；16 KiB 帧、2 MiB 背压、30 s 通道 watchdog |
| 会话/记录 | `link/LinkSession.kt`、`LinkStore.kt`、`LinkShareController.kt` | SignalClient 分享者 + 每访客一个 PC；verify 后 bye{done}，800 ms 重开信令；本机 `shares.json` 是 P9 列表源 |
| P8 | `ui/ShareLinkScreen.kt` | 本地 zxing QR + 链接文本 + 复制 + 有效期 + 状态行（等待访客/正在发送 x%/已发送/失效） |
| P9 | `ui/ShareManageScreen.kt` | 文件名 / 剩余有效期 / 被访问 n 次 / 撤销（一次确认） |
| 入口 | `ui/FileScreen.kt`、`ui/SettingsDrawer.kt`、`HuchuanApp.kt` | P4「…」→「分享链接」；P7「分享管理」 |

- 分享者等待预算由内核按分享有效期管理（core fbd24a2：等待期不判 `connect-failed`，访客在场时按 15 s 握手预算）；app 侧保留发送端 30 s 通道 watchdog 与接收页 25 s 兜底。
- 二维码只由 zxing 出 BitMatrix、Compose Canvas 绘制，不调用在线二维码服务。
- 失败只显示一句话：创建失败在 P8 状态位 +「重试」；信令/传输失败映射成「无法直连 / 传输超时 / 校验不一致 / 访客已离开」，不弹窗。

## 2. 真机验收

| 项 | 结果 | 证据 |
|---|---|---|
| a 创建分享，二维码与链接出现 | PASS | 最终构建 `p8-core2-waiting.png`（QR + `#s=3Y3A…` + 7 天后失效 + 等待访客）；QR 解码回同一链接：`qr-decoded.txt` |
| b 本地 pages 接收 8 MiB | PASS | `python -m http.server 8765 --directory pages/public` + Edge headless：`result-r3.json`；bytes=8388608，sha256 与源一致（`baaeea25…`），`sha256Match=true`；复跑 `result-r1/r2/r4.json` + `result-final.json` + `result-core2.json` 全过 |
| c 撤销后新访客 | PASS | P9 撤销 `d1ec0bb8`；同一链接再开 → `phase=gated`、`status=链接已失效`：`result-revoke.json`、`browser-revoke.png` |
| 状态变化 + bye{done} | PASS | `p8-core2-sending`（25%）→ `p8-core2-sent`（已发送）→ `p8-core2-waiting`（等待访客）；`guest-bye-ui-during/after.txt`（访客已加入→等待访客），logcat `link_visit_end reason=bye-done state=signaling` |
| P9/P4 入口 + 失败一句话 | PASS | `p9-share-list-final.png`（3 条，被访问 0/1/2 次）；`p4-menu-share-link.png`；断网创建 → P8「连不上分享服务」+「重试」，无弹窗（`p8-create-failed.png`） |

- 复跑：`node artifacts/link-share-app/accept.mjs --token <26> --sha256 <hex> --size 8388608 --tag rN`（内置静态服务）；或加 `--page 'http://127.0.0.1:8765/recv/?api=…#s=<token>'` 配合 `python -m http.server`；撤销态加 `--mode revoked`。

## 3. 构建与体积

- `./gradlew :app:assembleDebug` 通过；debug arm64-only：**26,198,104 B → 51,333,423 B（+25,135,319 B，+23.97 MiB）**。增量主体：WebRTC AAR 的 `classes9.dex` 12,335,700 B + `libjingle_peerconnection_so.so` 12,287,312 B；OkHttp 4.12.0 与 zxing core 3.5.3 合计约 0.5 MiB。

## 4. 已知限制

- P9 行未接「点行 → P8」；会话在 P9 打开时启动，App 重启后要先点一次 P9 才恢复在线。
- 无前台服务：熄屏/进程被杀 → 链接可能 offline（见 §5）；线上 Worker 仍是旧房间版本（`docs/link-share-web.md` §7）；单条分享只带一个文件；未改 core/daemon/pages/worker/tools/scripts/docs/design/STATE。

## 5. 挂机与锁屏

- 10 分钟挂机：11 次采样 API 全 `available`、P8 全「等待访客」、心跳 29–31 条/分、0 次重连；第 10 分钟浏览器接收 PASS（8 MiB，sha256 一致）。
- 第 3 分钟锁屏 2 分钟（USB 供电）：API 仍 available、心跳未断；点亮解锁后 297 ms 恢复 available，P8 回「等待访客」。无前台服务在电池供电/长时 Doze 下仍可能 offline，列为已知限制。
- 证据：`soak-10min/`（`samples/min-XX.json`、`summary.json`、`result-soak.json`、`browser-soak.png`、`p8-after-soak.png`、`logcat-soak.txt`）。

## 6. 线上验收
- 线上 `https://huchuan-a9f.pages.dev/#s=YKH…` 直接打开（无 api 参数）：8,388,608 B、sha256 一致、`saveMode=opfs`；P8 等待访客→访客已加入→正在发送 0/62%→已发送→等待访客（`live-r1/`）。
- 撤销后同一线上链接：`phase=gated`、`status=链接已失效`（`live-r1/result-live-revoke.json`、`browser-live-revoke.png`）。

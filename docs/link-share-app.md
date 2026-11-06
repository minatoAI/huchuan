# 链接分享 · Android 接线（P8/P9）

- 2026-11-07 · Mi MIX 2S `5b94673a`（Android 13 / API 33）· `./gradlew :app:assembleDebug` 通过
- 范围：只改 `app/`；证据 `artifacts/link-share-app/`（截图、logcat、接收页结果）

## 1. 落点

| 件 | 文件 | 要点 |
|---|---|---|
| Transport | `link/OkHttpTransport.kt` | OkHttp WebSocket 实现 core 的 `Transport`/`TransportFactory`；握手 410/403 原样交给 core |
| 发送端 | `link/LinkSender.kt` | `org.webrtc`；answer 与远端 ICE 回灌 PeerConnection、本地候选 trickle；16 KiB 帧、2 MiB 背压、30 s 通道 watchdog |
| 会话 | `link/LinkSession.kt` | SignalClient 分享者 + 每访客一个 PC；verify 后 bye{done}，800 ms 重开信令让链接可再次取件 |
| 记录 | `link/LinkStore.kt` + `link/LinkShareController.kt` | `filesDir/m1/link/shares.json`（P9 列表源）、创建/撤销/状态 StateFlow |
| P8 | `ui/ShareLinkScreen.kt` | 本地 zxing QR + 链接文本 + 复制 + 有效期 + 状态行（等待访客/正在发送 x%/已发送/失效） |
| P9 | `ui/ShareManageScreen.kt` | 文件名 / 剩余有效期 / 被访问 n 次 / 撤销（一次确认） |
| 入口 | `ui/FileScreen.kt`、`ui/SettingsDrawer.kt`、`HuchuanApp.kt` | P4「…」→「分享链接」；P7「分享管理」 |

- 分享者的信令预算改成「剩余有效期」（core 默认 15 s 是访客口径），否则挂着的分享每 15 s 被判 `connect-failed`；建链兜底交给发送端 30 s watchdog 与接收页 25 s。
- 二维码只由 zxing 出 BitMatrix、Compose Canvas 绘制，不调用在线二维码服务。
- 失败只显示一句话：创建失败在 P8 状态位 +「重试」；信令/传输失败映射成「无法直连 / 传输超时 / 校验不一致 / 访客已离开」，不弹窗。

## 2. 真机验收

| 项 | 结果 | 证据 |
|---|---|---|
| a 创建分享，二维码与链接出现 | PASS | `p8-share-created.png`（QR + `#s=32F0…` + 7 天后失效 + 等待访客）；QR 解码回同一链接：`qr-decoded.txt` |
| b 本地 pages 接收 8 MiB | PASS | `python -m http.server 8765 --directory pages/public` + Edge headless：`result-r3.json`、`browser-r3.png`；bytes=8388608，sha256 与源一致（`baaeea25…`），`sha256Match=true`，落盘档 opfs |
| b 复跑 | PASS | `result-r1/r2/r4.json` + 最终 APK `result-final.json` 五次全过（含 P9 启动会话后复用同一链接） |
| c 撤销后新访客 | PASS | P9 撤销 `d1ec0bb8`（`share_revoke` / `link_revoked`）；同一链接再开 → `phase=gated`、`status=链接已失效`：`result-revoke.json`、`browser-revoke.png` |
| 状态变化可见 | PASS | `p8-sending-37.png` / `p8-sending-62.png` / `p8-sent.png`；`logcat-evidence.txt`：`link_incoming → link_transferring → verify ok=true match=true → link_delivered` |
| P9 列表 | PASS | `p9-share-list.png` / `p9-share-list-final.png`（3 条，被访问 0/1/2 次，均带「7 天后失效」与「撤销」）；P7 入口计数 3 |
| P4 入口 | PASS | `p4-menu-share-link.png`（「…」→「分享链接」） |
| 失败一句话 | PASS | 断网创建 → P8 只有「连不上分享服务」+「重试」，无弹窗；恢复网络点重试出新链接：`p8-create-failed.png` |

- 复跑：`node artifacts/link-share-app/accept.mjs --token <26> --sha256 <hex> --size 8388608 --tag rN`（内置静态服务）；或先 `python -m http.server 8765 --directory pages/public`，再加 `--page 'http://127.0.0.1:8765/recv/?api=…#s=<token>'`；撤销态加 `--mode revoked`。
- 本地页面用 `?api=https://huchuan-a9f.pages.dev#s=<token>`：页面在本地，注册表/信令仍走已部署 Worker。

## 3. 构建与体积

- `./gradlew :app:assembleDebug` 通过；debug arm64-only：**26,198,104 B → 51,333,423 B（+25,135,319 B，+23.97 MiB）**。
- 增量主体：WebRTC AAR 的 `classes9.dex` 12,335,700 B + `libjingle_peerconnection_so.so` 12,287,312 B；OkHttp 4.12.0 与 zxing core 3.5.3 合计约 0.5 MiB。

## 4. 已知限制

- P9 行未接「点行 → P8」（设计稿有；本轮验收只要求列表 + 撤销）；会话在 P9 打开时启动，App 重启后要先点一次 P9 才恢复在线。
- 无前台服务：熄屏/进程被杀 → 链接 offline（link-share.md §11-4）。
- 线上 Worker 仍是旧房间版本（`docs/link-share-web.md` §7），本轮只验证现有 `/api/share*` 与 `/signal`。
- 单条分享只带一个文件（P4 单文件入口），多文件 UI 未做。
- 未改 core/daemon/pages/worker/tools/scripts/docs/design/STATE。

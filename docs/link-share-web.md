# 链接分享 · 浏览器接收页（W1）

- 2026-11-07 · 页面 `pages/public/recv/{index.html,app.js}`、路由 `pages/public/_redirects`、信令房间 `worker/src/room.js`
- 依据 `docs/design/link-share.md` §3 W1 / §6 / §7 / §10，协议用法同 `docs/spikes/webrtc-link.md`
- **未上线**：本机 wrangler 的 OAuth refresh token 已失效（`invalid_grant`），也没有 `CLOUDFLARE_API_TOKEN`，`wrangler deploy` 与 `wrangler pages deploy` 都被拒；重新 `wrangler login` 后再发。下面的验收用 `pages/public` 本地复刻同一套路由（`wrangler pages dev` 已验证 `_redirects` 生效）+ 已部署的 Worker 跑通。

## 1. 路由

| 链接形状 | 落点 |
|---|---|
| `https://<域名>/#s=<26 字符 token>` | `/` → `/recv/` |
| `https://<域名>/r/<32 hex shareId>#s=<token>` | `/r/*` → `/recv/` |

- token 只从 fragment 读，不进路径与查询串：不进服务器日志、不跟 Referer 外发。
- `_redirects` 的目标必须写目录 `/recv/`：写 `/recv/index.html` 会被 Pages 判成「归一化后自指」整条丢弃（`wrangler pages dev` 实测）。shareId 只做一致性校验（与 `GET /api/share/{token}` 的不同即判失效）；无 token 的裸域名访问显示「没有分享链接」。

## 2. 页面

文件行（`名字 · 大小`，多文件带 `1/3`）／状态行（`正在连接`·`{p}% {速度}`·`对方暂时离线`·`已保存`）／失败时的「重试」。打开即连，不用点。

## 3. 落盘：三档自动降级

| 档 | 触发 | 上限 | 行为 |
|---|---|---|---|
| FSA | `showSaveFilePicker` 可用且有用户手势 | 1 GiB | 流式写进用户选的文件 |
| OPFS | 无手势时的默认档（Chromium / Firefox 111+ / Safari 15.2+） | 1 GiB，另受配额 80% 约束 | 边收边写 OPFS，收完交给浏览器下载，暂存 5 分钟后删 |
| 内存 | 没有 OPFS | **128 MiB** | 分块攒 Blob；超限当场一句话拒绝 |

- FSA 要用户手势，而 W1 是「连上就自动开始」，所以实际默认走 OPFS；这条路上峰值堆不随文件变大（见 §4）。
- 单文件 1 GiB 是设计上限（§12-4）；超过与内存档共用一句「这个文件超过 1 GiB，浏览器接不了，请用 App 接收」。
- 校验：接收时用自带增量 SHA-256 边收边算（不拼整块内存），收完与清单比对；不符或字节数不对就不落盘。调试开关 `?save=opfs|memory` 可强制某档（§4 两次 128 MiB 就是用它分开跑的）。

## 4. 实测（Edge 156 headless + CDP；发送方 `spikes/webrtc-link` 桌面 demo；同机回环）

| 用例 | 档 | 建链 | 传输 | 速率 | 峰值堆 |
|---|---|---|---|---|---|
| 8 MiB ×3 | OPFS | 3.5 / 2.4 / 3.4 s | 0.64 / 0.72 / 0.71 s | 12.4 / 11.2 / 11.2 MiB/s | 16–17 MiB |
| 128 MiB | OPFS | 4.2 s | 10.3 s | 12.6 MiB/s | 40 MiB |
| 128 MiB | 内存 | 3.3 s | 10.3 s | 12.7 MiB/s | 137 MiB（≈1.07×） |

- 每例 4 处 SHA-256 全等：页面增量实现、OPFS 落盘文件用 WebCrypto 复算、下载落地文件（Node 算）、发送端收到的 `verify`。
- 负向：撤销后的链接 0.8 s 显示「链接已失效」且 **0 条 WebSocket**；150 MB 走内存档 → 一句话拒绝 + 重试。证据在 `artifacts/link-share/`（`view-w1*.png`、`view-gated.png`、`run-*/result.json`、`run-*/e2e.log`）。

## 5. 信令重连（两边都补）

- 分享者重连：回来时房间把在场访客重放成 `share-incoming{resumed:true}`，visitorId 不变，访客不重新 join 就能收到新 offer；旧 socket 迟到的关闭不再误发 peer-left。
- 访客重连：同 `ep` 重连（退避 2/4/8/16/30 s），房间按 `ep` 接管旧 socket（关 4006），不占「最多 1 个访客」的名额。
- 分享者离线：显示「对方暂时离线」，每 10–60 s 重试 join；被 `err{rate}` 挡下等 65 s（§7 的 3 次/分钟）。重连一律从头重传，不续传。

## 6. 安全

- revoked / expired / not-found 在页面上合成一句「链接已失效」，WS 与 RTCPeerConnection 都不创建。
- offer 验签：pub 推出的 deviceId 必须等于记录里的（`huchuan/dev1/v1`），再验 `share1` 域的 [token, dtlsFp, expiresAt]；不过就不 `setRemoteDescription`。信令仍能拒绝服务、仍看得到谁在何时取件，但不能冒充分享者。

## 7. 已知限制

- 1 GiB 只在桌面 Chromium 的 OPFS 档按设计成立，未真机验证；移动 Safari 配额与单页堆都更紧，内存档 128 MiB 是桌面实测工作点。OPFS 档收完还要落一份到下载目录，期间磁盘占 2×；断线不续传；多文件逐条收，第二个文件起每条目 index 从 0 重计。
- 首次请求偶发 10–20 s 长尾（共享机器 + pages.dev 冷启动），页面重试三次，失败才报「连不上服务器」。
- 真 workerd 已验证房间重放（`wrangler dev --local`，`artifacts/link-share/room-resume.log` 9/9）；线上仍是旧房间，部署后重跑 `node artifacts/link-share/e2e.mjs --tag w1` 复验。

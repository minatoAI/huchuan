# worker/ · 互传的 Cloudflare Worker

一台 Worker 干三件事（`docs/design/link-share.md` §4.3）：BEP 3 tracker、WebRTC 信令、分享注册表。
零第三方运行时依赖：只用 Workers 自带的 Web Crypto 与 Durable Objects。
部署步骤见 `docs/deploy-cloudflare.md`，本文件只讲实现口径。

## 端点

| 方法 | 路径 | 作用 | 关键入参 |
|---|---|---|---|
| GET | `/health` | 健康检查，回三个 DO 绑定是否就位 | — |
| GET | `/announce`、`/announce/<passkey>` | BEP 3 tracker（`protocol.md` §3.2） | info_hash=groupHash20、peer_id、port、passkey、event、compact、numwant |
| POST | `/api/tracker/register` | 群在 tracker 上登记 passkey 哈希 | infoHash、gid、passkey、prevPasskey? |
| POST | `/api/share` | 创建分享 | token?、deviceId、pub、files[]、ttlSec? |
| GET | `/api/share/<token>` | 解析分享，合成四态 | — |
| DELETE | `/api/share/<shareId 或 token>` | 撤销分享（签名） | deviceId、token、ts、nonce、sig |
| GET | `/api/shares?deviceId=` | 列该设备仍然有效的分享 | — |
| GET | `/signal`（别名 `/ws`） | WebRTC 信令（WebSocket 升级） | 分享者：share、dev、ts、nonce、sig；访客：share、ep |

## 本实现定死的口径（客户端要对齐）

| 项 | 口径 |
|---|---|
| token | 26 字符 Crockford B32 = 128 bit；客户端可自带，不带就服务端生成；`POST /api/share` 原样回传 |
| token 哈希 | `SHA-256(UTF8("huchuan/tok1/v1") ‖ UTF8(规范化后的 token 字符串))`；库泄露也取不了件 |
| shareId | 16 字节随机 → 32 位小写 hex；不是凭据，只用来选信令房间 |
| 四态 | `available`（有效且分享者在信令里）/ `offline`（有效但分享者不在）/ `revoked` / `expired`，只在 Worker 边界合成一次 |
| 撤销签名 | `signBytes("huchuan/revoke1/v1", [deviceId, token, ts, nonce])`，ts 允许 ±300 s，必须是该分享的 deviceId |
| WS 分享者签名 | `signBytes("huchuan/ws1/v1", [dev, nonce, ts])`，用该分享记录里的 pub 验；`dev` 必须等于记录里的 deviceId |
| tracker passkey | `hex(HMAC-SHA256(kGrp, UTF8("huchuan/trk1/v1")‖gid‖当前小时整数))`；服务端只存 `SHA-256("huchuan/trk1/v1"‖passkey)` 的哈希 |
| passkey 轮换 | 每小时换值，客户端重新 `POST /api/tracker/register`（带 `prevPasskey` 作为连续性证明）；不带证明且值不同则 409 |
| announce 应答 | `interval=600`、`min interval=300`、`numwant` 上限 32、compact peers 与 peers6、`external ip`（BEP 24） |
| tracker 错误 | HTTP 200 + bencode `{"failure reason": ...}`（tracker 惯例） |
| 信令 URL | 只有 `share=<shareId>`，**token 不进 URL**；token 只出现在 WS 帧 `share-join` 里 |
| 房间帧 | `share-join` / `share-incoming` / `offer` / `answer` / `ice` / `bye` / `err`；另有扩展帧 `joined{shareId,visitorId}`（访客配对成功），`bye` 的 code 用 `offline`（分享者不在）、`peer-left`（对方断开）、`gone`（已撤销） |
| 房间容量 | 最多 1 个访客：第二个连上来当场 `err{busy}` 并被关；同一分享的 share-join 限速 3 次/分钟 |
| 分享上限 | 同机有效分享 ≥20 回 429；单文件 ≤1 GiB、≤10 个、文件名 ≤255 B |

## 结构

```
src/index.js    路由 + Worker 入口（default export fetch）
src/announce.js BEP 3 语义、原始查询串解析（info_hash 是二进制，不能交给 URLSearchParams）
src/swarm.js    TrackerSwarm DO：一个 groupHash20 一个实例，存群注册信息（只有哈希）与 peer 表
src/share.js    /api/share* 的 HTTP 面与四态合成
src/registry.js ShareRegistry DO：一条分享一行（单实例，按 link-share.md §4.3）
src/signal.js   /signal 的握手验签与转发
src/room.js     SignalRoom DO：一个分享一个房间，WebSocket Hibernation 写法
src/crypto.js   Web Crypto 封装：B32、signBytes、P-256 验签、两种哈希
src/bencode.js  bencode 编解码
src/ws.js       101 应答（Node 下退化成普通对象，测试用）
```

## 本地测试

```bash
node --test worker/                       # 在仓库根目录
cd worker && npm test                      # 等价写法
```

Node 24 的 test runner 把位置参数一律当 glob，传目录会被当成模块执行，
所以 `worker/index.js` 只是把 `test/` 下的用例 import 进来看，不是部署入口（部署入口是 `src/index.js`）。

测试用内存 shim 跑真实代码（`test-kit/memory.js`）：内存 KV 存储 + `acceptWebSocket` /
`getWebSockets` / `serializeAttachment` + 自己的 `WebSocketPair`。覆盖 announce 正常/错 passkey/
未注册、分享创建与撤销、信令配对与撤销后拒绝、并发两个访客只放一个、B32 与 signBytes 字节布局。

## 已知边界

- 不做 TURN（`link-share.md` §4.5），对称 NAT 与 CGNAT 打洞失败如实报 `connect-failed`。
- 群注册是「先到先得」：第一个登记的 infoHash 拥有该 swarm，之后换 passkey 要带 `prevPasskey`。
  没有账号体系，这是无服务端邀请制的固有代价。
- 撤销后踢人只防重连；正在传的字节由分享者本地断流（分享者是唯一字节源）。
- 日志只打事件与结果，不打 token、SDP、访客地址（`link-share.md` §7）。

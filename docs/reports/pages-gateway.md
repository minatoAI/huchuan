# pages.dev 网关：中国大陆的入口（考古 → 选型 → 部署 → 实测）

- 日期：2026-11-06 20:32–20:55（Asia/Shanghai）
- 设备：Mi MIX 2S（5b94673a，Android 13，curl 7.80.0 / Android 系统自带）
- 账户：`f72eb4995b4129225c4761434d61b733`；wrangler 3.114.17
- 产物：`pages/`（新增）、`docs/deploy-cloudflare.md` §5、本报告、`artifacts/pages-gateway/`
- 边界：没动 `worker/`。Worker 的代码、配置、`workers_dev` 全部保持原样，这一层只是转发。

## 结论

**成立，可以当默认入口。** 三条必验全过，回退档也补验了：

| 验证 | 结果 | 原始输出 |
|---|---|---|
| 本机访问 `https://huchuan-a9f.pages.dev/health` | 200，连打 5 次全过，0.9–3.5 s（同机直连 workers.dev 12 s 超时） | `raw/01-local-curl.txt` |
| 手机注册 + 两次 announce | 注册 `created:true`；announce 回 bencode，`external ip` 等于手机自己的出口 IP | `raw/02-phone-mix2s.txt` |
| `/signal` WebSocket | 裸升级 101；分享者 + 访客两条连接走完 join / offer / ice / bye，8/8 | `raw/03-ws-signal.txt` |
| 回退档 B（临时路由，已删） | HTTP 通；WebSocket 同样 8/8 | `raw/05-fallback-b.txt`、`raw/05-fallback-b-ws.txt` |

选型落定：**service binding**（`env.RELAY.fetch(request)`），不是 fetch workers.dev。
信令可用，且不是"能握手但帧过不去"——上下行与跨连接转发都验了。

另外把**回退档 B**（Pages Function 里直接 fetch workers.dev）也实测了一遍（§四.4）：
两条路都通，选 A 是因为少一跳、少一次计费，不是因为 B 不可行。

## 一、旧项目怎么做的（p2p-share）

旧项目 `E:\agentHome\p2p-share` 用的是同一招，而且已经上过生产（它自己的 `docs/16`、`docs/17` 有线上验收记录，
域名现在还能打开）。可直接抄的四件事：

1. **Pages 里配 service binding**：`app/pages/wrangler.jsonc`

   ```jsonc
   "services": [
     { "binding": "LIGHTHOUSE", "service": "p2p-lighthouse" },
     { "binding": "TRACKER", "service": "p2p-bt-tracker" }
   ]
   ```

   同一个文件里还写了为什么非有这一层：DO 不能定义在 Pages 项目里
   （官方原话 "You cannot create and deploy a Durable Object within a Pages project"）。
   我们的情况一样——三个 DO 都在 `huchuan-relay` 里，所以 Worker 必须独立存在，Pages 只做转发。

2. **转发就是一行，原样透传整个 Request**：`app/pages/functions/api/[[path]].js`

   ```js
   export const onRequest = ({ request, env }) => env.LIGHTHOUSE.fetch(request);
   ```

3. **WebSocket 靠的就是"原样"**：`app/pages/functions/ws/[[path]].js` 与 `api` 那行一模一样，
   注释写得很直白：

   > 这里必须原样透传 Request，不能重建：Upgrade 头一旦丢掉就升级不成 WebSocket，页面会一直停在"正在连灯塔"。

4. **生产证据**：`app/README.md` 第 145 行以下——"用户访问的是 pages.dev。Worker 那边一个公开地址都不留，
   只有自己的 Pages 项目能调到它，因为 service binding 的请求不出 Cloudflare 网络。"
   `docs/17-sdp丢失问题-外部评审.md` 第 101–108 行记录了线上形态：

   ```
   wss://p2p-share-web.pages.dev/ws/presence
   // pages/functions/ws/[[path]].js
   export const onRequest = ({ request, env }) => env.LIGHTHOUSE.fetch(request);
   **关键点：这个转发是原样透传整个 Request，包括 WebSocket 升级，不区分客户端是浏览器还是原生进程。**
   ```

   `docs/16-E2E抖动的排查记录.md` 第 318–324 行是原生端两进程都连 pages.dev 跑通配对与传输的记录。

**和旧项目的差异**（两处，都是简化）：

- 旧项目的 tracker 在独立的 Worker 上、路径前缀不同，所以 `functions/tracker/[[path]].js` 做了路径重写。
  我们的 `/announce` 就是 Worker 的根路径，不需要重写，直接透传。
- 旧项目 Pages 还兼着托管前端静态站；我们不需要前端，`public/` 只放了两个极简页面。

## 二、这次新增了什么

```
pages/
  wrangler.toml              name=huchuan / pages_build_output_dir=./public / services→RELAY=huchuan-relay
  public/index.html          根路径一个极简页
  public/404.html            见 §五.2，没有它会回 200 + index.html
  functions/health.js        /health
  functions/announce.js      /announce
  functions/signal.js        /signal       ← WebSocket 升级走这里
  functions/ws.js            /ws（/signal 的别名）
  functions/api/[[path]].js  /api/*
```

五个转发文件的正文都是同一行：

```js
export const onRequest = ({ request, env }) => env.RELAY.fetch(request);
```

不重建 Request、不改头、不读 body、不复制业务逻辑。鉴权（签名、token、passkey）全部仍由 Worker 验，
实测见 §四.2 的 [6]：错 passkey 回的是 `d14:failure reason11:bad passkeye`，不是网关自己的错。
CORS 也照转——Worker 自己回的 `Access-Control-*` 原样出去。

**Worker 为什么不用改**：`worker/src/index.js` 的 `route()` 只看 `url.pathname`，不看 Host。
Host 变成 `huchuan-a9f.pages.dev` 也照样命中同一分支。唯一的连带影响是 `CF-Connecting-IP`，
见 §五.1——那个是好事。

## 三、选型依据

### 3.1 为什么是 service binding

官方定义（[Service bindings](https://developers.cloudflare.com/workers/runtime-apis/bindings/service-bindings/)）：

> Service bindings allow one Worker to call into another, **without going through a publicly-accessible URL**.

请求在 Cloudflare 网络内部完成，不落到公网。所以客户端所在网络能不能解析 workers.dev，
和这条链路彻底无关——这正是我们要的效果。

Pages Functions 支持它，官方 Bindings 文档给的示例就是我们要写的那一行（[Pages · Bindings](https://developers.cloudflare.com/pages/functions/bindings/#service-bindings)）：

> To bind your Pages Function to a Worker, configure a Service binding in your Pages Function…
> `return context.env.SERVICE.fetch(context.request);`

配置在 Pages 的 wrangler 文件里，键名 `services`（[Pages · Configuration](https://developers.cloudflare.com/pages/functions/wrangler-configuration/) 明列为支持的绑定类型）。

### 3.2 WebSocket 到底能不能过（这条是重点）

能，生产环境能。三条依据：

1. **机制**：原样转发整个 `Request` 时 `Upgrade: websocket` 头保留，Worker 侧
   `request.headers.get("upgrade")` 仍是 `websocket`，照常 `new WebSocketPair()` 并回 101。
   我们在 §四.3 的 A1 抓到了裸 101（`upgrade=websocket connection=upgrade`），B1–B7 又验了帧。
2. **官方 issue 的措辞**：workers-sdk [#13054](https://github.com/cloudflare/workers-sdk/issues/13054)
   标题是「WebSocket upgrades proxied through a Worker service binding do not work **in Vite dev mode**」。
   里面的 workaround 明确要「keep the service binding WS proxy working correctly **in production** where workerd handles it natively」。
   即：坏的只是本地 vite 插件那条路径，生产没问题。
3. **本仓库旧项目的生产事实**：`wss://p2p-share-web.pages.dev/ws/presence` 用同一手法跑了数月，
   浏览器与原生端共用（§一）。

### 3.3 为什么不用另外两条路

| 方案 | 不选的理由 |
|---|---|
| Pages Function 里 `fetch("https://…workers.dev/…")` | 能用（Workers 之间的 fetch 是边缘子请求，与客户端网络无关），但多一跳、按一次额外 Worker 调用计，且要自己保证 `Upgrade` 头与 101 不被改写。留作回退档，§四.4 实测也通 |
| 自定义域名绑到 Worker | 要域名；大陆域名要备案。AGENTS §9 已经定过走 pages.dev，不重开 |
| 让客户端直连 workers.dev | 本轮实测就是它不通（§四.1 [1]） |

## 四、实测原始输出

复跑方法见 §七。三个文件都在 `artifacts/pages-gateway/raw/`。

### 4.1 本机（`raw/01-local-curl.txt`）

```
### 本机（MSYS bash on Windows），2026-11-06T12:45:40Z
### 本机公网出口
222.208.209.39

### [1] 控制组：本机直连 workers.dev（预期失败）
workers.dev/health -> http=000 time=12.002865s
curl_exit=28

### [2] 本机走 pages.dev：/health（连打 5 次，看稳定性）
{"ok":true,"service":"huchuan-worker","version":"0.1.0","time":"2026-11-06T12:45:55.655Z","bindings":{"SWARM":true,"REGISTRY":true,"ROOM":true}}  [http=200 time=1.052926s]
{"ok":true,"service":"huchuan-worker","version":"0.1.0","time":"2026-11-06T12:45:59.122Z","bindings":{"SWARM":true,"REGISTRY":true,"ROOM":true}}  [http=200 time=3.478959s]
{"ok":true,"service":"huchuan-worker","version":"0.1.0","time":"2026-11-06T12:46:01.000Z","bindings":{"SWARM":true,"REGISTRY":true,"ROOM":true}}  [http=200 time=1.778899s]
{"ok":true,"service":"huchuan-worker","version":"0.1.0","time":"2026-11-06T12:46:01.983Z","bindings":{"SWARM":true,"REGISTRY":true,"ROOM":true}}  [http=200 time=1.036426s]
{"ok":true,"service":"huchuan-worker","version":"0.1.0","time":"2026-11-06T12:46:03.011Z","bindings":{"SWARM":true,"REGISTRY":true,"ROOM":true}}  [http=200 time=0.930218s]

### [3] 路由边界
  GET / -> 200
  GET /nope -> 404
  GET /api -> 404
  GET /api/share/ZZZZZZZZZZZZZZZZZZZZZZZZZZ -> 404
```

读法：`/api/share/<未知 token>` 回 404 是 **Worker 回的**（转发生效），`/nope` 回 404 是 **Pages 静态返回的**
（说明未匹配路径没被误转发）。两条都能区分开，路由边界是干净的。

再补一条路由探测（`raw/04-routes.txt`）。`/ws` 是 `/signal` 的别名，两个都必须被接住：

```
GET /ws -> {"ok":false,"error":"websocket upgrade required"} [http=426]
GET /signal -> {"ok":false,"error":"websocket upgrade required"} [http=426]

### /ws 带 Upgrade 头但不给 share 参数：应由 Worker 的参数校验回 400（不是网关的错）
HTTP/1.1 400 Bad Request
{"ok":false,"error":"invalid share"}
```

426 是 Worker 自己判的（缺 upgrade 头），400 也是 Worker 判的（缺 `share` 参数）。
两条错误码都出自业务层，说明转发层没有吞请求、也没有自己造响应。

### 4.2 手机（`raw/02-phone-mix2s.txt`）

命令：`adb -s 5b94673a push phone-probe.sh /data/local/tmp/` 后
`MSYS_NO_PATHCONV=1 adb -s 5b94673a shell "sh /data/local/tmp/pages-probe.sh"`。

```
### device: Mi MIX 2S / android 13 / 2026-11-06T12:46:50Z
### group: infoHash=4505c703f9c29bf14e01ea85250f1d61c5bd5b6f gid=ABCDEFGH12345678

### [0] 手机自己的公网出口（用来核对 announce 回的 external ip）
222.208.209.39

### [1] 控制组：手机上直连 workers.dev（预期失败）
workers.dev/health -> http=000 time=7.710196s
curl_exit=28

### [2] 手机上走 pages.dev：/health
{"ok":true,"service":"huchuan-worker","version":"0.1.0","time":"2026-11-06T12:47:02.567Z","bindings":{"SWARM":true,"REGISTRY":true,"ROOM":true}}
[http=200 time=2.386190s]

### [3] 注册群（POST /api/tracker/register）
{"ok":true,"created":true}
[http=200 time=1.345433s]

### [4] announce（GET /announce，event=started）
d8:completei1e11:external ip14:222.208.209.3910:incompletei0e8:intervali600e12:min intervali300e5:peersle6:peers6lee
[http=200 time=2.302889s]

### [5] 第二次 announce（event=none）
d8:completei1e11:external ip14:222.208.209.3910:incompletei0e8:intervali600e12:min intervali300e5:peersle6:peers6lee
[http=200 time=0.756916s]

### [6] 错误 passkey（应回 failure reason，说明鉴权仍是 Worker 在验）
d14:failure reason11:bad passkeye
[http=200]
### done
```

看点：[0] 的出口 IP 与 [4]/[5] 里 `external ip14:222.208.209.39` **完全一致**。这条比"能通"更要紧，见 §五.1。

### 4.3 WebSocket（`raw/03-ws-signal.txt`）

探针 `artifacts/pages-gateway/ws-probe.mjs`：自己生成 P-256 密钥、算 `deviceId`、走真实的
`POST /api/share` 建分享，再连两条 `wss://huchuan-a9f.pages.dev/signal`。签名与哈希直接
`import` 了 `worker/src/crypto.js`（纯 WebCrypto，Node 24 能直接跑），保证和 Worker 侧同一套协议。

```
base=https://huchuan-a9f.pages.dev
deviceId=MVFKJ4MVGWAGT1K5 pub=be4c9249c4bc3f95…
+1752ms  POST /api/share (A/裸升级) -> 201 shareId=cc4b214dd9bf42a3356bd59a3563387d token=M7BPFN2HP8EPH6ETEHYR8GFHKC
+5521ms  POST /api/share (B/双向会话) -> 201 shareId=6d302d65a97d1e4a183234abd79ec134 token=K2X2XQRBWN0081H4P0W2NEYRZW
+6426ms  GET wss://huchuan-a9f.pages.dev/signal?role=guest&share=cc4b214dd9bf42a3356bd59a3563387d&ep=GPPROBE00001  -> HTTP 101 upgrade=websocket connection=upgrade
PASS  A1 /signal 在 pages.dev 上返回 101  — HTTP 101
+13263ms  [sharer] WS 已打开
PASS  B1 分享者 WS 经 pages.dev 升级成功  — 签名握手 ts=1793969185
+14125ms  [guest] WS 已打开
PASS  B2 访客 WS 经 pages.dev 升级成功
+14388ms  [sharer] <- {"t":"share-incoming","shareId":"6d302d65a97d1e4a183234abd79ec134","visitorId":"PVMK1SA2","epPub":null}
+14407ms  [guest] <- {"t":"joined","shareId":"6d302d65a97d1e4a183234abd79ec134","visitorId":"PVMK1SA2"}
PASS  B3 访客发 share-join，收到 joined（上行帧到了房间）  — visitorId=PVMK1SA2
PASS  B4 分享者收到 share-incoming（下行帧穿回另一条连接）  — visitorId=PVMK1SA2
+14702ms  [guest] <- {"t":"offer","sdp":"pages-gateway-probe-offer"}
PASS  B5 分享者 -> 访客 转发 offer  — sdp=pages-gateway-probe-offer
+14996ms  [sharer] <- {"t":"ice","candidate":"pages-gateway-probe-ice"}
PASS  B6 访客 -> 分享者 转发 ice  — candidate=pages-gateway-probe-ice
+15276ms  [sharer] <- {"t":"bye"}
PASS  B7 访客 bye 传给分享者  — code=-
+15568ms  [guest] 关闭 code=4003 reason=bye

8/8 通过，用时 15706ms
```

B5/B6 是双向的：分享者发的 offer 访客收到了，访客发的 ice 分享者也收到了。
只验"能握手"是不够的——帧过不去的话，链接分享会在建连后静默卡死。

### 4.4 回退档 B 也验了（临时路由，验完已删）

方案表里的 B（Pages Function 里直接 fetch workers.dev）不是纸面备选，实测同样通。
做法：临时加一个 `pages/functions/__fallback-probe/[[path]].js`，把请求重写到
`https://huchuan-relay.autoai233.workers.dev`，头整体复制、body 直传，然后**从客户端仍然只访问 pages.dev**。
验完立即删掉该路由并重新部署，`GET /__fallback-probe/health` 已回到 404（当前线上无此路由）。

```
### 回退档 B 探针：Pages Function 里直接 fetch workers.dev（2026-11-06T12:51:40Z）
[B1] GET /__fallback-probe/health
{"ok":true,"service":"huchuan-worker","version":"0.1.0","time":"2026-11-06T12:51:41.490Z","bindings":{"SWARM":true,"REGISTRY":true,"ROOM":true}}
[http=200 time=0.929550s]

[B2] GET /__fallback-probe/api/share/<未知 token>
{"status":"not-found"}
[http=404]
```

WebSocket 也用同一个探针跑了完整 8 项（`raw/05-fallback-b-ws.txt`）：

```
+7469ms  GET wss://huchuan-a9f.pages.dev/__fallback-probe/signal?role=guest&share=0dbc1fa7… -> HTTP 101 upgrade=websocket connection=upgrade
PASS  A1 /signal 在 pages.dev 上返回 101  — HTTP 101
…（B1–B7 同 §4.3）…
8/8 通过，用时 11643ms
```

结论：**两条路都通**。选 A 是因为少一跳、少一次计费请求、少一处要自己维护的头部处理，
不是因为 B 不可行。哪天 service binding 出问题，按 `docs/deploy-cloudflare.md` §5.5 换成 B 即可，
换完按 §七重跑。

## 五、实测里查出来的三件事

### 5.1 客户端真实 IP 被保住了（不是运气，要盯着）

announce 记的 peer 地址来自 `cf-connecting-ip`（`worker/src/util.js:118`）。
如果 service binding 转发时这个头被换成了 Cloudflare 边缘 IP，tracker 会给全世界记同一个地址，
跨网发现会"看起来成功、实际连不上"，而且不会有任何报错。

实测：手机自己的出口是 `222.208.209.39`（ifconfig.me），announce 回的 `external ip` 也是 `222.208.209.39`。
一致，说明头是原样带过去的。以后谁改这一层（比如换成 fetch workers.dev 那套手搓 Request），
必须重跑一次这条比对——这是最容易静默坏掉的地方。

### 5.2 Pages 会把未匹配路径回成 200 + index.html

第一次部署后 `GET /nope` 返回 **200**，body 是 `index.html`。这是 Pages 的 SPA 兜底行为：
项目里没有 `404.html` 时它拿首页顶上。对网关是个坑——客户端把 200 当成功，再去 `JSON.parse`
一段 HTML。

修法：加 `pages/public/404.html`。加完重测：`/nope` → 404，`/api` → 404，`/` → 200，`/health` → 200
（见 `raw/01-local-curl.txt` 的 [3]，那份是修正后重录的）。

### 5.3 额度大概要按两倍估

一次客户端请求 = 1 次 Pages Function 调用 + 1 次 Worker 调用。
官方说 service binding 的子请求不计额外请求费（问题是这段写在 Workers 的 Standard 定价模型下，
[Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)），
而免费层的计量口径没有单独写。保守按两次算：10 万/天 → 约 5 万次客户端请求，
比 `docs/deploy-cloudflare.md` §1 那张表乐观值砍一半。

**未核实**：没有可用的账户计量接口可查，只能在 dashboard 的 Usage 里看。上线前核一次。

## 六、风险与未决

1. **单点还是那个域名**。workers.dev 被处置，pages.dev 也归 Cloudflare。真到那一步只能上自定义域名（要备案）。
   缓解：客户端的 base URL 必须是配置项，不能硬编码——目前 M2 的构建期配置项还没做，属于别的域。
2. **`workers_dev` 仍然开着**。本次边界是不改 `worker/`，所以没关。它意味着在能访问 workers.dev 的网络里，
   Worker 仍有一个公开地址。功能上无害（鉴权本来就是签名/passkey，不靠域名保密），
   要不要关掉 `worker/wrangler.toml` 的 `workers_dev` 由 L0 定。
3. **一次未复现的瞬时失败**：某轮回放时 `/health` 出现过一次 `http=000 / 16.27 s`，
   紧接在一条 workers.dev 超时之后。同一分钟起连打 8 次全 200（0.9–3.0 s），
   重录时再打 5 次也全 200（0.9–3.5 s）。判断是本地网络瞬时抖动，不是网关问题，
   但没有证据排除，先记在这里。上线前建议跑一段长一点的稳定性观察。
4. **只在约 15 分钟内验证过**。WebSocket 的长连接保活（`link-share.md` 里分享者挂机一整天那种）
   没有跑长测。Pages Function 作为转发层对长连接的影响，官方没有专门写，只能靠时间验。
5. **域名不是 `huchuan.pages.dev`**，是 `huchuan-a9f.pages.dev`。平台给的，改不了。
   所有文档、客户端配置都写实际这一个。

## 七、复跑

以下都在仓库根目录（`E:\agentHome\bt-share-app`）执行。

```bash
# 1) 健康检查（几秒）
curl -s -m 20 -w '\n[%{http_code} %{time_total}s]\n' https://huchuan-a9f.pages.dev/health

# 2) 手机侧注册 + announce
node artifacts/pages-gateway/gen.mjs run-$(date +%s)     # 换一个新群，参数写进 probe.json
#    把 probe.json 里的值填进 artifacts/pages-gateway/phone-probe.sh 的三行变量，然后：
MSYS_NO_PATHCONV=1 adb -s 5b94673a push \
  "E:/agentHome/bt-share-app/artifacts/pages-gateway/phone-probe.sh" /data/local/tmp/pages-probe.sh
MSYS_NO_PATHCONV=1 adb -s 5b94673a shell "sh /data/local/tmp/pages-probe.sh"

# 3) WebSocket（8 项断言，全过 exit 0）
node artifacts/pages-gateway/ws-probe.mjs
#    想验回退档 B 就在末尾加 base 参数，例如：
#    node artifacts/pages-gateway/ws-probe.mjs https://huchuan-a9f.pages.dev/__fallback-probe
```

第 2 步的 `phone-probe.sh` 是把参数写死在脚本里的（方便推到手机上一次跑完），
所以换群要先改脚本。这不是因为参数不能传，是因为 `adb shell` 一路转义容易出错。

注意：`adb.exe` 是 Windows 程序，push 的本地路径要写 `E:/...` 或 `E:\...`，
MSYS 的 `/e/...` 它不认；远程路径要用 `MSYS_NO_PATHCONV=1` 挡掉路径转换。

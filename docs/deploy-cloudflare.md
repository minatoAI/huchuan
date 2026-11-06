# 部署：Cloudflare Worker（tracker + 信令 + 分享管理）

**已部署，2026-11-06**：<https://huchuan-relay.autoai233.workers.dev>（Worker 名 `huchuan-relay`，免费的 `*.workers.dev` 子域，未绑自定义域名，账户 `f72eb4995b4129225c4761434d61b733`）

- 健康检查：<https://huchuan-relay.autoai233.workers.dev/health> → `{"ok":true,"service":"huchuan-worker","version":"0.1.0","bindings":{"SWARM":true,"REGISTRY":true,"ROOM":true}}`
- 撤销这次部署（删干净，不留资源）：

  ```bash
  cd worker && CLOUDFLARE_ACCOUNT_ID=f72eb4995b4129225c4761434d61b733 npx --yes wrangler@3 delete --name huchuan-relay
  ```

> 用 wrangler **3**（本次是 3.114.17）。v4 的配置格式与命令行为不同，别顺手升级。

**中国大陆的对外入口是 pages.dev，不是 workers.dev**：客户端默认地址写 `https://huchuan-a9f.pages.dev`，
Worker 藏在 Pages Functions 后面。为什么、怎么部署、坏了怎么退，见 [§5](#5-中国大陆访问pagesdev-入口)。

## 0. 三分钟部署

前置：Node 20+、一个 Cloudflare 账户（免费层即可，不用绑卡）。

```bash
# 1) 登录（浏览器里点一次授权）
npx --yes wrangler@3 login

# 2) 部署：仓库根目录下执行，配置在 worker/wrangler.toml
cd worker
npx --yes wrangler@3 deploy
```

`deploy` 的输出末尾会给出地址，形如：

```
Uploaded huchuan-relay (2.10 sec)
Deployed huchuan-relay triggers (0.76 sec)
  https://huchuan-relay.<你的子域>.workers.dev
```

```bash
# 3) 冒烟（在能访问 *.workers.dev 的机器上）
node worker/smoke.mjs https://huchuan-relay.<你的子域>.workers.dev
```

冒烟覆盖 `/health`、群注册、两次 announce 能否互相看见、错 passkey、未注册 info_hash、分享 404、`/signal` 426。
全过退出码 0。

```bash
# 4) 填进客户端：把 base URL 写进客户端配置（M2 的构建期配置项）
#    走 pages.dev（中国大陆可用），不要写 workers.dev，理由见 §5
#    tracker:  https://huchuan-a9f.pages.dev/announce
#    信令:     wss://huchuan-a9f.pages.dev/signal
#    分享 API: https://huchuan-a9f.pages.dev/api/share
```

> Pages 网关的部署是另一步，见 §5。先 Worker 后 Pages，顺序不能反。

部署时发生了什么（一屏之内）：

| 件 | 值 | 出处 |
|---|---|---|
| Worker 名 | `huchuan-relay` | `worker/wrangler.toml` 的 `name` |
| 入口 | `src/index.js` | `main` |
| Durable Objects | `TrackerSwarm` / `ShareRegistry` / `SignalRoom` | `[[durable_objects.bindings]]` |
| 迁移 | `[[migrations]] tag="v1" new_sqlite_classes=[...]` | 免费层要求 DO 用 SQLite 后端，**不要**用旧的 kv 形式 |
| 子域 | `workers_dev = true` | 不绑自定义域名 |
| 兼容日期 | `2025-06-01` | wrangler 3 的已知范围 |

## 1. 免费额度（2026-11 口径，部署前复核一次）

| 项 | 免费层 | 折算 |
|---|---|---|
| Worker 请求 | 10 万/天 | 全站共同上限；tracker 与信令按路径分桶，一条被刷另一条不跟着挂（`link-share.md` §4.3） |
| Durable Object 请求 | 10 万/天 | 每次 announce / 每次分享查询各 1 次；错误一律不重试，避免放大 |
| DO 计费时长 | 空闲不计（`state.acceptWebSocket` 是 Hibernation 写法） | 分享者挂一整天的那条连接不会按 duration 全天计费 |
| 出站流量 | 不产生 | 文件字节永不经过服务器 |

现实折算（`link-share.md` §10.2）：announce 固定 10 分钟一次 → 144 次/设备/天，全天在线约 694 台吃满；
每天在线 8 h（48 次）约 2,083 台。混算 1,000 台在线 8 h ＋ 2,000 次取件 ≈ 5.8 万请求（58%）。

额度接近上限时按顺序用三条，**都不改客户端**：

1. **降频**：服务端把 `ANNOUNCE_INTERVAL_SEC` 调大（`worker/wrangler.toml` 的 `[vars]`，取消注释即可），
   客户端只跟随服务端下发的 interval。
2. **分片**：在线扇出 DO 按 deviceId 前缀分实例（注册表不动）。
3. **收紧**：分享有效期 7 天 → 24 h。

## 2. 不部署会怎样（硬约束）

只失去两件事：**跨网发现**（`/announce`）与**链接分享**（`/signal` 与 `/api/share*`）。

局域网 mDNS、手动 IP:port、私有 BT 传输、建群／入群／评论／组队**全都照常**。

界面上不弹任何错误弹窗（`link-share.md` §10.3）：P0 走既有的「仅局域网可用」一行警告后自动进入；
P7 状态行写「仅局域网」，「…」里没有「分享链接」；P7 没有「分享管理」；系统分享进来一句 toast
「分享服务未配置」后退出，不建链接、不留半截状态。

## 3. 运维

| 要做的事 | 命令 |
|---|---|
| 看线上日志（结构化 JSON，grep `"ev":"announce"`） | `cd worker && npx --yes wrangler@3 tail` |
| 重新部署 | `cd worker && npx --yes wrangler@3 deploy` |
| 只跑本地测试 | `node --test worker/` |
| 删除（撤干净） | `cd worker && npx --yes wrangler@3 delete --name huchuan-relay` |
| 看账户与登录态 | `npx --yes wrangler@3 whoami` |

多账户时给命令加 `CLOUDFLARE_ACCOUNT_ID=<账户 ID>`，不要把账户 ID 写死在代码里。

## 4. 本次部署的事实

- 部署时间：2026-11-06；wrangler 3.114.17；上传 49.7 KiB（gzip 12.0 KiB）。
- 线上已验证（GET）：
  - `/health` → `{"ok":true,"service":"huchuan-worker","version":"0.1.0","bindings":{"SWARM":true,"REGISTRY":true,"ROOM":true}}`
  - `/announce?info_hash=<未注册 20B>&passkey=<64 hex>` → `d14:failure reason24:info hash not registerede`（bencode，说明 tracker 与 SWARM DO 在线上真的跑起来了）
  - `/api/share/<未知 token>` → `{"status":"not-found"}`
- 未能在本机验证的：需要 POST 的那几条（`/api/tracker/register` 与 `/api/share` 创建、以及注册后的正向 announce）。
  原因是本机出网按 SNI 白名单限制，`*.workers.dev` 连不上（DNS 也被污染），
  `api.cloudflare.com` 与部署通道可用。请在能开 workers.dev 的机器上跑一次：

  ```bash
  node worker/smoke.mjs https://huchuan-relay.autoai233.workers.dev
  ```

  这条脚本会自己注册一个临时群、发两次 announce、验错 passkey 与未注册 info_hash，全绿退出码 0。

  > 2026-11-06 补充：这两条**已经**通过 pages.dev 入口在手机上验过了（注册 + announce + 错 passkey，
  > 原始输出在 `docs/reports/pages-gateway.md`）。不需要另找能开 workers.dev 的机器。

## 5. 中国大陆访问：pages.dev 入口

**一句话**：客户端只认 `https://huchuan-a9f.pages.dev`，Worker 藏在 Pages Functions 后面，
用 service binding 转发；Worker 那一侧一行代码都没改。

### 5.1 为什么非要有这一层

同一台手机、同一个网络，只有域名不同：

| 请求 | 结果 |
|---|---|
| 本机 → `huchuan-relay.autoai233.workers.dev/health` | **12.0 s 超时**，curl 退出码 28 |
| 手机（Mi MIX 2S / Android 13）→ 同一地址 | **7.7 s 超时**，退出码 28；另一次是 0.06 s 连接被重置，退出码 7 |
| 本机 → `huchuan-a9f.pages.dev/health` | 0.9–3.5 s，HTTP 200 |
| 手机 → `huchuan-a9f.pages.dev/health` | 0.8–2.4 s，HTTP 200 |

`*.workers.dev` 被 DNS 污染 / SNI 阻断，`*.pages.dev` 通。这是客户端网络的问题，
服务器端再怎么改配置都没用，只能换域名。

### 5.2 选型：service binding，不是 fetch workers.dev

| 方案 | 走公网？ | WebSocket 升级 | 计费 | 结论 |
|---|---|---|---|---|
| **A. `env.RELAY.fetch(request)`（service binding）** | 不走，Cloudflare 内部 | 原样透传，生产可用 | 官方：子请求不计请求费 | **采用** |
| B. Pages Function 里 fetch `https://…workers.dev/…` | 走（一次边缘子请求） | 2026-11-06 实测也通，8/8 | 多一次 Worker 调用 | 回退档，写法见 §5.5 |
| C. 自定义域名绑到 Worker | 不走 | 可用 | 要域名；大陆域名要备案 | 不做（AGENTS §9） |

依据都是官方原文：

- service binding 的定义就是「让一个 Worker 调用另一个 Worker，**而不经过公开可达的 URL**」
  （[Service bindings](https://developers.cloudflare.com/workers/runtime-apis/bindings/service-bindings/)）。请求不出 Cloudflare 网络，
  所以客户端能不能解析 workers.dev 与这条链路无关。
- Pages Functions 支持的绑定里明确有 `services`，官方示例只有一行
  `return context.env.SERVICE.fetch(context.request);`
  （[Pages · Bindings](https://developers.cloudflare.com/pages/functions/bindings/#service-bindings)、
  [Pages · Configuration](https://developers.cloudflare.com/pages/functions/wrangler-configuration/)）——
  和 `pages/functions/*.js` 里写的一模一样。
- WebSocket：原样转发整个 `Request` 时 `Upgrade` 头保留，Worker 侧照常回 101。
  workers-sdk issue [#13054](https://github.com/cloudflare/workers-sdk/issues/13054) 讨论的是「Vite dev 模式下经 service binding 的 WS 升级不工作」，
  里面写着 workaround 的目标是「keep the service binding WS proxy working correctly **in production** where workerd handles it natively」——
  即生产环境本来就是好的，坏的只是本地 vite 插件。

### 5.3 仓库里新增了什么

```
pages/
  wrangler.toml              Pages 项目配置，services 绑定就这一段
  public/index.html          根路径一个极简页，避免 / 落到 404
  public/404.html            没有 404 页时 Pages 会把未匹配路径回成 200 + index.html；加了这个才回真 404
  functions/health.js        /health        → env.RELAY.fetch(request)
  functions/announce.js      /announce      → 同上
  functions/signal.js        /signal        → 同上（WebSocket 升级走这里）
  functions/ws.js            /ws（/signal 的别名）
  functions/api/[[path]].js  /api/* 全部    → 同上
```

每个转发文件是同一行代码，区别只在注释。**必须原样透传整个 Request**：
不重建、不改头、不读 body——`Upgrade` 头丢了信令就永远连不上，body 读了 Worker 那边就空了。

为啥 Worker 不用配合改：它按 `url.pathname` 路由，不看 Host，所以 Host 是 `pages.dev` 也照样命中。

### 5.4 部署

```bash
cd pages

# 1) 首次：建项目（之后跳过）
CLOUDFLARE_ACCOUNT_ID=f72eb4995b4129225c4761434d61b733 \
  npx --yes wrangler@3 pages project create huchuan --production-branch main

# 2) 部署
CLOUDFLARE_ACCOUNT_ID=f72eb4995b4129225c4761434d61b733 \
  npx --yes wrangler@3 pages deploy --branch main
```

三个坑：

1. **顺序**：先 `worker/`，后 `pages/`。service binding 是按 Worker 名字找的，名字写错或 Worker 没部署，
   部署能成功但请求会 500。
2. **`--branch main` 不能省**。不写的话可能落成「预览部署」，`huchuan-a9f.pages.dev` 不会更新，
   你看到的还是上一版。
3. **域名是平台给的**。项目名 `huchuan`，实际域名是 `huchuan-a9f.pages.dev`，那个 `-a9f` 改不了。
   客户端配置写实际域名，别写"项目名 + pages.dev"。

部署完自检：

```bash
curl -s https://huchuan-a9f.pages.dev/health
# {"ok":true,"service":"huchuan-worker","version":"0.1.0","bindings":{"SWARM":true,"REGISTRY":true,"ROOM":true}}
```

返回 Worker 的 JSON 就说明 Pages Function 找到了 Worker、binding 生效、三个 DO 都活着。

### 5.5 失败了怎么退

先分清是哪一层坏：

| 现象 | 含义 | 动作 |
|---|---|---|
| `/health` 正常回 JSON | 链路好的，问题在别处 | 不用动网关 |
| `/health` 回 500，Pages 日志有 `env.RELAY is undefined` | binding 没生效 | 查 `pages/wrangler.toml` 的 `service` 名字与 Worker 是否已部署；或去 dashboard Settings → Bindings 补 |
| `/health` 超时 / 证书错 | `pages.dev` 本身不通了 | 见下面「整体回退」 |
| `/health` 200 但 `/signal` 连不上 | WebSocket 层被改坏 | 检查有没有人把转发改成重建 Request |

**A → B 退化**（service binding 不可用时）：把五个 `functions/*.js` 的一行换成下面这段。
`Request` 里的头要整体复制过去（`Upgrade` / `Connection` 就在里面），body 用 `request.body` 直传：

```js
export async function onRequest({ request }) {
  const url = new URL(request.url);
  const target = new URL(url.pathname + url.search, "https://huchuan-relay.autoai233.workers.dev");
  const headers = new Headers(request.headers);
  headers.delete("host");
  const init = { method: request.method, headers };
  if (request.method !== "GET" && request.method !== "HEAD") init.body = request.body;
  return fetch(new Request(target, init));
}
```

这条路 2026-11-06 用临时路由实测过（验完已删，见 `docs/reports/pages-gateway.md` §四.4）：
`/health` 200（0.93 s），`/api/share/*` 404 正常，`/signal` 的 WebSocket 走完 8/8。
即 service binding 哪天不可用，退化档是能落地的，不是纸面方案。

改完按 `docs/reports/pages-gateway.md` §七的三条验证重跑一遍再发。

**整体回退**：客户端 base URL 改回 `https://huchuan-relay.autoai233.workers.dev`（只在能开 workers.dev 的网络可用），
或者把 Pages 项目删掉：

```bash
CLOUDFLARE_ACCOUNT_ID=f72eb4995b4129225c4761434d61b733 \
  npx --yes wrangler@3 pages project delete huchuan
```

**兜底认知**：网关挂了不等于功能全丢。局域网 mDNS、手动 IP:port、私有 BT 传输、建群/入群/评论全都照常，
丢的是跨网发现与链接分享（同 §2）。

### 5.6 额度：大概要多算一倍

一次客户端请求 = 1 次 Pages Function 调用 + 1 次 Worker 调用。
官方说 service binding 的子请求不计额外请求费（Workers Standard 定价模型），
但免费层的计量口径文档没有单独写清楚，**保守按两次算**：10 万/天 → 约 5 万次客户端请求。

上线前到 dashboard 的 Workers & Pages → Usage 核一次实际计数，再决定要不要动 §1 里的降频/分片/收紧三条。

### 5.7 已验证

2026-11-06，三条都过了，原始输出在 [pages-gateway.md](reports/pages-gateway.md)：

| 验证 | 结果 |
|---|---|
| 本机 `curl …/health` | 200，连打 5 次全过，0.9–3.5 s |
| 手机（Mi MIX 2S）注册 + 两次 announce | `{"ok":true,"created":true}`；announce 回 bencode，`external ip` 就是手机自己的出口 IP |
| `/signal` WebSocket | 裸升级返回 101；分享者 + 访客两条连接走完 join / offer / ice / bye，8/8 通过 |


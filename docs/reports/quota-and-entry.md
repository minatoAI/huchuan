# 额度与入口：能不能不要 Worker，只用 pages.dev

- 日期：2026-11-06（Asia/Shanghai）
- 方式：只读核查官方文档（2026-11 抓取）。没改代码、没部署、没动线上。
- 一句话：Worker 去不掉，但去掉它的收益本来就是零。Pages 到 Worker 那一跳按当前口径不额外计费，真正吃额度的是 Durable Object（DO）。

## 结论

1. DO 类不能部署在 Pages 项目里，必须有 Worker 承载。"全 Pages"最多做到"Worker 只剩一个壳"。
2. Pages Functions 可以直接绑 DO，不经过 Worker 转发。B 方案技术上成立。
3. service binding 的子请求按当前文档不额外计费，一次客户端请求记 1 次，不是 2 次。会记 2 次的是已废弃的 Bundled / Unbound 计费模型。
4. B 不省额度。A 和 B 都是 1 次 Workers 请求 + 1 次 DO 请求，改半天只省一跳内网转发。
5. 自定义域名直连 Worker 可行，但它解决的是封锁风险，不是额度；Cloudflare 也没有免费自定义域名这回事。

## 一、Pages Functions 支不支持直接绑 Durable Objects

支持。官方 [Pages · Bindings](https://developers.cloudflare.com/pages/functions/bindings/) 有专门一节，同时写死了 DO 的归属：

> You must create a Durable Object Worker and bind it to your Pages project using the Cloudflare dashboard or your Pages project's Wrangler configuration file. **You cannot create and deploy a Durable Object within a Pages project.**

配置放在 Pages 的 wrangler 文件里，[Pages · Wrangler configuration](https://developers.cloudflare.com/pages/functions/wrangler-configuration/) 把 `durable_objects` 列为支持的键，写法和 Workers 一样：

```toml
[[durable_objects.bindings]]
name = "SWARM"
class_name = "TrackerSwarm"
script_name = "huchuan-relay"   # DO 类所在的 Worker
```

`script_name` 的定义见 [Workers · Wrangler configuration](https://developers.cloudflare.com/workers/wrangler/configuration/)："The name of the Worker where the Durable Object is defined, if it is external to this Worker."

所以 B 可行：Pages 里 `env.SWARM.get(id).fetch(...)`，不再走 `env.RELAY.fetch(...)`。但 `huchuan-relay` 必须留着，它只是不再对外接业务请求。

## 二、没有 DO 的话，Pages Functions 能不能自己做 WebSocket 服务端

能握手，做不成房间。证据在 `artifacts/pages-gateway/raw/03-ws-signal.txt`：Pages Function 返回 101，两个客户端的帧双向转完，8/8。

做不成房间是因为没有共享点。[DO · WebSockets](https://developers.cloudflare.com/durable-objects/best-practices/websockets/) 的原话：

> A single Durable Object instance can coordinate between multiple clients (for example, chat rooms or multiplayer games)

分享者和访客的两条连接落到哪个 isolate 由运行时决定，isolate 之间不共享内存。要让 A 收到的帧送到 B，两条连接必须挂在同一个对象上。Pages Function 没有名字、没有存储、随请求生灭，当不了这个对象。

结论：SignalRoom 去不掉 DO，能换掉的只有外面那层转发。

## 三、service binding 的子请求算不算额外计费请求

标准计费模型下不算。[Workers · Pricing](https://developers.cloudflare.com/workers/platform/pricing/) 的 Service bindings 一节：

> Requests made from your Worker to another worker via a Service Binding do not incur additional request fees. ... this is billed as: One request (for the initial invocation of Worker A), The total amount of CPU time used across both Worker A and Worker B

同一页的例外条款，正好解释"双倍"这个印象从哪来：

> Only available on Workers Standard pricing. If your Worker is on the deprecated **Bundled or Unbound** pricing plans, incoming requests from Service Bindings are charged the same as requests from the Internet. In the example above, you would be charged for two requests.

Pages 这一侧走的是同一条规则。[Pages · Pricing](https://developers.cloudflare.com/pages/functions/pricing/) 写 "Requests to your Functions are billed as Cloudflare Workers requests" 和 "Pages supports the Standard usage model"；[Workers · Pricing](https://developers.cloudflare.com/workers/platform/pricing/) 开头写 "All Pages Functions are billed as Workers."

免费层与标准层差的不是免费/付费，是计费模型新旧。请求表脚注 1 覆盖 Free 与 Standard 两列：

> Inbound requests to your Worker. Cloudflare does not bill for subrequests you make from your Worker.

service binding 会另占子请求配额（免费 50 / 付费 10,000，[Service bindings](https://developers.cloudflare.com/workers/runtime-apis/bindings/service-bindings/)），这和请求计费是两回事，我们的转发每次只花 1 个子请求。要说清的缺口是：免费层没有一句"service binding 不计入 10 万/天"的独立声明，只能从上面脚注推，核对方法见最后一节。

## 四、用 D1 / KV / Queues 顶替 TrackerSwarm 与 SignalRoom

信令换不掉，tracker 换了也不省。

- KV：免费层 "Writes to different keys: 1,000 writes per day"、"Writes to same key: 1 per second"（[KV · Limits](https://developers.cloudflare.com/kv/platform/limits/)）。announce 一天就能打穿。
- D1：免费层 "Rows written: 100,000 / day"（[D1 · Pricing](https://developers.cloudflare.com/d1/platform/pricing/)），单个库单线程处理查询（[D1 · Limits](https://developers.cloudflare.com/d1/platform/limits/)）。TrackerSwarm 现在每次 announce 写 1 行，换 D1 还是 1 行，预算一样，还丢了内存里的 peer 列表。
- Queues：免费 10,000 operations/day，消息最多留 24 小时（[Queues · Pricing](https://developers.cloudflare.com/queues/platform/pricing/)）。它是 Worker 之间的投递，不是推给客户端的通道。
- DO 自己的免费额度（[DO · Pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/)）：100,000 requests/day、13,000 GB-s/day、100,000 rows written/day。请求口径含 "HTTP requests, RPC sessions, WebSocket messages, and alarm invocations"，WebSocket 上行消息按 20:1 折算成请求。

所以 D 方案只在"信令另找方案"时才成立，而信令没有替代品。

## 五、给 Worker 绑自定义域名直连

可行，代码几乎不用改。前提是 [Custom Domains](https://developers.cloudflare.com/workers/configuration/routing/custom-domains/) 写的两条：

> To add a Custom Domain, you must have: 1. An active Cloudflare zone. 2. A Worker to invoke.

也就是先有一个自己的域名，把 NS 指到 Cloudflare（Free 计划就够）。配完客户端直接打 `https://api.你的域名/announce`，不再经过 Pages。

大陆可达性取决于解析到的边缘 IP 在境内是否被阻断或限速、SNI 是否被针对性处置、有没有走境内节点。免费计划没有境内节点，[China Network](https://developers.cloudflare.com/china-network/) 原文是 "Traffic routed through servers outside the country faces significant latency and reliability issues due to China's network boundaries."，境内节点则 "available as a separate subscription for customers on an Enterprise plan"，还要 "a valid ICP filing"。官方对免费计划的大陆可达性没有任何承诺。

成本只有域名。Registrar 按成本价卖（[Registrar](https://developers.cloudflare.com/registrar/)："Buy and renew your domain at cost"），.com 量级 10 美元/年，便宜 TLD 更低，以结账页为准。

## 六、有没有官方的免费自定义域名

没有。Cloudflare 只给平台分配的免费子域：`<account>.workers.dev`（[workers.dev](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/)："Cloudflare Workers accounts come with a workers.dev subdomain"）和 `<project>.pages.dev`。自定义域名一律要求自备 zone。不花钱又要境内可达，只有 pages.dev。

## 方案对比

| 方案 | 客户端请求计费次数 | 国内可达性 | 实现成本 | 风险 |
|---|---|---|---|---|
| A 现状：pages.dev → service binding → Worker → DO | 1 次 Workers + 1 次 DO | 通，已实测（本机与手机，WS 8/8） | 0 | pages.dev 被处置；免费层计费口径无独立声明 |
| B 全 Pages：Pages Function 直接绑 DO | 1 次 Workers + 1 次 DO（与 A 相同，无额度收益） | 通，同 A | 中：`worker/src` 的业务逻辑搬进 `pages/functions`，Worker 只留 DO 类 | 搬家引入回归；Pages 直绑 DO 本仓库没实测过 |
| C 自定义域名直连 Worker | 1 次 Workers + 1 次 DO | 未实测，取决于 IP 与 SNI | 低：买域名、加 zone、配 custom domain，代码不动 | 域名被墙；仍需 Worker；境内节点要 Enterprise + ICP |
| D 改写掉 DO | tracker 变成 1 次 + 1 次 D1 行写（10 万/天）；信令做不成 | 同 A | 高 | 信令无解；写入预算与 DO 同级 |

## 推荐

保持 A，不做 B。B 的计费次数和 A 一模一样，只省一跳内网转发；D 不成立；C 是保险，不是优化。

真正能压额度的是这三处，和 Worker 无关：

1. announce 间隔。免费层约等于"100,000 ÷ 每天每客户端 announce 次数"，600 秒一档、只算 announce 约 690 个客户端全天在线，改 1800 秒就是三倍。Worker 已有 `ANNOUNCE_INTERVAL_SEC` 开关。
2. DO 行写。TrackerSwarm 每次 announce 是 2 行读 + 1 行写，10 万行写一天就封顶，和请求数同级。
3. DO 长连接时长。免费 13,000 GB-s/day，一个 128 MB 对象全天不休（最坏情况）约 11,000 GB-s，等于只够一个对象。SignalRoom 已经用 `acceptWebSocket` 走休眠，这块现在是安全的，别改回 `accept()`。

## 需要 L0 决定

1. 接不接受"service binding 按 1 次计"这个口径直接排期。不接受的话，先花半天做一次 dashboard 增量核对，再定容量。
2. 要不要现在买一个域名做 C 备胎。买的话客户端 base URL 必须可配置，M2 的构建期配置项目前还没做。
3. announce 默认间隔要不要从 600 秒调到 1800 秒。改了免费层容量翻三倍，代价是跨网发现变慢。

## 未核实的点

- 免费层是否把 service binding 那次调用计入 10 万/天：文档只有脚注可推，没有独立声明。核对方法：选一个安静的 10 分钟窗口，打 N 次 `/health`，在 dashboard 的 Workers 用量里看增量是 N 还是 2N，Pages 项目与 Worker 分开看脚本名。
- C 方案在境内的真实可达性：只能拿手机实测，不能用文档推断。pages.dev 今天通是既往实测结论，不是官方承诺。

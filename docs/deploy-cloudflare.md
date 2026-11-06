# 部署：Cloudflare Worker（tracker + 信令 + 分享管理）

**已部署，2026-11-06**：<https://huchuan-relay.autoai233.workers.dev>（Worker 名 `huchuan-relay`，免费的 `*.workers.dev` 子域，未绑自定义域名，账户 `f72eb4995b4129225c4761434d61b733`）

- 健康检查：<https://huchuan-relay.autoai233.workers.dev/health> → `{"ok":true,"service":"huchuan-worker","version":"0.1.0","bindings":{"SWARM":true,"REGISTRY":true,"ROOM":true}}`
- 撤销这次部署（删干净，不留资源）：

  ```bash
  cd worker && CLOUDFLARE_ACCOUNT_ID=f72eb4995b4129225c4761434d61b733 npx --yes wrangler@3 delete --name huchuan-relay
  ```

> 用 wrangler **3**（本次是 3.114.17）。v4 的配置格式与命令行为不同，别顺手升级。

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
#    tracker:  https://<子域>.workers.dev/announce
#    信令:     wss://<子域>.workers.dev/signal
#    分享 API: https://<子域>.workers.dev/api/share
```

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

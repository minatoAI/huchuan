# 公开 swarm 真实种子验证（2026-11-07）

**结论：可用。** 公开会话在真实公网种子上走完了 元数据 → 公网 peer → 数据 → piece 校验 全链路：21.4 GB 的种子 10 分钟窗口内又下 231.8 MB
（累计 299.3 MB，均速 396 KB/s，2–3 个公网 peer 全程在线），42 个完整 piece 的 SHA-1 逐个比对全部命中。没有卡点，「卡在哪一步」一节改为逐层实测。

## 种子（L0 桌面样本，只读、未入仓库）

- infohash v1 `d76bfe7c355fed37988aeece16c6ab1b4408c0dc`；种子文件 52 221 B；4 个文件，主片 .mkv 21 397 347 100 B
- 总大小 21 401 472 906 B（19.93 GiB）；piece 8 MiB × 2552；private=0；announce 仅 `http://tracker1.itzmx.com:8080/announce`
- 磁力 `magnet:?xt=urn:btih:d76b…` + dn + tr，全文 `artifacts/public-real/magnet.txt`

## 采样（30 s/次 × 21，t=0 为激活后首采；daemon 127.0.0.1:47200）

| t(s) | 状态 | swarm peer | 已下载 MB | 速率 KB/s | .torrent | progress |
| ---: | --- | ---: | ---: | ---: | ---: | ---: |
| 0 | downloading | 2 | 67.5 | 381 | 有 | 0% |
| 30 | downloading | 2 | 79.0 | 390 | 有 | 0% |
| 60 | downloading | 2 | 90.2 | 379 | 有 | 0% |
| 90 | downloading | 3 | 101.7 | 376 | 有 | 0% |
| 120 | downloading | 2 | 112.7 | 373 | 有 | 0% |
| 150 | downloading | 2 | 128.2 | 598 | 有 | 0% |
| 180 | downloading | 2 | 140.8 | 377 | 有 | 0% |
| 210 | downloading | 2 | 151.6 | 372 | 有 | 0% |
| 240 | downloading | 2 | 163.5 | 406 | 有 | 0% |
| 270 | downloading | 2 | 174.7 | 383 | 有 | 0% |
| 300 | downloading | 2 | 187.9 | 523 | 有 | 0% |
| 330 | downloading | 2 | 199.2 | 381 | 有 | 0% |
| 360 | downloading | 2 | 210.5 | 383 | 有 | 0% |
| 390 | downloading | 2 | 221.6 | 384 | 有 | 1% |
| 420 | downloading | 2 | 233.0 | 384 | 有 | 1% |
| 450 | downloading | 2 | 244.3 | 384 | 有 | 1% |
| 480 | downloading | 3 | 255.2 | 373 | 有 | 1% |
| 510 | downloading | 2 | 266.2 | 371 | 有 | 1% |
| 540 | downloading | 2 | 277.3 | 375 | 有 | 1% |
| 570 | downloading | 2 | 288.3 | 370 | 有 | 1% |
| 600 | downloading | 2 | 299.3 | 383 | 有 | 1% |

表外读数：元数据 `pub_magnet_added` 15:02:47.074 → `pub_magnet_metadata_received` 15:03:05.551（**18.5 s**），`.torrent` 落盘 53 231 B；
独立 announce 探针两次（15:00 / 15:11）均 HTTP 200，complete=4 / incomplete=4；daemon 在 t=150 s 采到自身对 `104.19.22.27:8080`
的连接（即 tracker announce）。swarm 端点 `5.193.143.34:51413`、`106.37.143.130:51413` 全程在线（后者在 tracker 名单内，
另者不在 → DHT/PEX），`31.200.249.237:31952` 断续；t=420 s 的 `172.66.44.146:443` 解析到本机 pages.dev，属本产品跨网 tracker，不计入。

## 逐层归因（每层都实测到了，没有卡点）

DNS：tracker1.itzmx.com → 104.19.22.22–31，12 ms。 tracker：HTTP 200，返回 8 个 peer，interval 10 735 s。 DHT：`dht_running=true`、UDP 6881 已绑（节点数无读口，见风险 3）。
peer：3 个公网端点建立 TCP，2 个全程在线。 数据：窗口内 231.8 MB。 完整性：停下后全量扫 2552 片，42 片完整、SHA-1 全对、0 片不符。

## 风险与未决

1. **状态口径不一致（缺陷候选）**：`import-magnet` 回 `sleeping / role=none / seeders=0 / progress=0`，但同一时刻 torrent 已在公开会话里真下载（磁盘块 17.7 → 28.5 → 36.8 MB 连续增长）；要再发一次 `POST …/active {"active":true}` 内核才有读数 —— UI 会显示「休眠 0%」而流量在跑。
2. `progressPercent` 是整数百分比：21.4 GB 种子 1% = 214 MB，「≥8 MB」在内核读数上看不出来；本轮用磁盘分配量 + piece SHA-1 校验代替。
3. 公开会话的 DHT 节点数与 tracker 状态都没有读口：`/api/state.daemon.dhtNodes` 与 heartbeat 的 `dht_nodes` 读的是私有会话（DHT 关，恒 0）；公开会话 `alert_mask=0x4d` 不含 tracker 类，TrackerAlert 不会到，归因只能靠外部探针 + netstat。
4. 未覆盖：private=1 种子误入公开会话的拒绝路径（本种子 private=0）、真双层 NAT / 无公网出口场景。

## 证据（artifacts/public-real/）

- 种子与导入：`torrent-info.json`｜`magnet.txt`｜`import.json`｜`api-state-final.json`；采样：`samples.jsonl`（21 条原数据）｜`sampler.out`｜`stats.json`｜`table.md`
- 探针与校验：`probe-tracker.json` / `probe-tracker-mid.json`｜`pieces-quiesced.json`｜脚本 `parse-torrent.mjs` / `sample.mjs` / `verify-pieces.mjs`
- 日志：`evidence-log.txt`（关键事件行）｜`daemon-data/daemon.log`（全量）

# 公开 swarm 复验：导入 ≠ 下载（2026-11-07）

**结论：新语义成立。** 导入后 251 s 静置 16 采，state 恒 `sleeping`、`downloadedBytes` 恒 0、`public/data` 恒 0 B；激活后 4.18 s 字节开始长（回包 24 ms / 11 ms）；休眠后 118 s 字节与磁盘一个字节没动，state 回 `sleeping`，公开会话自己停（`public_session_stopped reason=no_active_public_entry`）。元数据没丢：3 次激活都没有重拉元数据（`public_metadata_arrived` 计数 = 2 = 导入次数），后两次直接按缓存加种。

## 样本与环境

- 桌面 `.torrent`（只读、未入仓库）：`d76bfe7c…08c0dc`，19.93 GiB / 2552 片 / private=0 / 唯一 tracker `tracker1.itzmx.com:8080`。
- daemon `127.0.0.1:47200`（启动时空闲，收尾已 shutdown、端口已释放），数据目录 `artifacts/public-recheck/daemon-data`；jar 为 HEAD 构建（含 `downloadedBytes` / `torrent_adopted` 符号）。

## 三阶段读数（原数据 `samples-*.jsonl`）

| 阶段 | 采样 | state | downloadedBytes | totalBytes | public/data 占用 |
| --- | --- | --- | --- | --- | --- |
| 导入后静置 | 16 采 / 251 s | sleeping ×16 | 0 → 0 | 21 401 472 906 | 0 → 0 B |
| 激活（按缓存加种） | 46 采 / 150 s | downloading ×46 | 0 → 33 554 432 | 同上 | 42 536 960 → 45 666 304 |
| 休眠 | 24 采 / 118 s | sleeping ×24 | 33 554 432 → 33 554 432 | 同上 | 79 917 056 → 79 917 056 |
| 休眠后再激活 | 26 采 / 90 s | downloading ×26 | 0 → 60 854 154 | 同上 | 79 917 056 → 85 757 952 |

- 首字节出现在激活后 4.18 s（上一采 2.1 s 仍为 0），两次一致；activation 回包 24 ms / 11 ms，休眠回包 2 265 ms / 3 063 ms。
- 激活窗口的 42.5 MB 基数不是这次长的：那是 19:45:01 元数据到手后（adopt 接管路径，采样已停）写下的，本窗口再 +3.1 MB；复激活从 79.9 MB 起 +5.8 MB。
- 休眠期公开会话 `running=false / torrents=0`、`dht=0 / peers=0`：内核真没在传，不是界面写 0。

## /api/state.public（130 采）

- `dhtNodes` 0→82（复激活期最高 125），`dhtRunning=true`，会话端口 6881。
- `trackers[0]` 会翻：成功 14 采（`ok=true / state=replied / peers=8 / nextAnnounceInMs=12000`）；失败时 `ok=false / state=failed / failures=2–6 / nextAnnounceInMs=26–159 s / error=""`。
- `peers` 最多 54 个端点（复激活期 62），含在线做种者 `5.193.143.34:51413`、`14.152.83.170:6886`；同时刻独立 announce 探针 HTTP 200（complete=2 / incomplete=6 / 8 peers）。

## 顺带暴露的问题（都不是导入语义的回退）

1. **元数据变慢**：两次导入都在 timeoutMs（120 s / 90 s）内 `no_source`，元数据分别 15 min 16 s / 15 min 7 s 后才由后台补到（上一轮同种子 18.5 s）；tracker 间歇失败（失败累到 6 次，成功时也只报 8 个 peer），等待期 peer 主要靠 DHT。
2. **激活时元数据未到 → 界面没有反馈**：会话里留着的导入任务被 adopt 接管后仍是 `sleeping` / 0 字节，41 采 2 分钟内看不出「正在找源」。
3. **`downloadedBytes` 只算已校验片**：激活尾巴上它卡在 33.5 MiB 有 120 s 不动，磁盘同期 +3 MB；休眠时 33.5 MiB vs 磁盘 79.9 MB。判断「在不在动」够用，判断「下了多少」会低估。

## 证据（`artifacts/public-recheck/`）

- 采样：`samples-import.jsonl` / `samples-park.jsonl` / `samples-active.jsonl` / `samples-hibernate.jsonl` / `samples-active-2.jsonl` / `samples-active-adoptpath.jsonl`；断言与判据：`summary.json` / `table.md`。
- 请求与种子：`import.json` / `activate-2.json` / `group.json` / `groups-timeline.json` / `torrent-info.json` / `magnet.txt` / `probe-tracker.json` / `driver.mjs`。
- 日志：`daemon-data/daemon.log`（`pub_torrent_parked` 与 `pub_torrent_retired` 同一毫秒 19:21:47.813/.814，2.3 s 后 `public_session_stopped`）。
- 收尾：daemon 已 shutdown（pid 80512 退出、47200 无监听）；半成品实际占用 98 MB（稀疏，逻辑 21.4 GB），未达 300 MB 阈值，留在 daemon-data 作证据。

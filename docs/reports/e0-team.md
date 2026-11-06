# E0 · 组队分片值不值得做（基线实测）

- 版本：2026-11-06 · 执行层工程 agent · 对应 docs/design/team-acceleration.md §9（E0 装置与判定阈值）
- 结论一句话：**D = 1.1394 < 1.25 → 只做通告（0x53 队形 + 队友数），不做显式分片。**
- 证据：`artifacts/e0/evidence/e0-512m.log`（判定那一轮三个进程的全部日志，755 行，每行带 `run=20261106T114255-1907-26734`）；原始文件是 `artifacts/e0/log-S.log` / `log-C1.log` / `log-C2.log`（追加写，一个文件里有多轮）

## 1. 判定

| 项 | 值 |
|---|---|
| 判定行 | `team_metric D=1.1394 T_all=301.7s peers=2/2 uploaded=611713024 F=536870912 valid=true verdict=ANNOUNCE_ONLY` |
| D = S.payload_uploaded ÷ F | 611,713,024 ÷ 536,870,912 = **1.1394** |
| T_all | **301.7 s**（第一个下载者开始 → 最后一个下载者 100%） |
| 每成员 numPeers | C1 = **2**，C2 = **2**（做种者 + 队友）→ 不是「只连做种者」，本轮有效 |
| 结论 | D < 1.25 → **只做通告，不做分片**；M2 的排期还给通知三档与限速 UI |

判据来自派单与 docs/design/team-acceleration.md §9.4：D ≥ 1.25 说明 rarest-first 没去重、要重估分片；D < 1.25 则分片不划算。

## 2. 装置（三进程，`harness/e0-team.sh`）

| 角色 | 干什么 |
|---|---|
| S | 建群 → 共享 512 MiB（256 KiB × 2048 片）→ `setUploadLimitKbps(2048)`（2 MiB/s）→ 等两个下载者收工后读 `payload_uploaded` |
| C1 / C2 | 同时用后 8 位加入 → 收到同一条文件 → 同时 `startDownload` → 100% 时写 `markers/done-Cx.txt` |

- 载荷用「重复块」生成：一块 256 KiB 模板 + 每片在头 8 字节打片号，512 MiB 约 1 s 生成完，不用随机数（片间哈希不同，也不留成一份全零文件）。
- 两个下载者互连**不靠任何新代码**：`peer.announce` 已经在群内广播（S 转发），`connectSeeders` 本轮改成「每成员一次拨号」之后，两个下载者各拨对方一次。
- T_all 的时间基准来自两个下载者自己写的 `start_ms` / `done_ms`（同机墙钟）：`T_all = max(done) − min(start)`。

## 3. 结果

```
run=… M1 S  file_shared  file_id=25a716…c15937 name=payload-512m.bin size=536870912 piece_size=262144 upload_limit_kbps=2048
run=… M1 C1 download_start ok=true start_ms=1793936588050
run=… M1 C2 download_start ok=true start_ms=1793936588054
run=… M1 C1 download_done  bytes=536870912 peers_max=2 teammates_max=1 teammates_at_done=0 elapsed_ms=301620
run=… M1 C2 download_done  bytes=536870912 peers_max=2 teammates_max=1 teammates_at_done=0 elapsed_ms=301657
run=… M1 S  team_metric D=1.1394 T_all=301.7s peers=2/2 uploaded=611713024 F=536870912 valid=true verdict=ANNOUNCE_ONLY
```

旁证（说明「互助真的发生了」而不是两个下载者各拿一份）：

| 读数 | 值 | 含义 |
|---|---|---|
| S 的 `payload_uploaded` | 611,713,024 B ≈ 1.139 F | 做种者总共只发了 1.14 个文件的量 |
| C1 的 `payload_uploaded` | 191,807,488 B | 第一个下载者向第二个（以及少量协议量）上传了 183 MiB |
| C1 的 `payload_downloaded` | 536,985,600 B ≈ F | 下载者本身拿满了整个文件 |
| 两个 C 的 `teammates_max` | 1 | 队友数读口在真实传输中给出的值：对方是队友、做种者不算 |
| 两个 C 的 `teammates_at_done` | 0 | 到 100% 立刻退出组队（产品规格 §306 的口径） |

## 4. 为什么是 1.14 而不是 1.00（不改变结论）

design 文档 §2.1 给的推演上界是 `D − 1 ≈ (N−1)/N · 3C/F`：重复只可能发生在「在飞请求窗口」里，窗口 ≈ 3 秒的上行量。

- 本轮的 `3C/F = 2 × 3 / 512 ≈ 0.0117`，所以理论上 D ≈ 1.01–1.02。
- 实测 1.1394，比理论高 0.12，来源是首轮冷启动（bitfield 交换、握手、第一个请求波次）摊在一个 4.3 分钟的传输上，以及两个下载者几乎同时开始造成的首波重叠。

对照实验（同一装置，只把文件换成 16 MiB）：

```
team_metric D=1.4182 T_all=13.2s peers=2/2 uploaded=23793570 F=16777216 valid=true
```

16 MiB 时 `3C/F = 6/16 = 0.375`，实测 D 抬到 1.42 —— 与「D 的超出部分 ∝ 1/F」完全同向。也就是说 **D 随文件变小而变大**，512 MiB 的 1.14 里那 0.12 是固定开销，不是 picker 在重复喂同一批片。

判定仍按派单的阈值走（D < 1.25 → 不做分片）。要留意的是 design 文档 §9.4 还留了一档「1.10 ≤ D < 1.25 → 有条件做（仅当 N ≥ 3 且 C ≤ L/4）」，本次只跑了一轮基线，不满足「3 轮中位数 + 区间不重叠」的统计口径，**不足以把 1.14 与 1.00 区分开**；把它当「没有证据支持分片」用是安全的，当「分片一定没收益」用则不够。见 §6。

## 5. 本轮一并修的两个阻塞缺陷（E0 的前置条件）

E0 之所以能跑出 `peers=2/2`，是因为先修了这两条；否则一个下载者只会连做种者，D 会直接是 2（且与 picker 无关，测不出任何东西）。

**① `connectSeeders` 的「有 peer 就整轮不拨」**

- 现场：`GroupRuntime.kt` 里 `hasPeers = bt.peers(fileId) > 0`，有 1 条 peer 连接就跳过所有成员。
- 修法：按「每成员一次拨号」（`MemberDialPolicy`）+ 幂等去重（已互连的成员不重拨）+ 复用冷启动重连的退避表与并发预算（`ReconnectPolicy`：1/2/4/8/16/30 s、每轮 3 个）。
- 证据：C1/C2 的 `peers_max=2`，C1 的 `payload_uploaded=191,807,488`。

**② 两端同时拨号的 duplicate_link 竞态**

- 现场：`artifacts/m1-device/reconnect-race-evidence.txt`（手机每 30 s 拨一次、每次 EOF、永不 attach）。
- 修法：裁决前把已关闭的旧链路当不存在（僵尸不再占位）；自己这条被 duplicate 关掉而同一成员还有活链路时，直接沿用那条并补 `peer.announce` + `sync.req`（`reconnect_link_race_settled`），不进退避。
- 断言（`harness/reconnect-race.sh`）：两端带本地库同时起来、同一毫秒各掐一次链路，让**生产重连器**各拨一条；判据 ① 10 s 内双方 `race_attached ok=true`；② 撞车收尾后 60 s 内 `link_closed_tail=0` `offline_tail=0`；③ `race_settled ≥ 1`。
  - 正常臂：`race_attached ok=true elapsed_ms=244/258`、`race_stable ok=true link_closed_burst=10 link_closed_tail=0 offline_tail=0 race_settled=1/2` → **PASS**
  - 反向臂 `--mutant no_link_fallback`：`race_settled=0` → 断言 FAIL，脚本报 `PASS (mutant … 按预期让断言 FAIL)`。

## 6. 风险与未决

1. **只跑了一轮，没有 3 轮中位数。** 512 MiB × 2 MiB/s 一轮 5 分钟，3 轮 + 交替执行约 20 分钟，本轮按派单要求只跑基线一轮。D=1.1394 处在 design 文档「有条件做」区间（1.10–1.25）的上沿之外不到 0.02，**多轮重复才有统计意义**；若 L1 要复核，建议至少再跑 2 轮取中位数。
2. **撞车断言的证伪边界。** loopback 上 TCP 全双工、EOF 必达，对称竞态本身能收敛，所以「永不 attach」这一症状要真机切断网络（半开连接）才复现。夹具钉的是「兜底必须走到 + 撞完不抖」，用 mutant 臂证明有证伪力；真机上的半开连接路径仍未自动化覆盖，建议 M2 起用真机抓一次 WiFi 断开重连的日志。
3. **装置开销。** 一轮 512 MiB 在磁盘上占 1.5 GiB（S 的载荷 + 两个下载各一份），跑完留在 `artifacts/e0/`，下一轮开始时清掉（所以目录里现在留的是最后一次 16 MiB 冒烟轮的文件）。判定轮的**日志**不受影响：`artifacts/e0/evidence/e0-512m.log` 是那一轮的原样摘录。
4. **队友数的口径边界**（见 docs/core.md §3.4 与 §9）：只在「部分」计数；`peer_info` 没有成员身份，同机多成员（`127.0.0.1` 多端口）理论上可能配错人，局域网一台设备一个 IP 的常态下没问题。

## 7. 复现

```bash
bash scripts/m1-headless.sh          # 回归：35 条 CHECK 全 PASS（自检 140 条）
bash harness/e0-team.sh              # E0：约 5.5 分钟，打印 team_metric 一行，末行 PASS/FAIL
bash harness/reconnect-race.sh       # 撞车断言：正常臂必须 PASS（约 1.5 分钟）
bash harness/reconnect-race.sh --mutant no_link_fallback   # 反向臂：必须 FAIL（脚本会自证）
```

三个脚本都是**装置**，不进 `scripts/`（那个目录一人一文件，本轮不动）。

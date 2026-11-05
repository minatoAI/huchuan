# M1 验收装置加固（执行记录）

**对象**：`scripts/m1-headless.sh`、`harness/src/main/java/app/huchuan/harness/` 下 `M1Harness.java` / `Probe.java`（新增）/ `RunLog.java`（新增）/ `SelfTest.java`
**依据**：`docs/reviews/m1-harness-audit.md`（结论「有条件可信」，两条高危）
**日期**：2026-11-06 ｜ 未改 `core/`

## 0. 结论

审计点名的三条绕过路径现在都会被**对应**断言抓住，不是「碰巧失败」：

| 审计发现 | 旧装置 | 现在 |
| --- | --- | --- |
| §1 SHA-256 只证明两串相等 | 两进程都打印 `0` 也过 | 脚本自己用 sha256sum 重算两端文件，且要求 64 位 hex |
| §2 dht_nodes 检不出真失败模式 | 删 setEnableDht(false) 仍全绿 | 三重断言（dht_running / enable_dht / 采样≥3 全 0），变异实验证明会红 |
| §3 无法证明 B 的字节来自 A | 完全没有传输方向证据 | peer 数 + libtorrent 字节计数 + A 侧上传字节 + mtime + 清理校验 |
| §4 日志可重放 | 换代码不重跑也 PASS | 每轮 runId 写进每一行，断言只认本轮；日志追加不删旧证据 |
| §5 漏洞 A mDNS 永不 FAIL | WARN 不进 FAILS | 硬断言 + 显式打印 DEGRADED |
| §5 漏洞 B 延迟无下界 | 负值也 PASS | `0 < latency_ms <= 3000` |
| §5 漏洞 C A 先打 RESULT=PASS 再 exit 1 | 日志自相矛盾 | RESULT=PASS 只在成功路径最后打 |
| §6 端口只靠扫描兜底 | 无断言 | A/B 实际 msg_port 不等且 A 的等于 B 拨的端口 |
| 附录 selftest 不设 check 数下限 | 删光 check 也 PASS | 要求 checks >= 40 且本轮 rc=0 |

CHECK 条数 14 → 25，每条都有明确证伪条件（§2）。
样例：`bash scripts/m1-headless.sh` 连跑五次全绿，最后一次 `run=20261106T050400-2659-20024`，最后一行 `PASS`，rc 0（见 §7）。

## 1. 逐条改法

### 1.1 dht 三重断言（审计 §2，高）

旧断言只数 `dht_nodes=0`；manager 为空、DHT 开着但没有 bootstrap 路由器，两种情形都返回 0，所以检不出它声称要检的东西。

- **① 两进程都必须自报 `dht_running=false`**：harness 每进程起采样线程，每 750ms 直读 libtorrent 会话打一条
  `M1 <role> dht_sample seq=N dht_nodes=0 dht_running=false enable_dht=false`；开跑时另有 `bt_probe` 一条。
- **② settings_pack 的 enable_dht 必须是 false**：`Probe` 读 `SessionManager.settings().isEnableDht()`（libtorrent 活值），
  不是 core 的自报日志；两进程各一条 `bt_probe ... enable_dht=false`。
- **③ 每进程采样 ≥ 3 条且全为 0**：脚本按角色分别数，且坏样本数必须为 0（坏 = 不是
  ` dht_nodes=0 dht_running=false enable_dht=false` 结尾，含 `source=unavailable`）。

变异实验（§3.3）证明：打开 enable_dht 后 `dht_nodes` 仍然全是 0（6/6、7/7），旧断言照样 PASS；新断言靠 ①② 抓住。

### 1.2 provenance：B 的 32 MiB 必须能从 A 侧解释（审计 §3，高）

- **① 传输过程中 B 的 peer 数 > 0**：下载循环每 250ms 调 `GroupRuntime.peerCount(fileId)`（即 `BtSession.peers()`，
  core 已有，本装置把它接出来），打 `M1 B transfer_peers max=1 samples=9 positive=5 first_peer_ms=754 elapsed_ms=2010`。
- **② B 的下载字节数来自 libtorrent，不是文件大小**：`Probe.torrentLine()` 读 `TorrentStatus.totalPayloadDownload()` /
  `totalDone()`，打 `M1 B transfer_stats source=libtorrent ... payload_downloaded=33554432 total_done=33554432 ...`。
- **③ 清理必须校验 + mtime**：`clean_work()` 对 a-work / a-data / b-work / b-data 逐个 rm -rf，检查返回码、检查目录真的没了、
  检查 b-data/downloads 不残留，任一不满足直接 `die`（脚本 FAIL）。B 的下载文件 `stat -c %Y` 必须 > 本轮 `RUN_T0`。
- **额外（发送端）**：A 侧 `payload_uploaded >= 33554432`（同样来自 libtorrent 计数）。三处独立证据任一缺失都 FAIL。

### 1.3 runId：日志可重放（审计 §4，中）

- 脚本每轮生成 `RUN_ID="$(date +%Y%m%dT%H%M%S)-$$-$RANDOM"`，经 `-Dm1.runid` 传给两个进程。
- `RunLog` 用行首前缀流把 `run=<runId> ` 写到**每一行**（异常栈的每一行也带）。断言一律用 `^run=$RUN_ID ` 锚定。
- 日志改成**追加写**：旧轮的行物理上留在同一个文件里，但过不了锚定 grep。开跑前还会检查本轮 runId 是否已在日志里出现过
  （撞车 → FATAL），避免人为构造重放。

### 1.4 mDNS 硬断言（审计 §5 漏洞 A，中）

- `mdns.discovered_lan_candidate (lan>=1)` 现在是硬断言：不满足计 FAIL，并打印
  `DEGRADED: mDNS 局域网发现拿不到候选（协议 §3.1 主路径）。本轮判 FAIL，不允许静默降级到手动 IP:port。`
- 另加 `mdns.a_started (mdns=on)` 与 `flow.same_group (A.gid == B.gid)`（证明 B 加进的是 A 那个群）。
- **`join_via` 只打印不断言**：同机若还有别的 M1 实例在广播（本轮就撞上了——47100 被另一个进程占着，B 先连上别人的组，
  `peer_err ... code=E_JOIN_CODE msg=invite_code_mismatch`，再回落到手动地址），`join_via` 会变成 manual。
  那是同机并发噪声，不是 mDNS 退化；硬断言的落点是「发现到了候选」+「进的是 A 的群」。

### 1.5 latency 下界（审计 §5 漏洞 B，中）

`0 < latency_ms <= 3000`。负值（时钟偏移 / 重放旧 op）现在直接 FAIL。

### 1.6 A 不再无条件打印 RESULT=PASS（审计 §5 漏洞 C，中）

先算 `inStore`，不成立走 `fail("A","comment_not_in_store")`（只打 RESULT=FAIL + exit 1）；
`RESULT=PASS` 只在 flow_done / kernel.close 之后打。

### 1.7 两进程 msg_port 不等（审计 §6，低）

断言 A、B 的实际 msg_port 都 > 0、互不相等，且 A 的实际值等于 B 实际拨的端口。

### 1.8 顺手补的两个（同属审计）

- `sha256.independent_recompute`：脚本用 `sha256sum` 重算 A 的载荷与 B 的下载文件，两个都要等于进程打印的值。
  审计 §1 的绕过（把 harness 的 sha256() 改成 return "0"）现在连 64 位 hex 都过不了。
- `selftest.byte_layer` 增加 check 数下限 40（当前 51），并只认本轮 runId 的 RESULT=PASS。

## 2. 断言清单（25 条，全部可证伪）

| # | 断言 | 一票否决条件 |
| --- | --- | --- |
| 1 | flow.A_invite_code_16_chars | 长度≠16 或不在 Crockford 字母表 |
| 2 | flow.B_joined_with_tail8_only | B 的 tail8 ≠ A 邀请码后 8 位 |
| 3 | flow.B_joined | 本轮无 join.ok |
| 4 | flow.same_group | A.gid ≠ B.gid |
| 5 | flow.A_shared_32MiB | 本轮无 size=33554432 的 file_shared |
| 6 | flow.B_saw_file_32MiB | 本轮无 size=33554432 的 file_seen |
| 7 | flow.B_downloaded_32MiB | 本轮无 bytes=33554432 的 download_done |
| 8 | sha256.equal | 任一非 64 位 hex，或两者不等 |
| 9 | sha256.independent_recompute | 脚本重算值与进程打印值不一致 / 文件不存在 |
| 10 | comment.same_op | B 的 op_id ≠ A 收到的 op_id |
| 11 | comment.latency_0_lt_x_le_3s | latency ≤ 0 或 > 3000 |
| 12 | comment.A_in_store | 本轮无 op_in_store=true |
| 13 | process.rc_0_0 | A 或 B 的退出码非 0 |
| 14 | selftest.byte_layer | rc≠0 / checks<40 / 本轮无 RESULT=PASS |
| 15 | process.msg_port_differs | A、B msg_port 相等或缺失，或 ≠ B 拨的端口 |
| 16-17 | dht.a / dht.b .enable_dht_false_and_running_false | bt_probe 不是 libtorrent 来源，或 enable_dht / dht_running 任一为 true |
| 18-19 | dht.a / dht.b .samples_ge3_all_zero | 本轮该角色样本 < 3，或存在非「全 0 + false」的样本 |
| 20 | provenance.b_peers_during_transfer | max=0 或 positive=0 |
| 21 | provenance.b_downloaded_bytes_from_libtorrent | payload_downloaded < 33554432 或 total_done ≠ 33554432 |
| 22 | provenance.a_payload_uploaded_from_libtorrent | payload_uploaded < 33554432 |
| 23 | provenance.download_mtime_after_run_start | 文件不存在，或 mtime ≤ RUN_T0 |
| 24 | mdns.a_started | kernel_started 里 mdns≠on |
| 25 | mdns.discovered_lan_candidate | lan=0（同时打印 DEGRADED，最终 rc=1） |

## 3. 反向测试（4 个变异，全部实跑，留原始输出）

注入方式：`M1_MUTANT=<名称>` → 脚本拼 `-Dm1.mutant=<名称>` 传给 harness（`-Dm1.mutant.copySrc` 用于 precopy 的源路径）。
变异点默认关闭，正常跑完全不受影响。`M1_WAIT_MS` 只为加速失败路径（缩短等待上限，只会让失败更早发生）。

### 3.1 关掉 A 的做种（M1_MUTANT=no_seed，run 20261106T044817-5120-1741）

A 共享完之后立刻 `group.stopFile(fileId)`，B 能看到清单但拿不到数据。期望：B 下载失败 + provenance 全红。

```
run=20261106T044817-5120-1741 M1 A mut_no_seed stopped=true file_id=034388ead9fbfb4283fe4fbeb69a0f9ab013a906
run=20261106T044817-5120-1741 M1 B transfer_peers max=0 samples=100 positive=0 first_peer_ms=-1 elapsed_ms=25109
run=20261106T044817-5120-1741 M1 B FAIL reason=download_timeout
CHECK FAIL flow.B_downloaded_32MiB (n=0)
CHECK FAIL provenance.b_peers_during_transfer (max=0 positive=0)
CHECK FAIL provenance.b_downloaded_bytes_from_libtorrent (payload_downloaded=0 total_done=0)
CHECK FAIL provenance.a_payload_uploaded_from_libtorrent (payload_uploaded=none)
FAIL (11 checks failed)  run=20261106T044817-5120-1741
```

抓住它的断言：`flow.B_downloaded_32MiB`、`provenance.b_peers_during_transfer`、
`provenance.b_downloaded_bytes_from_libtorrent`、`provenance.a_payload_uploaded_from_libtorrent`、`process.rc_0_0` 等 11 条。

### 3.2 B 直接把文件拷进 downloads（M1_MUTANT=precopy，run 20261106T044909-5663-25589）

B 在 `startDownload` 之前把 A 的载荷 `Files.copy` 到 `b-data/downloads/payload-32m.bin`（模拟「文件本来就在盘上」，
也等价于审计 §3 的「上一轮句柄没释放 / 硬链接」）。这是最关键的一次：**sha 与文件大小全对，字节一个没传**。

```
run=20261106T044909-5663-25589 M1 B mut_precopy src=E:/agentHome/bt-share-app/artifacts/m1/a-work/share/payload-32m.bin dst=...\b-data\downloads\payload-32m.bin bytes=33554432
run=20261106T044909-5663-25589 M1 B transfer_peers max=0 samples=3 positive=0 first_peer_ms=-1 elapsed_ms=513
run=20261106T044909-5663-25589 M1 B transfer_stats source=libtorrent ... total_done=33554432 payload_downloaded=0 payload_uploaded=0 total_download=594 total_upload=624 ...
CHECK PASS flow.B_downloaded_32MiB (n=1)
CHECK PASS sha256.equal (ad5cfbff91a0c299b43bfbfda003e066c7a8e2bd4984c7b8913f729f78d65dad)
CHECK PASS sha256.independent_recompute (B_file=ad5cfbff... A_share=ad5cfbff...)
CHECK FAIL provenance.b_peers_during_transfer (max=0 positive=0)
CHECK FAIL provenance.b_downloaded_bytes_from_libtorrent (payload_downloaded=0 total_done=33554432)
CHECK FAIL provenance.a_payload_uploaded_from_libtorrent (payload_uploaded=0)
FAIL (3 checks failed)  run=20261106T044909-5663-25589
```

注意 `sha256.*` 与 `flow.B_downloaded_32MiB` **全部 PASS** —— 这正是旧装置（只有这几条）会报 PASS 的场景，
新装置由 provenance 三条把它钉死。顺带说明 `provenance.download_mtime_after_run_start` 在这次里是 PASS：
mtime 只能抓「上一轮残留的旧文件」，抓不到「本轮临时拷贝的文件」；后者必须靠 libtorrent 字节计数。两条断言分工明确。

### 3.3 打开 DHT（M1_MUTANT=dht_on，run 20261106T044938-6137-17525）

harness 直接对 libtorrent 会话 `applySettings(enable_dht=true)` + `startDht()`（不改 core 源码），
等价于审计 §2 的绕过形态：DHT 开着，但没有 bootstrap 路由器，dht_nodes 仍恒为 0。

```
run=20261106T044938-6137-17525 M1 A bt_probe source=libtorrent_session enable_dht=true enable_lsd=false enable_upnp=false enable_natpmp=false dht_running=true dht_nodes=0
run=20261106T044938-6137-17525 M1 B bt_probe source=libtorrent_session enable_dht=true enable_lsd=false enable_upnp=false enable_natpmp=false dht_running=true dht_nodes=0
（A 的 dht_sample 6 条、B 的 7 条，dht_nodes 全部 = 0）
CHECK FAIL dht.a.enable_dht_false_and_running_false (got ... enable_dht=true ... dht_running=true dht_nodes=0)
CHECK FAIL dht.b.enable_dht_false_and_running_false (got ... enable_dht=true ... dht_running=true dht_nodes=0)
CHECK FAIL dht.a.samples_ge3_all_zero (n=6 bad=6, need n>=3)
CHECK FAIL dht.b.samples_ge3_all_zero (n=7 bad=7, need n>=3)
FAIL (4 checks failed)  run=20261106T044938-6137-17525
```

关键点：`dht_nodes` 全程为 0。旧断言（只数 dht_nodes=0）在这个变异下必定 PASS，新断言靠 ① dht_running 与 ② enable_dht 抓住。

### 3.4 复用上一轮日志（M1_MUTANT=replay，run 20261106T045006-6571-10490）

harness 本轮只打一行 `run_begin` 就退出（等价于「进程早退，磁盘上只剩上一轮的绿色证据」）。
此前 7 轮的行都还留在同一个 `log-A.log` 里（含 20261106T044938-6137-17525 那轮，A 侧绿色证据齐全）。

```
  本轮 runId 在 log-A 里的行数     : 2
  文件里不带本轮标记的旧证据行数   : 7  ← 旧轮残留，本轮不得采信
B         : skipped（A 没产出 invite_tail8 / msg_port，本轮 B 不可能拿到正确证据）
CHECK FAIL flow.A_invite_code_16_chars (got )
CHECK FAIL flow.B_downloaded_32MiB (n=0)
...
FAIL (24 checks failed)  run=20261106T045006-6571-10490
```

同一份日志、同一段模式，两种 grep 结论相反（这就是 runId 锚定的意义）：

```
grep -c "^run=20261106T044938-6137-17525 M1 B download_done " artifacts/m1/log-B.log   # → 1（旧轮，朴素断言会采信）
grep -c "^run=20261106T045006-6571-10490 M1 B download_done " artifacts/m1/log-B.log   # → 0（本轮，锚定断言不采信）
```

唯一仍 PASS 的是 `selftest.byte_layer`：selftest 是本轮真跑的，不是重放。

### 3.5 清理校验实测（针对审计 §3 的「rm -rf 失败却不报错」）

不是变异，是装置的负向自检：让 PowerShell 先打开 `a-work` 下的一个文件（Windows 上等价于「上一轮 java 还占着句柄」），
再执行脚本里的那条 rm -rf：

```
$ rm -rf artifacts/m1/testlock; echo rc=$?
rm: cannot remove artifacts/m1/testlock/hold.bin: Device or resource busy
rm rc=1
GUARD WOULD FIRE: still exists
$ (释放句柄后) rm -rf artifacts/m1/testlock; echo rc=$?   → 0
```

即 `clean_work` 的两个分支（返回码 ≠ 0 / 目录仍在）在真机上都会触发 → `die` → 整轮 FAIL，
不会出现「清理失败但 B 复用了上一轮文件、仍然 PASS」的情形。

## 4. 待 core 暴露的信号（本轮没动 core/）

harness 用反射读 libtorrent 真值（`harness/.../Probe.java`），耦合点是
`M1Kernel.bt` / `BtSession.manager` / `BtSession.tasks` / `BtSession.lock` / `Task.handle`。
反射失败不会静默降级：会打 `bt_probe source=unavailable error=...`，脚本判 FAIL。
建议 core 补三个公开读口，补上后 `Probe` 可以整体删掉，断言不用动：

1. `GroupRuntime.transferredBytes(fileId)` → `totalDone` / `totalPayloadDownload` / `totalPayloadUpload`（libtorrent 计数）
2. `M1Kernel.sessionSettings()` → `enable_dht` / `enable_lsd` / `enable_upnp` / `enable_natpmp` / `dht_running`（会话活值）
3. `GroupRuntime.peerCount(fileId)` 已有（`BtSession.peers()`），已直接用，不需要改

## 5. 已知限制

1. **同机并发**：两个实例会互相删 a-work / b-data、互相占端口。脚本加了 `artifacts/m1/.runlock`（mkdir 原子锁 + pid 存活检查 +
   陈旧锁自动清理），第二个实例直接 FATAL。但**别的实现**（不是本脚本）在同机跑 M1 内核时仍会干扰端口与 mDNS（本轮实测过），
   所以 `join_via` 只打印不断言。
2. **日志追加**：log-A / log-B / selftest.log 只追加不截断，文件会随轮次增长。这是「旧证据留着也过不了断言」的代价。
3. **mtime 的边界**：只覆盖「上一轮残留文件」，不覆盖「本轮临时拷进来的文件」（§3.2 已说明，由 libtorrent 计数兜底）。
4. **反射耦合**：core 若改私有字段名，`bt_probe` 会变成 `source=unavailable` 并让整轮 FAIL —— 这是有意的（宁可红，不要静默弱化）。
5. **B 没有单独的 kill 兜底**：脚本只 kill A；B 靠自身等待上限（`M1_WAIT_MS`）退出，`wait $PID_B` 会等它。等待上限调到很大时，异常卡死的 B 会让整轮变慢。

## 6. 复现

```bash
bash scripts/m1-headless.sh                      # 期望最后一行 PASS，rc 0
M1_MUTANT=no_seed  bash scripts/m1-headless.sh   # 期望 FAIL：11 条（B 下载失败 / provenance 全红）
M1_MUTANT=precopy  bash scripts/m1-headless.sh   # 期望 FAIL：3 条 provenance（sha / 文件大小仍 PASS）
M1_MUTANT=dht_on   bash scripts/m1-headless.sh   # 期望 FAIL：4 条 dht
M1_MUTANT=replay   bash scripts/m1-headless.sh   # 期望 FAIL：24 条（只有 selftest PASS）

M1_WAIT_MS=25000 M1_MUTANT=no_seed bash scripts/m1-headless.sh   # 同上，只是失败更快
```

每轮 runId 记在 `artifacts/m1/last-runid.txt`；按轮取证：

```bash
R=$(cat artifacts/m1/last-runid.txt)
grep "^run=$R " artifacts/m1/log-A.log artifacts/m1/log-B.log artifacts/m1/selftest.log
```

## 7. 本轮全绿记录

| runId | 结果 |
| --- | --- |
| 20261106T044737-4600-25856 | PASS（25/25） |
| 20261106T045105-7120-5056 | PASS（25/25） |
| 20261106T045207-7798-19471 | PASS（25/25） |
| 20261106T045844-11363-10124 | PASS（25/25），core/m1 在 04:53 被另一个 agent 改过之后复跑 |
| 20261106T050400-2659-20024 | PASS（25/25），最终确认（另有一个并发实例在 05:02 跑过同脚本，运行锁生效）|

关键真值（最后一次）：

```
dht.a.samples_ge3_all_zero (n=6 bad=0)
dht.b.samples_ge3_all_zero (n=7 bad=0)
provenance.b_peers_during_transfer (max=1 positive=5)
provenance.b_downloaded_bytes_from_libtorrent (payload_downloaded=33554432 total_done=33554432)
provenance.a_payload_uploaded_from_libtorrent (payload_uploaded=33554432)
provenance.download_mtime_after_run_start (mtime=1793912650 run_t0=1793912646)
```


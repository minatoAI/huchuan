# 组队通告「队友 ≥ 1」真机验证（M2）

补 `docs/m1-device.md` §6-4 的缺口：**0 态**（不显示）早有真机断言，**n ≥ 1 的展示**一直没有证据。本轮补上，四条断言全绿。

| 项 | 值 |
| --- | --- |
| 命令 | `bash scripts/m2-team-verify.sh`（末行 `PASS`，退出码 0；本轮 9/9 通过，约 4 分钟） |
| 设备 | Mi MIX 2S `5b94673a`（Android 13 / API 33）；OPPO 未碰 |
| 场景 | S（桌面 JVM，做种 `team-64m.bin`，单文件上传 512 KB/s）→ B（桌面 JVM，加入同群下载同一文件）→ 手机（加入并下载） |
| 素材 | `team-64m.bin` 67108864 B，sha256 `b52945db9089b30b…`，file_id `b7da55211472da2f…` |
| 证据 | `artifacts/team-verify/` |
| 前置 | **端口 47200 / 51513 / 47210 / 51523 必须空闲**（S、B 两个对端的消息端口与 BT 端口）；被占用时脚本直接 die，不会去杀别人的进程 |

## 断言与结果

| # | 断言 | 现场读数 | 结果 |
| --- | --- | --- | --- |
| 1 | `team.p3_chip_2` | P3（XML 读数 41%，下载中）：chip「组队 2 人」 | PASS |
| 2 | `team.p4_line_2` | P4（XML 读数 31%，↓534 KB/s）：一行「正在组队 · 2 人」 | PASS |
| 3 | `team.cleared_at_100` | 100% 后 P4 无「正在组队」（做种中 / 做种者 2）、P3 无「组队 n 人」 | PASS |
| 4 | `team.teammates_logged` | `event=teammates_changed … count=1 peers=2` | PASS |

另外两条支撑断言（一起判 PASS）：

- `team.drop_caused_by_completion`：全程 `teammates_changed count=0` 条数 = 0，B 在手机完成时仍未下完（`b_completed_lines=0`）；
- `team.teammate_alive_at_last_tick`：完成前最后一条 `download_progress`（99%）里 `teammates=1` —— 队友陪到了 100%，消失不是「队友先跑光」。

队伍人数 2 = 队友 1 + 本机：S 是做种者不计入，B 是唯一处于「部分」的成员，与 spec §306 / `GroupRuntime.teammates()` 的口径一致。

## 证据

```
artifacts/team-verify/
  screenshots/5b94673a/  20261106-180422-469-p4-team-2.png   「正在组队 · 2 人」
                         20261106-180436-978-p3-team-2.png   「组队 2 人」
                         20261106-180606-048-p4-team-gone.png 100%、无组队行
                         20261106-180619-378-p3-team-gone.png 做种中、无 chip
  ui-dumps/5b94673a/     同上四张 + p3/p4 的 0 态 XML（断言取的是文本节点）
  logs/run.log           脚本完整输出（含 PASS）
  logs/app-logcat.log    只含 huchuan/m1 事件
  logs/desktop-S.log · desktop-B.log   两端 PEER 日志
  logs/summary.txt       本轮关键读数
```

截图与 ui-dump 的文件名带时间戳，正是「下载中」的那几分钟，不是事后补拍。

## 怎么稳定跑出 n=1

S 上传限速 512 KB/s → 下载窗口几分钟；手机在 P4 设单文件**上行**限速 64 KB/s，让 B 追不平手机的点位、手机先到 100%；B 在手机进度 ≥ 25% 才启动。三项都可用 `M2_SEED_KBPS` / `M2_PHONE_UP_KBPS` / `M2_B_START_PCT` 调。

## 已知问题 / 复现注意

1. **第一轮是假阴性**：群名用的是「组队验收群」，P3 的「无组队」断言被群名污染。已把默认群名改成「验收群」，并把断言收紧成 chip 正则 `组队 n 人`。
2. 输入上行限速会弹出软键盘挡住下半屏：脚本在 P3 快照前发 ESC 收键盘，留证截图现在是干净的一屏。
3. `scripts/m1-device.sh` 的 `team.no_teammate_events` 原是恒真断言（grep `teammates_changed count=` 匹配不到中间夹着字段的日志行）—— 2026-11-06 已修成「先锚 `event=teammates_changed` 再取 count」，并用本报告这份 logcat（1 条）与 m1 的 logcat（0 条）对拍证明它能 FAIL。
4. 脚本只清命令行含 `team-verify` 的 java 进程，不碰别人的对端（端口占用见上表「前置」）。
5. 边界：写了 `scripts/m2-team-verify.sh`、`scripts/m1-device.sh`（仅 §12 那条断言）、本文件、`docs/m1-device.md`（§4 表与 §6-4）、`artifacts/team-verify/`；`app/`、`core/` 未改。

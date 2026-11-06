# tracker 接线验收（`--phase all`）

- 日期：2026-11-06 18:37–18:41（Asia/Shanghai）
- 设备：OPPO 3B1F5HEADK1TXSF0（PKJ110，API 37）；Mi MIX 2S 全程未使用
- 命令：`bash scripts/verify-tracker-wiring.sh --phase all`（a→b 连续跑，不是单独跑 b）
- 耗时：3 分 40 秒（构建命中缓存；脚本注释的 10–15 分钟是冷构建估计）

## 结论

**PASS**：47 项断言全过、0 FAIL，脚本末行 `PASS`（`CHECKS_FAIL=0` 分支即 exit 0）。两段式状态依赖成立：a 从卸载重装走到「建群 + 开开关 + 注册尝试」，b 接着关开关、冷启动。

起点是脏数据（残留 `tracker_discovery=false`），靠 phase a 的 `adb uninstall` + 全新安装复位，符合脚本前置要求，不需额外清数据。

## 每阶段计数

| 组 | 断言数 | PASS | FAIL |
| --- | --- | --- | --- |
| env（SDK 37） | 1 | 1 | 0 |
| src（源码静态断言） | 4 | 4 | 0 |
| a 开启态 | 22 | 22 | 0 |
| b 关闭态 | 15 | 15 | 0 |
| c 回归（P0 门 + mDNS） | 5 | 5 | 0 |
| 合计 | 47 | 47 | 0 |

## 失败项与归因

- 本次运行：无 FAIL。
- 对照旧证据 `artifacts/tracker-wiring/prev-run-1832/summary.txt`（我接手前**单独跑 b** 留下）：21 PASS / 2 FAIL，`b.restart_hint` 与 `c.b_relink`。判为脚本状态依赖，不是产品缺陷：
  - `c.b_relink`：没跑过 a，设备上没有已加入的群，重启后无处重连，`role=` 为空；
  - `b.restart_hint`：那次开关本来就是 off，设置与内核一致，本不该出现「下次启动生效」。
- 本次两条均 PASS：`c.b_relink` = `event=peer_bound dev=ZP9AWGFAY8YGT0VH role=out`；`b.restart_hint` 在点关后 15 s 内命中。归因成立。

## 关键证据

- 全量 stdout：`artifacts/tracker-wiring/logs/verify-all-1838.log`
- 断言表：`artifacts/tracker-wiring/summary.txt` 与 `summary-all.txt`（47 行）
- 设备日志流：`logs/stream-3B1F5HEADK1TXSF0-a.log`、`-b-toggle.log`、`-b.log`
- 桌面对端：`logs/peer.log`，`PEER download_done bytes=8388608 sha256=6816713c…` 与 Android 侧同值
- 截图：`artifacts/tracker-wiring/shots/`（本次 9 张；目录内另有上次 4 张）
- 注册尝试原行：`event=tracker_register_failed … 66.220.146.94_(port_443) … after_4000ms`（本机 DNS 被污染，断言只要求有尝试）
- 关闭态原行：`event=tracker_config enabled=off base=-`，整轮 tracker 网络事件 0 条

## P7 文案一致性（只记录，未改代码）

- 规格 `docs/design/product-spec.md:227` 与文案表 `:240` 写「**重启后生效**」。
- Android 实机是「**下次启动生效**」：`app/src/main/res/values/strings.xml:115`（`p7_tracker_restart`），见截图 `shots/20261106-184047-044-b-p7-after-off.png`。
- 实现报告 `docs/reports/tracker-wiring.md:26` 与代码同口径，规格这处没同步。取哪边由 L0 定，本会话不动。

## 风险与未决

- 跑前设备上已有 11 个孤儿 `adb logcat`（非本次产生，跑完仍 11 个）；断言只读本次日志流，不影响结论。
- 脚本会 `adb uninstall` 该设备上的 `app.huchuan`，依赖其已登录/已入群状态的手工验证都要重来。
- 边界：只写本报告与 `artifacts/tracker-wiring/`；`app/`、`core/`、`scripts/` 未改（`git status` 里的改动是其它 agent 的既有工作，`scripts/summary.txt` 也非本次产生）。

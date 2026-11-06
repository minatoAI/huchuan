# 公开种子 UI（M3 · app 侧接线）

- 2026-11-06 · 执行层工程 agent · 依据 `docs/core.md` §3.6（内核能力）与 `docs/design/public-swarm.md`（pub2）
- 范围：只动 `app/`。**页面级新增 0**：P3 行内一枚 chip、P3「添加文件」一个弹层两项、P4 一行磁力摘要 + 一个「移除」。

## 1. 改了什么

| 位置 | 行为 | 文件 |
|---|---|---|
| P3 文件行 | 公开条目在状态 chip 左侧多一枚「公开」chip；元数据未到手时状态显示「等待来源」、不显示尺寸 | `ui/GroupScreen.kt`、`ui/Common.kt` |
| P3「添加文件」 | 同一按钮 → 弹层两项：**本机文件**（原 SAF 流程，行为不变）/ **磁力链接**（输入框 + 导入） | `ui/AddFileSheet.kt` |
| 磁力导入 | 全程 `Dispatchers.IO`；内核那句中文原样显示在输入框下一行，不弹窗、不重试 | `HuchuanApp.kt`、`kernel/M1Runtime.kt` |
| P4 | 公开条目多「公开」chip、一行磁力摘要（点一下复制整条）、顶栏「移除」；私有条目不变 | `ui/FileScreen.kt` |
| 投影 | `GroupFile` 补 `scope` / `magnet` / `pieceSize` 映射（此前恒为 PRIVATE） | `kernel/M1GroupStore.kt` |

## 2. 磁力导入的路径

`P3 添加文件 → 弹层 → 磁力链接 → 粘贴 → 导入` → `HuchuanState.importMagnet`（`withContext(IO)`）
→ `M1Runtime.importPublicMagnet` → `kernel.addPublicMagnet`（默认等元数据 60 s）。成功关弹层进 P4；
失败把内核的 `message` 写在输入框下一行：链接无法识别 / 这个种子已经在群里 / 找不到来源，先挂着等别人上线 /
等文件信息超时，会继续重试 / 文件信息无效。导入中按钮为「导入中」，弹层不许下滑关闭。

## 3. P4「移除」= 本地隐藏（design §5.2 的口径）

只从本机列表消失：不广播 file.del（M2 才有）、不删磁盘文件；本机正在跑的任务先 `stopFile`。
内核没有这个动作，标记落宿主：`AppPrefs.hideFile/isFileHidden`（SharedPreferences），`M1GroupStore.toGroup` 投影时跳过（**未做** §5.2 的 infohash 黑名单）。

## 4. 证据（MIX 2S / 5b94673a / Android 13）

`bash artifacts/public-ui/verify-public-ui.sh <群名>`（安装 → 启动 → 进群 → 导入 → 断言 → 截图 → 取日志）
- 非法磁力 → 「链接无法识别」，无弹窗：`CHECK invalid_link_message PASS`
- 同一合法磁力两次 → 第一次 60 s 后「找不到来源，先挂着等别人上线」并建条目，第二次「这个种子已经在群里」：`CHECK valid_magnet_message / duplicate_message PASS`
- P3 有「公开」chip + 「等待来源」；P4 有磁力摘要与「移除」：`CHECK p3_public_row / p4_magnet_and_remove PASS`
- 移除：21:10 单独复验 PASS（`event=ui_file_remove_local ... scope=public`，P3 该行消失）
- 私有分享不回归：走新弹层的「本机文件」→ SAF → P4「做种中 100% 做种者 1」（`event=share_import` + `event=ui_file_added`），截图 `13-private-share`
- 截图：`artifacts/public-ui/screenshots/5b94673a/`（01 弹层 … 13 私有分享）；日志 `artifacts/public-ui/logcat-public-ui.log`

## 5. 风险与未决

1. **公开会话报警线程崩溃（app 边界外，需内核 agent）**：导入磁力后约 2 分钟进程死在
   `FATAL EXCEPTION: SessionManager-alertsLoop`：`NullPointerException ... AlertType.ordinal()`
   （libtorrent4j `SessionManager$5.run:1176`，未映射的 alert 类型 → `AlertType` 为 null）。
   三轮复现（20:56 / 21:00 / 21:08），证据 `artifacts/public-ui/{crash-check,repro-crash}.log`，
   脚本 `artifacts/public-ui/repro-public-session-crash.sh`。内核侧可先试给公开会话设窄 `alert_mask`。
2. **`scripts/m1-device.sh` 第 7 步要补一步**：`tap "添加文件"` 之后弹层出现，需再 `tap "本机文件"`；
   本轮 `--quick` 就停在「SAF 选不到 m1-payload-32m.bin」。脚本在 `scripts/`，不在本任务边界内。
3. 私有**下载**本轮未复验：`m1-device.sh` 中止后桌面对端退出；下载路径（P4 激活 → `startDownload`）本轮无改动。
4. 文案：派单写「本机文件 / 磁力链接」，design §6.1 写「选择文件 / 导入磁力」；按派单实现，改回是一行 `strings.xml`。
5. 「移除」只影响本机，同群其他成员仍看得到（设计如此）；公开条目的「做种 n」仍是旧公式（`LocalSession` 没有 numSeeds 读口）。

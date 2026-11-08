# UI 可用性修复（2026-11-08）

## 结论

设计文档里的 5 条可用性问题全部落地，并在 Mi MIX 2S（5b94673a / Android 13）上逐条截图。
`./gradlew :app:assembleDebug` 通过（日志 `artifacts/ui-fix/logs/assembleDebug.log`）。

## 改动

| # | 改法 | 证据 |
| --- | --- | --- |
| 1 回复层级与取消 | 评论按根评论聚合两级：子回复缩进 16dp、左侧 2dp 竖线；深于两级的回复压平到第二级；串内 parent 在最上、回复按时间正序，串间按最近活动倒序。点评论设目标，输入框上方显示「回复 @作者 ×」；× 或返回键只取消目标，不退出 P4；无目标时返回键才走全局返回 | `screenshots/16-comment-order-parent-first-replies-ascending.png`、`02`、`03`、`04`、`15` |
| 2 长文件名 | P3 行内用实测宽度的中间省略，保留扩展名（`痴迷.The.Obsession.2025.216…-DL.x265.mkv`），整行 `fillMaxWidth().clickable`（行内空白处点击已验证）；P4 顶部完整名按实测宽度折两行，不插入不可见字符，语义 `text` 与真实文件名逐字一致 | `screenshots/01-p3-longname-middle-ellipsis.png`、`02-p4-title-two-lines-and-seeding.png`、`ui-dumps/p4-title.xml` |
| 3 限速开关 | P7 开关改「限制速度」：关 = 两项按 0 下发且输入框隐藏，开 = 显示上行/下行 KB/s；P4 同样改「限制速度」，关 = 0、开 = 显示 KB/s 输入框 | `screenshots/05-p4-limit-on.png`、`06-p7-limit-off.png`、`07-p7-limit-on.png` |
| 4 激活/休眠后果 | 开关下补一行当前后果，四种状态：正在做种 / 已休眠 / 正在下载 / 正在寻找来源；不加控件 | `screenshots/02`、`11`、`12`、`13` |
| 5 分享入口 | P9 顶栏加「新建分享」，弹层按群分组列出全部群文件；选一个走既有 P8 创建流程。P4「…」→「分享链接」保留 | `screenshots/08-p9-new-share-picker.png`、`09-p8-from-p9.png`、`10-p8-from-p4-menu.png`、`14-p4-menu-items.png` |

## 验证

- 构建：`./gradlew :app:assembleDebug` → `BUILD SUCCESSFUL`；`artifacts/ui-fix/logs/assembleDebug.log`。
- 真机：APK `install -r` 覆盖安装（保留数据）；P3/P4/P7/P9 截图与 uiautomator XML 均在 `artifacts/ui-fix/`。
- 四后果中的「正在下载」由 PC 端 `M1Harness A` 做种 32 MiB 文件、手机加入该群后激活下载复现（截图 12%、↓684.5 KB/s），不是静态摆拍。
- 可重跑检查：`bash artifacts/ui-fix/verify.sh`（构建 + 源码断言 + 截图数量）。

## 风险与未决

- P4 标题自绘两行（两个 Text + 外层单一语义节点），自动化读到的是完整原始文件名；视觉断行优先扩展名前分隔符，两行仍放不下时第二行末尾省略，但语义仍是完整文件名。
- P9 选择器列全部群文件，包括尚未下载完的公开条目；选到不可分享文件时由 P8 按既有失败文案拦下，本次不新增过滤。
- 本次只改 app/、docs/ui-fix.md、artifacts/ui-fix/；未改 core/、daemon/、windows/、pages/、worker/、tools/、scripts/、docs/design/、AGENTS.md、docs/STATE.md；P4「公开到群外」「移除」菜单未动。

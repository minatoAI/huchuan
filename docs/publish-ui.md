# 公开到群外（P4 接线）

- 2026-11-06 · 执行层工程 agent · 依据 `docs/core.md` §3.9 与 `docs/design/public-swarm.md` §6.1
- 范围：只动 `app/`，外加本文件与 `artifacts/publish-ui/`。**没改 core/ daemon/ windows/ tools/ scripts/。**

## 1. 改了什么

| 位置 | 行为 | 文件 |
|---|---|---|
| P4 顶栏 | 新增「…」菜单：私有条目一项「公开到群外」，公开条目一项「取消公开」；原有「移除」原样保留 | `ui/FileScreen.kt`、`res/values/strings.xml` |
| 发布执行 | `HuchuanState.publishPublic` → `withContext(Dispatchers.IO)` → `M1Runtime.publishPublic` → `kernel.publishPublic`；建种的全量哈希不占界面线程 | `HuchuanApp.kt`、`kernel/M1Runtime.kt` |
| 发布中 | 期间状态 chip 换成「发布中」（复用既有状态位，无新增按钮），菜单项置灰 | `ui/FileScreen.kt` |
| 失败 | 内核 `message` 中文原样贴在 P4 正文一行（error 色），不弹窗、不自动重试 | 同上 |
| 取消公开 | `kernel.unpublishPublic`：停种 + 本地隐藏；条目消失后 P4 自己退回 P3，私有条目不动 | `HuchuanApp.kt`、`kernel/M1Runtime.kt` |

## 2. 幂等

连点「公开到群外」：界面侧 `publishingIds` 挡住第二个任务；内核侧同一私有 fileId 直接回同一条公开条目
（`public_publish reused=true`），不再广播 file.add，P3 行数不变。

## 3. 证据（MIX 2S / 5b94673a / Android 13）

`bash artifacts/publish-ui/verify-publish-ui.sh`：push 8 MiB → 冷启动 → SAF 共享 → 发布 → 复制磁力 → 幂等 → 取消公开 → 休眠后发布看文案。末行 `PASS`，27 个 CHECK 全过：
- 发布：`public_publish … reused=false`、新 infohash ≠ 私有 id、`ui_p4_publishing state=on|off` 成对；
- P3：行数 1→2、`公开` chip 2→3；取消公开后回到 1 / 2，私有行仍在；
- 磁力：公开条目 P4 的 `btih:…` 点一下出「已复制」；
- 幂等：第二次 `reused=true`，行数与 chip 数都不变；
- 失败：休眠后发布显示「文件不在做种中」，`public_publish_rejected reason=not_seeding`，无弹窗、无 `FATAL`。

截图在 `artifacts/publish-ui/screenshots/5b94673a/`（`00-saf-picker` … `09-p4-publish-failed`）。
「发布中」那张是 `10-publishing.png`，用 32 MiB 素材拍（8 MiB 的窗口只有约 0.1 s），
复现命令 `bash artifacts/publish-ui/capture-publishing.sh`。

## 4. 风险与未决

1. 8 / 32 MiB 的「发布中」只闪 0.1–0.2 s，GB 级才明显。要人眼看得见得给状态位设最短显示时长，那是撒谎，本轮没做。
2. 新公开条目追加在 P3 列表末尾，条目多了要滚到底（列表本身没改）；验收脚本据此先滚到底再点数。
3. 同名文件重复共享时 `FileImporter` 会改名成 `xxx (2).bin`（另一条 infohash、另一行）；验收脚本先删私有目录里的同名副本规避。
4. `removed=true` 只代表本机移除成功；对端仍看得到这条公开条目（设计如此，file.del 在 M2）。
5. 只验了「本机发布 → 本机取消」；群外设备纯磁力下载本轮没测（core 侧 §9 也还欠着）。

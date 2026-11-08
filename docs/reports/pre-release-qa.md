# 发版前真机回归 · 0.1.0

- 设备 Mi MIX 2S `5b94673a`（API 33），2026-11-08 14:44–15:26；包 `dist/release/0.1.0/Huchuan-0.1.0-arm64-v8a.apk`（23,055,647 B，sha256 `f2ef64f3…8ce6c4c`）
- 一句话结论：**不建议按现状发版**。产品侧发现一个首启导航缺陷；验收工具侧在 release 包上会必然失分（脚本依赖 debuggable）。两件事分开记。

## 一、结果总表

| 项 | 结果 | 一句话 |
| --- | --- | --- |
| 1 装 release 包 | **FAIL（任务前提错误）** | 设备上是 debug 签名包，release 包覆盖安装必失败；经授权卸载后安装成功 |
| 2 `m1-device.sh --quick` | **FAIL（6/12，5 PASS / 0 FAIL）** | 产品缺陷：首启不进 P5 |
| 3a 个人空间 → 分享链接 → P8 | **PASS** | 二维码 + 链接 + 7 天有效都正常 |
| 3b 公开种子 导入/激活/取消 | **PASS** | 等待来源 → 正在寻找来源 → 取消有 `public_acquire_cancelled` |
| 3c 评论 发 / 回 / 删 | **PASS（删除未实现，跳过）** | 正文与缩进回复都渲染 |

## 二、产品行为

**1 安装**：首次 `devkit install` 退出 3：`INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package app.huchuan signatures do not match`。设备原包证书 `CN=Android Debug`（sha256 `08bec0a9…`），release 包 `CN=Huchuan Release`（`2b78201b…`，与 APK-SIGNATURE.txt 一致）——包本身没问题，是设备上装的是 debug 构建。`pm uninstall` 后安装成功，装后拉回 `base.apk` 的 sha256 与发布包逐字节一致，flags 中已无 `DEBUGGABLE`。

**2 quick 脚本**：`FATAL: 没进 P5（新群组页）`，前 5 项全 PASS（selfbuilt_so / 邀请码 16 位 / kernel_started / keystore_keyseed / multicast_lock），0 FAIL。根因是产品缺陷：`UserNameScreen.kt:41` 用 `state.groupList.isNotEmpty()` 判「已有群组」，而个人空间也进 `groupList` → `HuchuanApp.kt:724` 走 `enterHome()`，`HuchuanApp.kt:211` 的「首启无群组直接进 P5」成死代码。复现：`pm clear` → 启动 → P1 保存 → 落在 P2（只有「我的分享」）；手动点「+」可正常进 P5。release 包同样复现（s-d1 / s-d2）。

**3a** PASS。P2 首行「我的分享」→ P3 空态 → 添加文件 → 本机文件 → SAF 选 8 MiB `qa-8mib.bin`（`share_import bytes=8388608`，`file_seeding`）→ 自动进 P4 → 「…」→ 分享链接 → P8 出二维码与链接 `https://huchuan-a9f.pages.dev/#s=Y4T0E3GDEXG7QV88ERQQHZ7ND0`（26 字符）。logcat：`link_created share=11b33655 bytes=8388608` / `link_fgs_start` / `link_open`。

**3b** PASS。导无源磁力 `0123456789abcdef0123456789abcdef01234567`：P3 行「未命名种子 + 公开 + 等待来源」（`pub_magnet_added import_only=true`，`pub_magnet_metadata_timeout peers=0 reason=no_source`，`file_public_added scope=public`）；进 P4 点激活 →「正在寻找来源」（`pub_torrent_adopted`）；再点休眠 → 状态回「等待来源」、后果行「已休眠，不占带宽」，条目仍在，logcat `public_acquire_cancelled … from=acquiring reason=user_cancel removed=true torrents=0`。注：取消后状态 chip 是「等待来源」而不是「休眠」——无元数据条目的正确语义，记下来不算缺陷。

**3c** PASS。发「QA pre-release comment 1」→ `comment_created … seq=1`，列表渲染作者与正文；点该行进回复态（「回复 @Mi MIX 2S」+ 取消回复）发「QA reply 1」→ `comment_created … seq=2`，缩进渲染为子回复。**删除未实现**：`strings.xml` 无删除类文案，`FileScreen.kt:465` 评论行只有 `onClick = onReply`，无长按或菜单入口；内核仅有墓碑语义（`OpLog.kt:75` kind=2 只隐藏）。按任务要求跳过并记录。

## 三、验证工具在 release 包上缺什么（独立问题，与产品无关）

`scripts/m1-device.sh:437 pull_private` 用 `adb exec-out run-as` 读应用私有目录，`run-as` 只对 debuggable 包有效；release 包实测报 `run-as: package not debuggable: app.huchuan`。受影响断言：`share.copy_matches_payload`（:828）、`stress.roundN.sha256`（:1015），在 release 包上必然 FAIL（空哈希 vs 期望值）。建议改法：① 脚本识别 `run-as` 失败后把这些项降级为 SKIP 并显式标注「release 包不可读私有目录」，跨端一致性仍由 `:842 sha256.equal_android_desktop`（对端日志 `download_done sha256=`）守住；② 若必须保留手机侧哈希，让内核在 `share_import` 日志里补 `sha256=` 字段（需内核 owner 改代码），脚本改读 logcat。两处都不属于本次可写边界，未改。

## 四、证据

`artifacts/pre-release-qa/`：`s-a*`（3a）/ `s-b*`（3b）/ `s-c*`（3c）/ `s-d1``s-d2`（首启缺陷）/ `s-d3-quick-fail-6of12.png`；`logcat-m1-events.log` 及 `logcat-a|b|c-*.log`；`m1-quick-debug.log`（5 PASS / 0 FAIL / FATAL）；`steps-a.md`（3a 实际执行命令序列 + SAF 自动化四个坑）。quick 脚本自身证据在 `artifacts/m1-device/`（脚本每次会清空该目录）。

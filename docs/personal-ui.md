# 个人空间接界面（P2/P3/P4/P5/P8）

日期：2026-11-08 · 范围：只改 `app/`（内核未动） · 设备：Mi MIX 2S `5b94673a`（API 33）

## 结论
个人空间在 P2 置顶、P3 无「成员」入口、P5 两种失败文案分开；添加文件 → P4「…」→ 分享链接 → P8 二维码
在真机跑通。`bash artifacts/personal-ui/verify-personal-ui.sh` 末行 **PASS（17 条 CHECK）**，
`./gradlew :app:assembleDebug` 通过。

## 改了什么
| 位置 | 改动 |
| --- | --- |
| `app/src/main/kotlin/app/huchuan/HuchuanApp.kt` | `HuchuanState` 增 `personalGroupId` / `isPersonal` / `lastJoinWasPersonal`：读 `kernel.personalSpace()` 与 `lastJoinError()`，界面不按群名猜 |
| `app/src/main/kotlin/app/huchuan/ui/HomeScreen.kt` | 个人空间稳定置顶；行首品牌色图标；该行不显示「做种 n」（成员数 / 做种数对它无意义） |
| `app/src/main/kotlin/app/huchuan/ui/GroupScreen.kt` | 个人空间不出现「成员」入口；空态改「还没有文件，加一个再分享链接」 |
| `app/src/main/kotlin/app/huchuan/ui/NewGroupScreen.kt` | P5 失败分流：`E_JOIN_PERSONAL` →「对方是个人空间，不能加入」，其余 →「加入失败，检查邀请码或网络」 |
| `app/src/main/res/values/strings.xml`、`drawable/ic_personal_space.xml` | 新增 4 条文案 + 一枚 24dp 线性图标（未引入 material-icons） |

## 验收
一键重跑：`bash artifacts/personal-ui/verify-personal-ui.sh`（日志 `artifacts/personal-ui/verify-personal-ui.log`，
截图在 `artifacts/personal-ui/screenshots/5b94673a/`）。

- P2 首行「我的分享」、有专属图标、行内无「做种」：`20261108-135014-430-p2-personal-first.png`（深色 `20261108-135531-862-p2-personal-dark.png`）
- P3 空态（首次进入、还没加文件）：`20261108-133121-186-p3-personal-empty.png`；有文件后仍无「成员」：`20261108-135022-880.xml`
- P4「…」两项都在：`20261108-135046-426-p4-menu-personal.png`；「公开到群外」真跑过一次：`20261108-133757-768-p3-personal-public-chip.png`
- P8 二维码 + 链接：`20261108-135056-293-p8-share-link.png`
- P5 对方是个人空间：`20261108-135153-528-p5-join-personal.png`；笼统失败：`20261108-135235-220-p5-join-failed.png`
- 普通群没被误改：`20261108-133656-359-p3-normal-members-entry.png`（仍有「成员（2 人）」）

第二个内核是桌面 daemon（空数据目录，只有个人空间），手机经 `adb reverse` 连 `127.0.0.1:47300`
加入其 gid 后 8 位；daemon 的 `personal_space_join_rejected` 与手机的 `join_failed reason=E_JOIN_PERSONAL`
都在 `artifacts/personal-ui/logcat-p5-personal.log`。

## 风险与未决
1. `kernel.groups()` 是插入顺序，旧库补建的个人空间排在最后：置顶是界面排序的结果，不是内核保证
   （`docs/core.md §3.13` 写「排在最前」，与代码不符）。
2. 个人空间有了文件后空态文案无法在脚本里复现（没有删除私有条目的入口），脚本会跳过并 NOTE。
3. P9「新建分享」仍把个人空间与普通群混在一个列表里，本轮未分组。
4. 分享链接依赖已部署的 pages.dev；本轮可用，离线时 P8 走既有失败文案。

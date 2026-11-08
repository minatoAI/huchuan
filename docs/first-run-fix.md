# 首启导航缺陷修复（发版前 P0）

## 缺陷与修法

原判据 `groupList.isNotEmpty()`（UserNameScreen.kt:41）把常驻的个人空间也算成「已有群组」→ 恒 true →
P0 出口直接 `enterHome()`，首启落 P2；「无群组进 P5」成死代码，只有点「+」才到得了 P5。

- [HuchuanApp.kt:188](../app/src/main/kotlin/app/huchuan/HuchuanApp.kt)：`HuchuanState.hasRealGroups` —— 读内核 `isPersonal` 标记，**除个人空间外还有群**才算已有群组；没有内核（静态壳）时退回「群列表非空」。
- [HuchuanApp.kt:713](../app/src/main/kotlin/app/huchuan/HuchuanApp.kt)：`enterAfterSplash()` —— P0 放行后先 `awaitGroupsLoaded()`（等 state.json 读完，上限 5 s）再判：无用户名→P1、有普通群→P2、只剩个人空间→P5，并打一行 `event=p0_route has_real_groups=… user_name_set=…`。
- [UserNameScreen.kt:46](../app/src/main/kotlin/app/huchuan/ui/UserNameScreen.kt)：P1「保存」改判 `state.hasRealGroups`。
- 建群/加入成功仍走 `openGroup()` → P3、返回 P2；P2 个人空间置顶未动。

## 验收（Mi MIX 2S `5b94673a`，debug 包，15:37:34 卸载重装后一轮跑完）

| # | 步骤 | 结果 | 证据 |
| --- | --- | --- | --- |
| 1 | 首启 → P1 保存 | 落 P5，不是 P2 | [s1](../artifacts/first-run-fix/screenshots/5b94673a/20261108-153746-236-s1-first-launch-p5.png) |
| 2 | P5 新建群组 → 创建 | 落 P3（成员 1 人）；返回 P2 首行仍是「我的分享」 | [s3](../artifacts/first-run-fix/screenshots/5b94673a/20261108-153804-400-s3-p3-after-create.png)、[s6 dump](../artifacts/first-run-fix/ui-dumps/5b94673a/20261108-153912-398-s6-p2-after-back.xml) |
| 3 | 杀进程重启（已有普通群） | 直接 P2 | [s4](../artifacts/first-run-fix/screenshots/5b94673a/20261108-153813-265-s4-restart-with-group-p2.png)、[dump](../artifacts/first-run-fix/ui-dumps/5b94673a/20261108-153816-569-s4-p2-after-restart.xml) |
| 4 | 杀进程重启（只有个人空间） | 回 P5 | [s2](../artifacts/first-run-fix/screenshots/5b94673a/20261108-153752-905-s2-restart-only-personal-p5.png) |

[logcat](../artifacts/first-run-fix/logcat/5b94673a/20261108-153816-948.log) 里三次落点：`has_real_groups=false user_name_set=false`（先 P1 再 P5）、`false / true`（→P5）、`true / true`（→P2）；建群 `group_created gid=WDXQ7K4M5J1KM5CP`。

## 说明

- s1 与 s2 像素一致（P5 外观相同、状态栏同一分钟），是两次独立启动（pid 17178 / 17179），区别看上面两行 `p0_route`。
- 验证包 = `app/build/outputs/apk/debug/app-debug.apk`（15:34 由当前工作树构建，sha256 `8a27d962…`，与设备上 `base.apk` 逐字节一致）；该工作树同时带着另一条线未提交的 `core/` 改动，本次缺陷与修法都在 `app/` 层。
- 干扰：15:35:16 另一条线在本机 `group_joined 真机验收群` 并拉起 SAF 选文件，打断了第一次验收序列（当时的截图已作废）；结论全部取自 15:37:34 重装的干净一轮，此后无外部事件。
- 边界：只改 `app/` 这两个文件，另加本文档与本目录证据；`core/` 未动。

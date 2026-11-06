# OPPO PKJ110 布局走查

2026-11-06 · 设备 `3B1F5HEADK1TXSF0`（PKJ110，Android 17 / API 37，1440x3168 @640dpi = **360x792dp**）
范围：P0–P7 共 8 页，浅色 / 深色各一套。截图在 `artifacts/oppo/`（devkit 时间戳名已改语义名，`manifest.jsonl` 仍记原路径）。

## 1. 结论

1. 安装、启动、8 页两套主题全部走通：无崩溃、无权限拒绝、无文字截断、无元素重叠。
2. 布局问题 3 条：P4 输入行不贴底、P4 键盘态整体上推压进状态栏、P7 限速框缺 `KB/s`。
3. 跨主题不一致 1 条：深色 `Switch` 是 primary 提亮色 `#4FC7A5`（薄荷绿），与锁死的品牌按钮 `#1F6F5C` 不同源。
4. 权限：`targetSdk=36`；APK **未声明** `ACCESS_LOCAL_NETWORK`，系统侧却已 `granted=true`。升 targetSdk 37 前必须补声明，否则本地网络（mDNS）会被拦。

## 2. 构建与安装

- 构建跳过：`app-debug.apk`（13:51）比 `app/src`、`core/src` 全部源码新；唯一更新的 `app/tools/peer/DesktopPeer.java` 不在 `sourceSets` 内，不进 APK。
- 安装 `devkit install … -s 3B1F5HEADK1TXSF0` → `OK bytes=26186998 durationMs=1200`。
- ColorOS「安装增强防护」**未拦截**，`install_app_detail_safeguard_state` 未改动。

## 3. 逐页布局（截图名相对 `artifacts/oppo/`）

| 页 | 结果 | 证据 |
| --- | --- | --- |
| P0 自检 | 无问题。三行垂直居中，`可互传` 不被进度环挤压 | `p0-light.png` `p0-dark.png` |
| P1 用户名 | 无问题。输入框 312dp（24dp 边距），`最多 20 字` 在框内不越界 | `p1-light.png` `p1-dark.png` |
| P2 首页 | 无问题。搜索框 206dp / `设置` / `+` 中心 y=304·305·305 对齐，布局间距 8dp（实测视觉 ≈23dp：8dp + TextButton 12dp 内边距） | `p2-light.png` `p2-dark.png` |
| P3 群组 | 无问题。标题文本右缘 605px，`成员（1 人）` 左缘 1057px，余 452px；文件行 chip 右对齐不压标题 | `p3-light.png` `p3-light-files.png` `p3-dark.png` |
| P4 文件详情 | **问题 1** 输入行不贴底；**问题 2** 键盘态整体上推 | `p4-light.png` `p4-light-ime.png` `p4-dark.png` `p4-dark-ime.png` |
| P5 新群组 | 无问题。控件间距 16dp，弹窗默认群名 `PKJ110的群组` 完整 | `p5-light.png` `p5-dark.png` |
| P6 成员 | 无问题。邀请码底缘 3053px，手势区起点 3104px，余 13dp 未被压住；但 16 字符正好占满 328dp，横向零余量（字号放大即贴边） | `p6-light.png` `p6-dark.png` |
| P7 设置 | **问题 3** 上行/下行缺单位；抽屉 360dp 铺满全屏 | `p7-light.png` `p7-dark.png` |

**问题 1 · P4 评论输入行不贴底**：内容按 `verticalScroll` 自然流，输入行落在 y≈1847–1943（约 470dp），下方留 306dp 空白。屏越高越明显——OPPO 792dp 上占 40% 屏高，MIX 640dp 上约 25%。`p4-light.png` `p4-dark.png`

**问题 2 · P4 键盘弹出窗口整体上推**：无 `imePadding()`，走 `adjustPan`——输入行 1847→1778、标题 236→167，整窗上移 69px(17dp)，标题落进状态栏/挖孔区（`displayCutout [0,0][1440,160]`）。输入行底 1874 与键盘顶（`ime [0,1938][1440,3168]`）只余 64px(16dp)：能看见，无余量。`p4-light-ime.png` `p4-dark-ime.png`

**问题 3 · P7 上行/下行没有单位**：默认「不限」开 → 两框 `enabled=false`，`KB/s` 后缀不渲染（dump 无该节点）；P4 同类框 `enabled=true` 时后缀正常（`[1172,1361][1312,1457]`）。两个灰框只剩「上行/下行」，看不出单位。

**跨主题 · 深色开关变薄荷绿**：`Switch` 跟 `colorScheme.primary`（深色 `#4FC7A5`），`BrandButton` 锁 `#1F6F5C`，同页按钮深玉青、开关薄荷绿。`p4-dark.png` `p7-dark.png` 对比 `p4-light.png`

## 4. 权限基线

- `dumpsys package app.huchuan | grep -i -E 'ACCESS_LOCAL_NETWORK|targetSdk'` → `minSdk=26 targetSdk=36`；
  `android.permission.ACCESS_LOCAL_NETWORK` 出现在 requested，且 `granted=true, flags=[REVOKE_WHEN_REQUESTED|USER_SENSITIVE_WHEN_GRANTED|…]`。
- `aapt2 dump permissions app-debug.apk` 只有 INTERNET / ACCESS_NETWORK_STATE / ACCESS_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE，**没有** `ACCESS_LOCAL_NETWORK`——是系统侧放行，不是应用声明。
- 启动日志（清空后重启，800 行）grep `ACCESS_LOCAL|SecurityException|Permission Denial|permission.*denied`：命中 **0**。仅一条 OEM 噪声 `OplusThermalStats: Error getting package info`，与权限无关。

## 5. 与 MIX 720x1280 的差异

- 逻辑宽度相同：1440/640 = 360dp，720/320 = 360dp → **横向结论（截断、重叠、间距）可原样平移**。
- 高度差：792dp vs 640dp（+152dp / +24%）。P4 留白与 P4 键盘态在这台更刺眼；P6 邀请码离手势区反而更松。
- 密度 4.0x vs 2.0x：同 dp 下描边更锐，截图体积约 2 倍。
- 挖孔 160px(40dp)：MIX 无挖孔，问题 2 的「标题压进挖孔区」只在 OPPO 出现。
- MIX 数据取自 `docs/ui-notes.md`（720x1280 / API 33），**本轮未连接 5b94673a**。

## 6. 未做与风险

1. 未测 P4 多评论（>5 条）的滚动 + 键盘组合；问题 2 的「多一行就被压」按 insets 推算，未实测。
2. P1 深色需首启态，靠删 `shared_prefs/huchuan.xml` 复现；群组数据在 `files/m1/state.json`，未受影响。
3. 只覆盖布局与权限基线；传输、压力不在本轮。

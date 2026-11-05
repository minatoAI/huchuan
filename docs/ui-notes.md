# UI 修正记录（U1）

日期：2026-11-06 · 执行层 UI agent · 范围：`app/` 的页面、主题、资源

## 1. 结论

1. 浅色 / 深色两套配色改为从品牌深玉青 `#1F6F5C` 完整派生，关闭 dynamic color；深色模式的文本 / 图标主色用同色相提亮版 `#4FC7A5`，填充按钮仍锁 `#1F6F5C`。
2. P0 按 2026-11-06 的新需求重做成**自检开屏页**：只有「互传」、循环进度、一行状态；成功 / 降级自动放行，失败才出现唯一按钮「重试」。NAT / IPv6 / IPv4 明细与「进入 / 重测」已全部移除。
3. P7 的「网络诊断」入口改为只读一行「网络：<当前状态>」，不再跳 P0。
4. P2 顶部整理为「搜索框（次级）+ 设置（文本）+ ＋（品牌圆钮）」，控件数量不变。
5. P1/P3/P4/P5/P6/P7 统一到 M3 的字号、16dp 页边距、24dp 分区间距与语义色。

本轮未能出 APK：`core/` 正在被另一个 agent 大改，`core/src/main/kotlin/app/huchuan/core/m1/` 编译错误阻塞 `:core:compileKotlin`。已改的 app 代码用 `./gradlew :app:compileDebugKotlin -x :core:compileKotlin` 对旧 core 产物编译通过；P0 新四态还未经真机走查。

## 2. 主题（`ui/theme/Theme.kt`）

### 2.1 配色

| 角色 | 浅色 | 深色 | 说明 |
| --- | --- | --- | --- |
| primary | `#1F6F5C` | `#4FC7A5` | 深色提亮版同色相，对 surface `#0F1513` 为 **8.83:1** |
| onPrimary | `#FFFFFF` | `#00281E` | 对 primary 分别 6.02:1 / 7.60:1 |
| primaryContainer / on | `#A6F2D8` / `#002117` | `#0F4A3B` / `#B7F0DC` | 13.26:1 / 8.00:1 |
| surface | `#F7FBF8` | `#0F1513` | 绿灰中性底，不再是 M3 默认紫灰 |
| onSurface | `#171D1A` | `#DFE4E0` |  |
| onSurfaceVariant | `#3F4945` | `#BEC9C3` | 8.94:1 / 10.85:1 |
| tertiary | `#3E6374` | `#A5CCDF` | 降级提示色 |
| error | M3 标准红 | M3 标准红 | 只用于失败态 |

- **填充按钮在两套主题里都用 `#1F6F5C` + 白字**（`ui/Common.kt` 的 `BrandButton`）。原因：M3 的 `primary` 在深色模式必须提亮才能让文本达到 4.5:1，但老板明确说「按钮是薄荷绿」不可接受；把品牌色锁在按钮容器上，文本类主色仍用提亮版。按钮对深底 3.07:1（组件边界 ≥3:1），白字对按钮 6.02:1。
- `alpha` 版的 `surfaceContainer*`、`outline`、`inverse*`、`primaryFixed*` 等全部一起定义，避免组件回落到 M3 默认紫灰。
- dynamic color 未调用任何 `dynamicLightColorScheme` / `dynamicDarkColorScheme`；`Theme.kt` 内有显式注释。
- `values/colors.xml` 与 `values-night/colors.xml` 的 `window_background` 同步为 `#F7FBF8` / `#0F1513`，冷启动不闪默认底色。

### 2.2 字号 / 圆角 / 间距

- `Typography` 显式落地 `headlineSmall / titleLarge / titleMedium / titleSmall / bodyLarge / bodyMedium / labelLarge / labelMedium`，尺寸与行高按 M3。
- `Shapes`：4 / 8 / 12 / 16 / 28dp；按钮沿用 M3 full 圆角，输入框 extraSmall。
- `ui/Common.kt` 新增 `Spacing`：页边距 16dp，分区 24dp，行间距 12dp，行最小高 48dp。各页不再混用 12/16/24。
- P2 搜索框高 56dp、圆角 28dp（M3 search bar 规格）；其余输入框保持 OutlinedTextField 规格。

## 3. P0 自检开屏（`ui/NetworkScreen.kt`）

### 3.1 结构与状态

- 全屏 `Surface`，垂直居中：`互传`（displaySmall，品牌色）→ 48dp 循环进度 → 一行状态文案。没有标题栏、没有返回、没有检测项列表。
- 状态机（UI 内部，不改 core 接口）：

| 阶段 | 显示 | 停留 / 走向 |
| --- | --- | --- |
| BOOT | `启动中` | 700ms |
| CHECKING | `正在检测网络` | 700ms |
| PASS | `可互传`（primary 色） | 450ms 后自动 `onEnter()` |
| DEGRADED | `仅局域网可用`（tertiary 色） | 1200ms 后自动 `onEnter()` |
| FAILED | `检测失败`（error 色） | 停留，下方只有「重试」 |
| OFFLINE | `无网络连接`（error 色） | 停留，下方只有「重试」 |

- 自动放行复用现有出口：用户名未设置 → P1，否则 → P2；没有新增导航目的地。
- 状态切换用 `Crossfade`（220ms）切换状态文案；进度动画与失败态的「重试」用 `AnimatedVisibility` 淡入淡出。失败前进度一直在转，失败后停转。
- 降级 / 失败的判定不新增 core 字段：`NetworkStatus.FAIL` 且 `items` 非空 → 降级；`FAIL` 且 `items` 为空 → 检测失败；`OFFLINE` → 无网络。
- 文案全部 ≤10 字：启动中 / 正在检测网络 / 可互传 / 仅局域网可用 / 检测失败 / 无网络连接 / 重试。

### 3.2 仅 adb 可用的调试入口

`MainActivity` 从 intent extra 读取，`HuchuanApp` 用 `DebugNetworkProbe` 包装原探针；界面不出现任何开关。extra：

| extra | 值 | 作用 |
| --- | --- | --- |
| `huchuan.debug.network` | `limited` | 强制降级（FAIL + 非空 items） |
|  | `failed` | 强制检测失败（FAIL + 空 items） |
|  | `offline` | 强制无网络（OFFLINE） |
| `huchuan.debug.hold` | `boot` | 停在「启动中」，不自动推进 |
|  | `checking` | 停在「正在检测网络」 |
|  | `result` | 停在结果态（PASS / DEGRADED），不自动放行；失败态本来就会停 |

四态取证命令（MIX 序列号 `5b94673a`；OPPO 换 `3B1F5HEADK1TXSF0`）：

```bash
ADB=/e/agentHome/android-sdk/platform-tools/adb.exe   # 或 PATH 里的 adb
S=5b94673a

# 启动中
$ADB -s $S shell am force-stop app.huchuan
$ADB -s $S shell am start -n app.huchuan/.MainActivity --es huchuan.debug.hold boot
tools/devkit/devkit screenshot --serial $S --name p0-boot --artifacts artifacts/ui/light

# 成功自动跳转后（无 extra，等待约 2 秒落到 P1）
$ADB -s $S shell am force-stop app.huchuan
$ADB -s $S shell am start -n app.huchuan/.MainActivity
sleep 3
tools/devkit/devkit screenshot --serial $S --name p1-after-autojump --artifacts artifacts/ui/light

# 降级（停在结果态取证；去掉 hold 就是 1.2 秒后自动进）
$ADB -s $S shell am force-stop app.huchuan
$ADB -s $S shell am start -n app.huchuan/.MainActivity --es huchuan.debug.network limited --es huchuan.debug.hold result

# 失败
$ADB -s $S shell am force-stop app.huchuan
$ADB -s $S shell am start -n app.huchuan/.MainActivity --es huchuan.debug.network offline
```

深色态先执行 `$ADB -s $S shell cmd uimode night yes`，浅色执行 `night no`；切换会重建 Activity、内存状态回到 P0，这是预期的。

## 4. P7 只读网络状态（`ui/SettingsDrawer.kt`）

- 删除「网络诊断」的 `TextButton` 与跳转回调；抽屉底部改为一行不可点文本：`网络：可互传` / `网络：仅局域网可用` / `网络：检测失败` / `网络：无网络连接`。
- 值来自 `state.probe.check()`（抽屉打开时取一次），因此 adb 调试态也能在 P7 看到对应状态。
- `HuchuanApp` 不再从 P7 push `Destination.Network`；P0 只剩冷启动入口。

## 5. P2 层级（`ui/HomeScreen.kt`）

- 顶部一行：16dp 页边距 + 8dp 间距；搜索框 `weight(1f)`，填充 `surfaceContainerHigh`、无描边、28dp 圆角、56dp 高；「设置」降为 onSurfaceVariant 文本按钮；「+」改为 48dp 品牌色 `FilledIconButton`（白字）。
- 控件数量与文案不变（搜索群组 / 设置 / +）。
- 列表行：标题 titleMedium，辅助信息 bodyMedium + onSurfaceVariant；未读 chip 用 primaryContainer，不再用灰绿 secondaryContainer。

## 6. 其余页面

- P1 / P3 / P5 的主按钮统一为 `BrandButton`（`#1F6F5C` + 白字）；P0 失败态「重试」同款。
- P3 文件行、P4 状态行 / 评论、P6 成员行全部改用语义字号与 onSurfaceVariant，间距统一 16 / 24。
- P4：分隔线用 outlineVariant，进度条 / 开关由主题色驱动，限速输入框宽 160dp。
- P6 改用 `PageScaffold` 以复用统一 TopAppBar，并保留 SnackbarHost。
- P7：分区标题 titleSmall，通知 FilterChip 选中态用 primaryContainer。

## 7. 证据（`artifacts/ui/`）

设备：Mi MIX 2S，`5b94673a`，720x1280，浅色 `cmd uimode night no`、深色 `night yes`。截图命令一律 `tools/devkit/devkit screenshot --serial 5b94673a --artifacts artifacts/ui/<light|dark>`。

已截（新主题下的 P1–P4）：

| 页面 | 浅色 | 深色 |
| --- | --- | --- |
| P1 用户名 | `artifacts/ui/light/screenshots/5b94673a/20261106-033106-122-p1-username.png` | `artifacts/ui/dark/screenshots/5b94673a/20261106-033229-036-p1-username.png` |
| P2 首页 | `artifacts/ui/light/screenshots/5b94673a/20261106-033139-960-p2-home.png` | `artifacts/ui/dark/screenshots/5b94673a/20261106-033233-456-p2-home.png` |
| P3 群组 | `artifacts/ui/light/screenshots/5b94673a/20261106-033144-495-p3-group.png` | `artifacts/ui/dark/screenshots/5b94673a/20261106-033237-867-p3-group.png` |
| P4 文件详情 | `artifacts/ui/light/screenshots/5b94673a/20261106-033158-968-p4-file.png` | 未截 |

P0 四态截图**待补**：旧设计（检测项 + 重测 + 进入）的两张 p0-network 截图已删除，避免和新 P0 混淆。补图时按 §3.2 命令生成 `p0-boot` / `p1-after-autojump` / `p0-degraded` / `p0-failed`，浅深各一套。OPPO 1440x3168 本轮未重新安装，未取证。

## 8. 风险与未决

1. **P0 未经真机走查**：代码已通过 app Kotlin 编译，但 APK 被 core 中间态阻塞。Crossfade / AnimatedVisibility 的动画节奏、失败态重试后的重新检测需下轮真机确认。
2. **与旧 spec 冲突**：`docs/design/product-spec.md` §3 P0 仍写着检测项列表 + 重测 + 进入；`docs/build.md` §6 的 P0 文案对照也过期。两者不在本轮允许改动范围，需 L1 另派单更新。
3. **新增文案**：`p0_booting`（启动中）、`p0_degraded`（仅局域网可用）、`p7_network_status`（网络：%1$s）；既有字符串字面值未改。`p0_item_nat/ipv6/ipv4`、`p0_limited`、`p7_network_diagnostics` 已无 UI 引用，暂留资源未删。
4. **P7 状态是快照**：抽屉打开时取一次，M1 内存假数据下够用；接真实探针时需改成可观察状态。
5. **调试入口**：两个 intent extra 只影响探针与停留，不写盘、不出 UI；发布前可考虑用 debug 构建类型隔离。
6. **OPPO 1440x3168**：布局全部用 dp / 自适应宽度，没有固定屏宽假设；但本轮没有该分辨率截图，需下轮补。

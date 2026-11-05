# 构建、安装与版本管理

目标：任何一台装了 JDK 17 与 Android SDK 的机器，跑两条脚本就能得到可安装、可启动的 APK。

- 构建：`scripts/build-android.sh`
- 安装：`scripts/install-android.sh`
- 真机验证工具：`tools/devkit`（`devices` / `install` / `launch` / `ui-dump` / `tap-text` / `screenshot` / `logcat-dump`），AGENTS.md §6.6

## 1. 前置

| 依赖 | 要求 | 本机事实（2026-11-06） |
| --- | --- | --- |
| JDK | 17 | Temurin 17.0.20.1，`JAVA_HOME=E:\dshHome\.jdks\temurin-17` |
| Android SDK | `platforms/android-37`、`build-tools` | `E:\agentHome\android-sdk`（platform `android-37.0`，build-tools 36.0.0 / 37.0.0） |
| adb | platform-tools | 37.0.1，同上 SDK |
| Gradle | 只用仓库自带 wrapper 9.6.0 | 不要改用本机全局 gradle |

SDK 定位顺序：`ANDROID_HOME` → `ANDROID_SDK_ROOT` → 本机默认路径。找到后脚本会按探测结果重写 `local.properties`（该文件不入库）。SDK 在别处时：

```bash
ANDROID_HOME=/path/to/android-sdk scripts/build-android.sh
```

## 2. 构建

```bash
scripts/build-android.sh
```

- 依次检查 JDK 17、SDK 里的 `platforms/android-37`，再跑 `:app:assembleDebug`。
- 成功时最后一行固定为绝对路径：`APK: E:\agentHome\bt-share-app\app\build\outputs\apk\debug\app-debug.apk`。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`（debug 签名，versionName 见 §5）。

## 3. 安装

```bash
scripts/install-android.sh                 # 全部在线设备：安装 + 启动
scripts/install-android.sh -s 5b94673a     # 指定设备（可重复、可逗号分隔）
scripts/install-android.sh --list          # 只列在线设备
scripts/install-android.sh --apk <path>    # 用现成 APK，不重新构建
scripts/install-android.sh --no-launch     # 只装不启动
```

成功输出形如：

```
== [5b94673a] 安装
Success
== [5b94673a] 启动
OK [5b94673a] 已启动，pid=12953
```

启动断言：`am start -W -n app.huchuan/.MainActivity` 无 `Error`，且 2 秒后 `pidof app.huchuan` 有值（进程在，说明没崩）。

### OPPO / ColorOS：安装增强防护

PKJ110（ColorOS，API 37）在首次 `adb install` 时会停在系统页「安装增强防护」并要求人工点「继续安装」（右上「⋮ → 开始深度扫描」后再点「继续安装」）。此时脚本会等 300 秒后报超时并提示。

处理办法（任选）：

1. 手机上出现该页时点「继续安装」，随后重跑脚本；
2. 设置 → 安全 → 安装增强防护 关闭该拦截（本机已把 `settings put system install_app_detail_safeguard_state 0` 置 0，第二次起安装不再拦）。

不装系统组件、不改包名、不用应用商店。

## 4. 改版本号

```kotlin
// app/build.gradle.kts
defaultConfig {
    versionCode = 1          // 整数，只能递增
    versionName = "0.1.0"    // 展示用
}
```

依赖与插件版本集中在 `gradle/libs.versions.toml`：

| 键 | 当前值 | 说明 |
| --- | --- | --- |
| `agp` | 9.5.1 | 基线锁定，改动需 L0 同意（AGENTS.md §9） |
| `kotlin` | 2.2.10 | AGP 9 内置 KGP 版本；`:core` 的 `kotlin-jvm` 与 Compose 编译器插件都用它 |
| `composeBom` | 2026.09.00 | Compose 与 Material3 版本从 BOM 走 |
| `compileSdk` / `targetSdk` / `minSdk` | 37 / 36 / 26 | 写在 `app/build.gradle.kts` |

升级 Kotlin 的做法（AGP 9 内置 Kotlin 要求）：在根 `build.gradle.kts` 的 `buildscript` 里把 KGP 提到目标版本，并同步 `libs.versions.toml` 的 `kotlin`，否则 Compose 编译器插件与 KGP 版本会不匹配。

## 5. 静态壳的行为边界

- 所有数据在进程内存里（`core/…/InMemory.kt`）：一个群组、两个文件、三条评论、三个成员。杀进程即重置，冷启动永远从 P0 开始。
- 不写 BT / libtorrent / 网络代码；「激活 / 休眠」只切内存状态，按 spec §5 状态机推导。
- 「添加文件」调用系统文件选择器（`ACTION_OPEN_DOCUMENT`）拿文件名与大小，加入内存后直接进 P4；取消则什么都不发生。
- 邀请码：内置 `K3P9T7Q2M8X4Z6VB`，P5 只收后 8 位 `M8X4Z6VB`；其他 8 位返回「邀请码不对」。
- dynamic color 关闭，只跟随系统明暗。

## 6. 文案对照（spec §3 → 实现）

每页默认态直接照 spec 渲染；状态专属文案在 `app/src/main/res/values/strings.xml`（资源名见括号），页面按状态切换。

| 页面 | 默认渲染 | 状态专属（资源名） |
| --- | --- | --- |
| P0 网络检测 | `网络检测` `NAT 类型` `IPv6` `IPv4` `可互传` `重测` `进入`；从 P7 进入时多一个 `返回` | `正在检测网络`（p0_checking，检测中 350ms）、`检测失败`（p0_failed）、`无网络连接`（p0_offline，仅留重测）、`仅本机可用`/`请换网络后重测`（p0_limited/p0_change_network，未达标时进入置灰） |
| P1 用户名 | `用户名` `我的设备`（预填设备名）`最多 20 字` `保存` | `请输入用户名`（p1_empty，空提交）、`保存中`（p1_saving，提交中 250ms）、`保存失败`（p1_save_failed，假实现不触发） |
| P2 首页 | `搜索群组` `设置` `+` `我的设备的群组` `做种 3` `组队 2 人` `2 条新消息` | `还没有群组`（p2_empty，搜索无结果时）、`加载失败，重试`/`离线，仅显示缓存`（p2_load_failed/p2_offline） |
| P3 群组 | 群名 `成员（3 人）` `添加文件` `做种 1` `42%` `下载中` `组队 2 人` `做种中` | `休眠`（p3_state_sleeping，P4 关掉开关后）、`还没有文件`（p3_empty）、`加载失败，重试`（p3_load_failed） |
| P4 文件详情 | 文件名 `下载中`/`做种中`/`休眠`、`来源 …`、`42%`、速率、`正在组队 · 2 人` `做种者 1` `激活` `休眠` `单文件限速` `KB/s` `写评论` `发送` | `等待做种者`（p4_waiting_seeder，无做种者时）、`还没有评论`（p4_no_comments，无评论的文件）、`回复 {名字}`（p4_reply，点评论行后） |
| P5 新群组 | `新群组` `邀请码后 8 位` `加入` `新建群组`；建群弹窗预填 `{用户名}的群组` 与 `创建` | `加入中`（p5_joining）、`邀请码不对`（p5_invite_wrong）、`加入失败`（p5_join_failed）、`离线，无法加入`（p5_offline） |
| P6 成员 | `成员` `在线` `做种 1`/`做种 2`/`做种 0` `邀请码` + 16 位码 | `已复制`（p6_copied，点码后 Snackbar）、`暂无成员`（p6_empty） |
| P7 设置 | `设置` `用户名` `全局限速` `上行` `下行` `不限` `通知` 群名 `系统气泡` `仅应用内` `静音` `网络诊断` | `读取中`/`读取失败，重试`/`离线可改，联网后生效`（p7_reading/p7_read_failed/p7_offline_editable） |

与 spec 的两处口径说明：

1. 任务书写「七页」，spec §2 的页面编号是 P0–P7 共 **8** 个，按 spec 全量实现。
2. spec P2 的文案表没有页面标题，因此 P2 不设标题栏（搜索框 + `设置` + `+` 占顶部一行）。

## 7. 环境坑

- **wrapper 下载**：本机访问 `services.gradle.org` 会 307 到 `github.com/gradle/gradle-distributions`，连接被重置。`gradle-wrapper.properties` 保留官方 URL 并写死 `distributionSha256Sum=bbaeb2fe…529a01`；9.6.0 分发包已预置在 `~/.gradle/wrapper/dists/gradle-9.6.0-bin/42k10rwplmzkhuboz9kdazi7s/`。换机器要重下时用镜像：

  ```bash
  curl -L -o gradle-9.6.0-bin.zip https://mirrors.cloud.tencent.com/gradle/gradle-9.6.0-bin.zip
  sha256sum gradle-9.6.0-bin.zip   # 必须等于 bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
  ```

- **不要用本机全局 gradle**：本机只有 9.7.1 的发行版，基线是 9.6.0；一律走 `./gradlew`。
- **MSYS 路径改写**：两个脚本都设了 `MSYS_NO_PATHCONV=1`，否则 `/data/local/tmp`、`pkg/act` 会被改成 Windows 路径。
- **adb 路径**：`adb.exe` 是 Windows 原生程序，安装时必须传 `cygpath -w` 的路径，脚本已处理。

## 8. 真机验证记录（2026-11-06）

| 设备 | 序列号 | Android | 结果 |
| --- | --- | --- | --- |
| Xiaomi Mi MIX 2S | 5b94673a | 13 / API 33 | 安装、启动、P0→P1→P2→P3→P4 全流程通过，开关切「休眠」后 P3 chip 同步变化 |
| OPPO PKJ110 | 3B1F5HEADK1TXSF0 | 17 / API 37 | 安装（首次需人工过「安装增强防护」）、启动、P0…P7 全页面文案核对通过 |

留证：`artifacts/screenshots/<serial>/`、`artifacts/ui-dumps/<serial>/`（devkit 产物，不入库）。

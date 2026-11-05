# 互传（bt-share-app）

私有 BitTorrent 群组互传。当前仓库里是 Android 静态壳：8 个页面、内存假数据，不含任何 BT / 网络代码。

## 技术基线

| 项 | 版本 / 取值 |
| --- | --- |
| Gradle wrapper | 9.6.0 |
| Android Gradle Plugin | 9.5.1（内置 Kotlin，不再单独 apply `kotlin-android`） |
| JDK | 17 |
| **Kotlin** | **2.2.10**（AGP 9.5.1 内置 KGP 版本；`:core` 经版本目录用同一版本） |
| compileSdk | 37 |
| targetSdk | 36 |
| minSdk | 26 |
| Compose BOM | 2026.09.00（Material3） |
| 包名 | `app.huchuan` |
| 应用名 | 互传 |
| 主色 | 深玉青 #1F6F5C，dynamic color 关闭，明暗跟随系统 |

## 模块

| 模块 | 类型 | 内容 |
| --- | --- | --- |
| `:core` | 纯 Kotlin JVM | 领域模型与接口（群组 / 文件 / 评论 / 成员 / 通知档 / 网络检测）+ 内存假实现。不引用任何网络或 Android API |
| `:app` | Android + Compose | 8 个页面（P0–P7）静态实现、导航与返回键、Material3 主题、自适应图标 |

## 目录

```
app/                      Compose 页面（app/src/main/kotlin/app/huchuan）
core/                     领域接口与内存假实现
gradle/libs.versions.toml 版本目录
scripts/build-android.sh  构建 APK 并打印绝对路径
scripts/install-android.sh 安装到真机并启动
docs/build.md             构建 / 安装 / 改版本号
docs/design/product-spec.md  页面、元素、文案（唯一产品事实源）
AGENTS.md                 项目治理规范
```

## 快速开始

```bash
scripts/build-android.sh          # 输出：APK: E:\...\app-debug.apk
scripts/install-android.sh        # 所有在线真机：安装 + 启动
scripts/install-android.sh -s 5b94673a
```

细节（环境前提、OPPO 安装增强防护、改版本号、文案对照）见 [docs/build.md](docs/build.md)。

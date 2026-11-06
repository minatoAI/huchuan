# 第三方组件与许可

本文件列出互传实际使用的第三方组件，以及每一项是否进入发行物（APK、`Huchuan.exe`、`daemon.jar`、绿色版 `dist/Huchuan/`）。
每条都给出版本、许可、来源证据与是否分发。

版本以 `gradle/libs.versions.toml`（JVM 依赖）与 `tools/build-libtorrent-android/versions.env`（原生库）为准。
标着"构建树"的路径在 `.gitignore` 里，跑一次 `bash tools/build-libtorrent-android/build.sh` 后生成；来源仓库与提交号在 `versions.env` 里可查。

## 传输内核

### libtorrent

- 版本：RC_2_1 head `170663b4b4fad545a6e40e77d03a9f66c8d7c363`（`tools/build-libtorrent-android/versions.env` 的 `LIBTORRENT_SHA`）。Windows 侧用 Maven 制品 `libtorrent4j-windows:2.1.0-39` 内嵌的 dll，其 libtorrent 版本按同文件注释为上游的 `24a3adf`。
- 许可：BSD-3-Clause
- 证据：`tools/build-libtorrent-android/build/src/libtorrent4j/swig/deps/libtorrent/COPYING`（构建树）。第 1 至 2 行是 "Copyright (c) 2003-2020, Arvid Norberg / All rights reserved."，第 4 行起为三条款 BSD（第 8、10、13 行是三条条款的开头，第 13 行的 "Neither the name of Rasterbar Software..." 是禁止背书那一条）。
- 分发：是。静态链接进 `libtorrent4j.so`；Android 侧由 `tools/build-libtorrent-android/build.sh` 自编，`app/build.gradle.kts` 的 `prepareM1Natives` 把它拷进 `jniLibs/arm64-v8a`，进 APK。Windows 侧的 `lib/x86_64/libtorrent4j.dll` 从 `libtorrent4j-windows` jar 取出，进 `daemon.jar`。

### libtorrent4j

- 版本：`2.1.0-39`（`gradle/libs.versions.toml` 的 `libtorrent4j`）
- 许可：MIT。注意：不是 BSD-3-Clause。Maven POM 的 `<licenses>` 只写了一条 "The MIT License"，源仓库的 `LICENSE.md` 也是 MIT 全文（Alden Torres 2018-2025，后附 FrostWire 2016 的 MIT 段落）。
- 证据：`org.libtorrent4j:libtorrent4j:2.1.0-39` 的 POM 第 11 至 16 行，`<licenses><license><name>The MIT License</name>`，可在 Maven Central 复核（本机缓存在 `~/.gradle/caches/modules-2/files-2.1/org.libtorrent4j/`）。同版本的 `LICENSE.md` 在构建树 `tools/build-libtorrent-android/build/src/libtorrent4j/LICENSE.md`。
- 分发：是。Java 绑定（jar 内不含 .so）进 APK 与 `daemon.jar`。

### Boost

- 版本：`1.90.0`（`versions.env` 的 `BOOST_VERSION`）
- 许可：BSL-1.0（Boost Software License 1.0）
- 证据：`tools/build-libtorrent-android/build/boost/LICENSE_1_0.txt`（构建树），首行 "Boost Software License - Version 1.0 - August 17th, 2003"。
- 分发：是。只用头文件，编译期进入 `libtorrent4j.so`。

### OpenSSL

- 版本：`3.5.5`（`versions.env` 的 `OPENSSL_VERSION`）
- 许可：Apache-2.0
- 证据：`tools/build-libtorrent-android/build/openssl-src/LICENSE.txt`（构建树），第 2 至 3 行 "Apache License / Version 2.0, January 2004"。
- 分发：是。静态库（`no-shared`）链接进 `libtorrent4j.so`。

## 发现与日志

### JmDNS

- 版本：`3.6.2`（`gradle/libs.versions.toml` 的 `jmdns`）
- 许可：Apache-2.0
- 证据：`org.jmdns:jmdns:3.6.2` 的 POM 第 46 至 52 行，`<licenses><license><name>Apache License, Version 2.0</name>`，`<distribution>repo</distribution>`。
- 分发：是。`:core` 的运行时依赖，进 APK 与 `daemon.jar`。

### slf4j

- 版本：`2.0.7`（`slf4j-api` 由 jmDNS 传递引入；`slf4j-nop` 在 `:core` 与 `:daemon` 显式声明）
- 许可：MIT
- 证据：`org.slf4j:slf4j-parent:2.0.7` 的 POM 第 22 至 27 行，`<name>MIT License</name>`；`slf4j-api-2.0.7.jar` 内含 `META-INF/LICENSE.txt`。
- 分发：是。进 APK 与 `daemon.jar`。

## Kotlin 与 Android 运行时

### Kotlin 标准库

- 版本：`2.2.10`（`gradle/libs.versions.toml` 的 `kotlin`）
- 许可：Apache-2.0
- 证据：`org.jetbrains.kotlin:kotlin-stdlib:2.2.10` 的 POM 第 16 至 21 行，`<name>The Apache License, Version 2.0</name>`。
- 分发：是。进 APK 与 `daemon.jar`。

### Compose

- 版本：BOM `2026.09.00`（`gradle/libs.versions.toml` 的 `composeBom`），实际解析到 `androidx.compose.material3:material3:1.4.0`、`androidx.compose.foundation:foundation:1.10.0`、`androidx.compose.ui:ui:1.10.0`。
- 许可：Apache-2.0
- 证据：上述三个制品的 POM 各有一段 `<licenses><license><name>The Apache Software License, Version 2.0</name>`（material3 在 1.4.0 POM 第 20 至 25 行）。
- 分发：是，仅进 APK（`Huchuan.exe` 的界面是 WebView2 里的 HTML，不用 Compose）。

### AndroidX

- 版本：`androidx.core:core-ktx:1.19.1`、`androidx.activity:activity-compose:1.13.0`、`androidx.lifecycle:lifecycle-runtime-compose:2.11.0`（`gradle/libs.versions.toml`）
- 许可：Apache-2.0
- 证据：`androidx.core:core-ktx:1.19.1` 的 POM 第 20 至 25 行，`<name>The Apache Software License, Version 2.0</name>`；另两个制品 POM 同段。
- 分发：是，仅进 APK。

## Windows 壳

### WebView2 SDK

- 版本：`1.0.4314.35`（`scripts/build-windows.sh` 的 `WEBVIEW2_VERSION`，可用同名环境变量覆盖）
- 许可：Microsoft 许可，内容是三条款 BSD 式文本
- 证据：`windows/.tools/packages/Microsoft.Web.WebView2/LICENSE.txt`（NuGet 包内，首次构建时下载），首行 "Copyright (C) Microsoft Corporation. All rights reserved."，其后 "Redistribution and use in source and binary forms..." 三条。
- 分发：部分。`WebView2LoaderStatic.lib` 静态链接进 `Huchuan.exe`（`windows/CMakeLists.txt` 的 `WEBVIEW2_ROOT/x64/WebView2LoaderStatic.lib`）。WebView2 Runtime 本身不随包分发，由系统或已安装的 Edge 提供。

## 图标资产

### Rough.js

- 版本：未记录。仓库里没有对应 `package.json` 或锁文件，全仓库找不到 rough.js 的版本号，只有生成结果。
- 许可：MIT
- 证据：`assets/brand/android/res/drawable/ic_launcher_foreground.xml` 第 5 行注释 "手绘箭头用 Rough.js（MIT）生成；P2P 字形来自 Kalam Bold（SIL OFL 1.1）"。
- 分发：否。只在设计期生成 `pathData`，Rough.js 的源码不随发行物分发；发行物里只有生成出来的路径字符串。

### Kalam

- 版本：Kalam Bold，OFL 版本 1.1。字体文件本身不入库，入库的是转换后的字形轮廓。
- 许可：SIL Open Font License 1.1
- 证据：`assets/brand/android/assets/licenses/Kalam-OFL.txt` 是 OFL 1.1 全文，首行 "Copyright (c) 2014, Indian Type Foundry (info@indiantypefoundry.com)."，第 3 行 "This Font Software is licensed under the SIL Open Font License, Version 1.1."。字形轮廓在 `assets/brand/android/res/drawable/ic_launcher_foreground.xml` 最后一条 `path`（P2P 三个字母转成路径）。
- 分发：是。字形轮廓进 APK 的启动图标；许可全文随仓库分发。

## 服务端

### Cloudflare Worker（`worker/`）

- 版本：`0.1.0`（`worker/package.json`）
- 许可：无第三方运行时依赖，故本文件无需为它列许可
- 证据：`worker/package.json` 没有 `dependencies`、`devDependencies`、`peerDependencies` 任何一个字段；`worker/src/` 的 12 个模块只 import 彼此的相对路径；`worker/node_modules/` 是空目录。`wrangler` 只在部署时用 `npx --yes wrangler@3` 临时拉取（`docs/deploy-cloudflare.md`），不参与运行时。
- 分发：否。Worker 由使用者自己部署到自己的 Cloudflare 账户，不随客户端发行物分发。

## 分发义务与已知缺口

- 二进制分发时，上面每条要求保留版权声明的许可（BSD-3-Clause、MIT、BSD 式 WebView2 许可）都要求把对应声明随发行物一起提供。本文件就是这份声明集合，发行时随包附上。
- 缺口一：`assets/brand/android/assets/licenses/Kalam-OFL.txt` 目前没有打进 APK 的 assets 目录（`app/src/main/assets` 不存在），OFL 要求随字体一起分发全文。
- 缺口二：APK 的 `jniLibs/arm64-v8a/libtorrent4j.so` 是 libtorrent、Boost、OpenSSL 三者的二进制合体，包内没有可查阅的声明页。发布 APK 前应把本文件一并附上（应用内"关于"页或随包文本）。

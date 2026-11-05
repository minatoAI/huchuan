# 技术选型决策报告（P0）

日期：2026-11-06 · 执行层研究 agent（只读）
适用：Android 13 / API 33 + Android 17 / API 37 + Windows x64 的 BT 点对点文件分发应用
证据口径：版本号、体积、星数、提交数于 2026-11-05 至 2026-11-06 实测或抓取。标「实测」的来自本机命令或旧项目产物，标「抓取」的来自公开 API，无法核实的写「待验证」，不猜。

---

## 0. 结论速览

| 问题 | 推荐 | 一句话理由 |
| --- | --- | --- |
| Q1 BT 内核 | libtorrent4j 2.1.0-39（JVM），arm64 原生库自编并打磁盘 I/O 补丁 | 唯一同时给到 Android arm64 与 Windows x64 现成二进制、且覆盖片级调度与彻底关 DHT 的候选 |
| Q2 跨平台架构 | A：纯 JVM 内核库 + 本地 HTTP/WS 控制面（Android 进程内，Windows 独立 daemon） | 旧项目走 B 路线把同一套群功能写了两遍（C++ 23034 行 / Kotlin 25449 行），结果两端 BT 根本不通 |
| Q3 Windows 壳 | C++ Win32 + WebView2 | MSVC 14.44、Win SDK 26100、cmake、ninja、WebView2 运行时 156 全已装好，安装成本为 0，13.6 MB 产物有先例 |
| Q4 Android UI | Compose + Material3，compileSdk 37 / targetSdk 36 / minSdk 26 | 本机只装了 android-37.0 平台，AGP 9.5 支持上限正好是 37，选低就得联网补 SDK |
| Q5 群组与同步 | 16 字符 Crockford Base32 邀请码 + HKDF 派生组密钥；自带 tracker + mDNS + 手动连接；评论用签名追加日志 | 旧项目 12 位码靠服务端限流防暴力，新架构没有服务端，必须自带 80 位熵 |

三个必须先钉住、否则后面全部返工的架构约束：

1. 私有模式与公开社区是两套互斥的 libtorrent 会话参数（DHT、LSD、UPnP、PEX 的开关都在 settings_pack 上），一个进程里必须开两个 SessionManager，不能混用。
2. 单文件上传限速在 libtorrent 里没有对应 API，只有 torrent 级 setUploadLimit。要满足需求，文件模型必须做成「一文件一 torrent」，这条会反向决定数据模型和目录同步协议。
3. libtorrent4j 的高层 API 没有动态添加 peer，必须落到 swig 调 connect_peer。这是旧项目踩过的坑。

---

## 1. 取证方式与边界

三条证据来源，按可信度排序：

1. 本机实测。旧项目 E:/agentHome/p2p-share 的产物与源码，加上本机工具链探测，可复现，附录 A 给了命令。
2. 公开仓库 API 抓取。GitHub REST、Maven Central、Google Maven、crates.io。数字会变，抓取时间写在各表里。
3. 官方文档。只用于解释机制（BEP、AGP 兼容性），不作为能力证据。

不采信：星数当质量、README 的 feature 列表当实现、别人的选型结论。凡「某内核支持 X」的判断，都回到 API 签名或源码核过。

---

## 2. Q1：BT 内核选型

### 2.1 候选总表

| 项目 | 绑定/语言 | 许可 | 最新版（日期） | 星 | 近 6 月提交 | Android arm64 | Windows x64 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| libtorrent4j | JVM + SWIG 到 libtorrent C++ | MIT（包装层）/ BSD-3（libtorrent） | 2.1.0-39，2026-02-07 | 288 | 0 | 有，Maven 现成 | 有，Maven 现成 |
| frostwire-jlibtorrent | JVM + SWIG 到 libtorrent C++ | MIT | 2.0.15.3，2026-10-31 | 517 | 活跃（10 月连发 4 版） | 有，AAR 含 4 ABI | 有 |
| libtorrent（C++ 本体） | C++ | BSD-3 | v2.1.2，2026-09-25 | 6124 | 100+ | 需自行 NDK 编译 | 需自行 MSVC 编译 |
| rqbit / librqbit | Rust | Apache-2.0 | 9.1.0，2026-10-29 | 1971 | 81 | 无 JNI、无 Android 目标 | 可 cargo 编译 |
| anacrolix/torrent | Go | MPL-2.0 | v1.61.0 | 6133 | 100+ | 无官方绑定，只能 gomobile 自建 | 原生 |
| libtransmission | C++ | GPLv2 | 4.1.4，2026-10-24 | 15384 | 89 | 无 | 无官方库分发 |

抓取时间 2026-11-05。星数为 GitHub API 的 stargazers_count；提交数为 since=2026-05-06 的分页计数（每页 100，超过记 100+）。

libtransmission 直接出局，理由一条：GPLv2。内核链接进闭源商业应用会把整个应用拖进 GPL，没有工程手段可绕。它的形态是整客户端（daemon + RPC + GTK/Qt 前端），不是能嵌进别人应用的库，Windows 侧也没有官方库分发。

### 2.2 平台二进制与体积（实测）

libtorrent4j 的 Maven 制品 jar 体积（Content-Length 实测）：

| 制品 | 版本 | jar 体积 | 备注 |
| --- | --- | --- | --- |
| libtorrent4j-android-arm64 | 2.1.0-39 | 6,210,960 B（5.9 MiB） | 内含 lib/arm64-v8a/libtorrent4j.so |
| libtorrent4j-android-x86_64 | 2.1.0-39 | 6,025,969 B | |
| libtorrent4j-windows | 2.1.0-39 | 4,798,381 B（4.6 MiB） | x64 dll |
| libtorrent4j-linux | 2.1.0-39 | 12,780,030 B | 与本项目无关 |

jar 里是压缩态。装进 APK 后因为 extractNativeLibs=false 必须原样存储，按解压后算。实测解包旧项目 2026-10-29 的 debug APK：

    lib/arm64-v8a/libtorrent4j.so                   14,182,024 B（13.53 MB，未压缩存储）
    lib/arm64-v8a/libjingle_peerconnection_so.so    12,287,312 B（11.72 MB，对照：旧项目还带 WebRTC）
    APK 总计                                        48,205,816 B（45.97 MB）

单位说明：全文的 MB 一律按字节数除以 1048576 折算，等价 MiB。

也就是说 libtorrent4j 单独给 arm64 APK 增加约 13.5 MB。这是硬成本，任何 JVM 内核方案都躲不掉，底层都是同一个 libtorrent。想更小只能裁编译选项（去 webtorrent、日志、uTP），省下来的量级在 1 到 3 MB，不改变结论。

对照：frostwire-jlibtorrent-android-arm64/2.0.15.3 的 jar 是 5,005,762 B；含四个 ABI 的 AAR 是 52,527,560 B。用 abiFilters 只取 arm64 时，实际入包仍是单个 .so 的量级。

Windows 侧对照（实测，旧项目产物）：

    native-cpp/build-bt/p2p-tray.exe      13,580,288 B（含 libtorrent + libdatachannel + WebView2）
    native-cpp/build-ninja/p2p-tray.exe    8,987,136 B（不含 BT）
    native-cpp/build-bt/p2p-setup.exe     14,009,856 B
    p2p-share-windows-x64-setup.exe        9,344,000 B（0.4.5 发布版）

意义：C++ 侧一个完整 WebView2 壳 + libtorrent + WebRTC 是 13.6 MB 的静态可执行文件，不依赖运行时。这给 Q3 提供了体积锚点。

### 2.3 能力矩阵

四类需求逐项核到 API 签名。

| 能力 | libtorrent4j | libtorrent C++ | rqbit | anacrolix/torrent |
| --- | --- | --- | --- | --- |
| 片级调度 | piecePriority(int, Priority)、setPieceDeadline、readPiece、havePiece（TorrentHandle.java:1155/1243/147/184） | 同上（原生） | 只有 only_files 与顺序下载，无对外片级接口 | Piece(i).WriteAt 可直接写片，片级自由度最高 |
| 全局上传限速 | SessionManager.uploadRateLimit(int)（SessionManager.java:345） | settings_pack::upload_rate_limit | 有（CLI 与 API） | ClientConfig.UploadRateLimiter（全局 rate.Limiter） |
| 单 torrent 上传限速 | TorrentHandle.setUploadLimit(int)（TorrentHandle.java:819） | torrent_handle::set_upload_limit | 有 | 无（只有全局） |
| 单文件上传限速 | 无，需一文件一 torrent 才能落到 setUploadLimit | 无 | 无 | 无 |
| 手动加 peer | 高层 API 无，必须 swig connect_peer（旧项目 BtEngine.kt:150 注释确认） | torrent_handle::add_peer | HTTP API POST /torrents/{id}/add_peers | Torrent.AddPeers |
| 彻底关 DHT/PEX/LSD | settings_pack 的 enable_dht / enable_lsd / enable_upnp / enable_natpmp 全可关；BEP 27 私有种子自动禁 PEX | 同左 | 有 dht crate，可关 | 可关 |
| 私有种子（BEP 27） | 支持，TorrentBuilder 可出 v2-only | 支持 | 支持 | 支持 |

三条硬结论：

1. 单文件限速四个内核都没有。libtorrent 的 torrent 级限速是唯一天然可用的钩子，前提是一文件一 torrent。这不是缺陷，是设计约束，必须写进数据模型。
2. libtorrent4j 的手动加 peer 要落 swig。旧项目为此留了注释「高层 API 没有动态加 peer，用 swig 的 connect_peer」，并因此在自编 .so 时打开 deprecated-functions=ON。libtorrent 2.1 起 connect_peer 属于废弃接口，自编 .so 时应在 JNI 层补 add_peer 出口，别继续吃废弃接口。
3. anacrolix 的「直接写片」是四个里独一份，理论上最适合组队加速（别人下好的片直接塞进来）。但 Go 到 Android 只有 gomobile 一条路，且全局限速做不到单文件，代价大于收益。

### 2.4 维护活跃度与已知事故

libtorrent4j：v2.1.0-39 发布于 2026-02-07，距今 9 个月，近 6 个月零提交，属于维护模式。上游 libtorrent 本身活跃（v2.1.2，2026-09-25，近 6 月 100+ 提交），落后的只是 SWIG 包装层和打包。

这个落后会造成真实故障。旧项目 NATIVE-LIBTORRENT.md 记录：官方 2.1.0-39 的 arm64 .so 固定在 libtorrent 24a3adf（2026-01-25），缺磁盘 I/O 生命周期修复，在 Xiaomi MIX 2S / LineageOS 20（Android 13）上稳定触发 Scudo ERROR: invalid chunk state，进程 SIGABRT。换自编的 170663b（RC_2_1 头）后，10 次 343 MB 下载、md5 与做种端一致、0 次 SIGABRT。

这是本次选型里最重要的一条历史证据：内核可用，但官方二进制包不可直接用，必须自编 arm64 .so。自编成本已被付过一次，步骤在旧项目 NATIVE-LIBTORRENT.md 里，NDK 28.2.13676358 与本机一致，可直接复用。

frostwire-jlibtorrent：2026-10-31 发 2.0.15.3，10 月连发 4 版，跟到 libtorrent 2.0.15。它把 Android 四 ABI 打成一个 AAR，省掉自己配 abiFilters 与 keep 规则。当前唯一「活跃且在维护 JVM 绑定」的选项。它不在 Maven Central，走 https://dl.frostwire.com/maven，实际 302 到 GitHub Release 资产，供应链多一跳，需要锁版本并留本地镜像。

rqbit：2026-10-29 发 9.1.0，近 6 月 81 次提交，桌面端是 Tauri 薄壳。但没有 JNI、没有 Android 目标，crates 目录里是 librqbit / librqbit-core / dht / tracker_comms 等纯 Rust 库，接 Android 要自己写 FFI 层，还要装 Rust 工具链（本机没有）。

anacrolix/torrent：v1.61.0，近 6 月 100+ 提交，纯 Go。Go 侧最成熟，但跨到 Android 与 Windows UI 的路都很长。

### 2.5 Q1 结论

推荐 libtorrent4j 2.1.0-39，arm64 .so 自编（NDK 28.2.13676358，ANDROID_PLATFORM 按最终 minSdk 定），JNI 层补 add_peer。

备选 frostwire-jlibtorrent 2.0.15.3。同源 SWIG 包装，API 形状一致，切换主要是改包名导入，条件是自编路线在 CI 上不可重复，或需要跟更新的 libtorrent。

排除 libtransmission（GPLv2）、rqbit（无 Android 绑定且需新装 Rust）、anacrolix（无 JNI 且限速粒度不够）。

推翻条件：

- 自编 .so 无法在 CI 或第二台机器重复产出（即「我这里能跑」），转 frostwire AAR。
- 官方 2.1.0-40 及以后发布并修复 Scudo 崩溃，回到官方制品，自编降为可选。
- 若必须支持 arm32 或 x86 模拟器，libtorrent4j 有对应制品，但 frostwire AAR 一次给全，切换成本更低。

---

## 3. Q2：跨平台架构

### 3.1 三个方案的成本对照

| 维度 | A 单一共享内核（JVM 库 + 本地控制面） | B 各平台各自实现 | C C++ 内核两端各编一份 |
| --- | --- | --- | --- |
| 内核代码份数 | 1 | 2 | 1（两套绑定） |
| 需要新学的语言 | 0 | 0 | C++ 构建 + JNI + MSVC |
| Windows 运行时依赖 | 需要 JRE（约 50 MB） | 无 | 无 |
| 首版工期（估算） | 中 | 长（协议要对两遍，必然漂移） | 长（JNI + 两套构建） |
| 调试难度 | 中（可脱离手机跑内核） | 高（两端行为不一致时无法定位哪边错） | 最高（JNI 崩溃无栈） |
| 两端互通风险 | 低（同一份代码） | 高（已验证会失败） | 低 |
| 体积 | 安卓 +13.5 MB，Windows +50 MB | 安卓 +13.5 MB，Windows +13.6 MB | 同左 |

### 3.2 为什么 B 不是理论风险，是已经发生过的事故

旧项目 p2p-share 就是 B。它有两份 BT 实现：Android 走 libtorrent4j（android/app/src/main/java/dev/p2pshare/app/bt/BtEngine.kt），Windows 走 C++ libtorrent（native-cpp/bt/src/）。规模：

    native-cpp（.cpp + .h）  23,034 行
    android（.kt）           25,449 行
    app（.ts/.tsx）          14,523 行

结果写在旧项目 STATUS.md 里：

    R0-2 安卓与 Windows 的 1:1 已对齐   FAIL
         证据：安卓已发 BtOffer，native-cpp 零命中，安卓到 Windows 传不了文件
    R5-1 跨网穿透机制                    FAIL
         证据：UPnP、NAT-PMP、uTP、DHT、打洞、IPv6、TURN 全部关闭或不存在

同一时期 79 号文档记录了更完整的漂移：Windows 默认构建里 P2P_ENABLE_BT 是 OFF，群功能根本不编译；Windows 没有 1:1 会话页；群文件下载按钮卡在「已开始」。两端各自实现，最后长成两个不同的产品。

这条证据的价值不在「B 慢」，在「B 的失败方式是静默的」：两端都能编译、都能单测通过，只有真机对传才暴露。对要求「两端必须互通」的项目，这是不可接受的风险形态。

### 3.3 推荐 A 的具体形态

A 不是「Android 起一个 daemon」。准确说法是：内核是一个纯 JVM 库，Android 在应用进程内直接调用，Windows 起独立 daemon 进程，两端共用同一份内核代码和一个 HTTP/WS 控制面。

    core-kernel（纯 JVM，无 Android API）
      会话管理（私有/公开双 session）
      文件模型（一文件一 torrent）、做种激活与休眠
      限速（全局 + torrent 级）
      群组、邀请码、成员表
      评论同步（签名日志）
        |
        +-- Android：进程内调用 + 前台服务保活
        +-- Windows：daemon 进程（jlink JRE） <-HTTP/WS-> WebView2 壳

控制面用 HTTP/WS 的理由不是「微服务时髦」，是三条具体收益：

1. 测试可以脱离手机。内核在桌面 JVM 上跑，验证脚本用 curl 打接口就能断言片调度、限速、私有关闭项，不用每次装 APK。直接满足 AGENTS.md 第 6 节「能用命令验证的，不进手工流程」。
2. Windows 壳与 Android 应用走同一套接口语义，两端行为不会悄悄分叉。
3. 内核崩溃与 UI 崩溃解耦。Windows 上 daemon 挂了 UI 能报错重启，而不是整个窗口消失。

代价写清楚：

- Windows 要带一个 JRE。用 jlink 裁到只留 java.base + java.net.http + jdk.crypto.ec，约 45 到 60 MB。这是本方案唯一新增体积。
- Android 上前台服务与 Doze 会杀内核。这不是架构问题，是平台适配问题，任何方案都要面对。
- 控制面必须只绑 127.0.0.1 且带一次性 token，否则同机其他程序能操纵传输。

### 3.4 备选 C 的适用条件

C（C++ 内核两端各编一份）在以下条件成立时值得翻盘：

- Windows 端坚决不接受 JRE。
- 需要在 libtorrent 层做定制存储（比如先写私有盘再原子移动），JNI 层不够用，必须改 C++ 内核。旧项目恰好做过这件事（git log：「取件改成选一个文件夹直接写盘」「私有盘临时文件」）。
- 团队里 C++ 调试能力强于 Kotlin。

C 的隐藏成本是 JNI 调试：崩溃只有 tombstone，没有 Kotlin 栈。旧项目踩 Scudo 崩溃时，定位靠的是换 libtorrent 版本而不是查栈。

### 3.5 Q2 结论

推荐 A。内核用 libtorrent4j，代码组织成一个无 Android 依赖的 JVM 模块，Android 与 Windows daemon 共用。

落地顺序：先写 core-kernel 与 HTTP 控制面，在桌面 JVM 上用 curl 跑通「建私有种子 → 加 peer → 传完 → md5 一致」，再接 Android UI。这样第一行 Android 代码写下去时，内核已经验证过。

推翻条件：

- 实测 daemon 常驻 RSS 超过 300 MB，或冷启动超过 3 秒（jlink JRE 起 JVM 的现实风险），转 C。
- 两台真机 + Windows 三者互传时出现只有 Android 复现、桌面 JVM 不复现的内核缺陷，说明 Android 适配层已厚到该分家，重新评估 C。
- 若产品要求 Windows 端单文件绿色版（无安装、无运行时），A 的 JRE 成为硬伤，转 C。

---

## 4. Q3：Windows 壳

### 4.1 先修正一个前提

任务书写「本机没有 dotnet 也没有 rust」，这两条对，但没提 C++ 工具链。实测本机：

    安装：C:/Program Files (x86)/Microsoft Visual Studio/2022/BuildTools（VS 生成工具 2022 17.14.37516.0）
    MSVC：14.44.35207（cl.exe 存在，x64/x86 齐全）
    Windows SDK：10.0.26100.0
    cmake 3.31.6、ninja：均可用
    WebView2 运行时：C:/Program Files (x86)/Microsoft/EdgeWebView/Application/156.0.4314.58
    系统：Windows 11 10.0.26200

也就是说 C++ + WebView2 这条路的安装成本是 0，而 .NET 与 Rust 都要新装。表里的安装成本按实测填，不按印象填。

### 4.2 五个宿主方案

| 方案 | 需新装什么（实测） | 运行时依赖 | 产物体积 | 与 JVM 内核对接 | 结论 |
| --- | --- | --- | --- | --- | --- |
| C++ Win32 + WebView2 | 无（工具链全在） | WebView2 运行时（Win11 自带，本机 156） | 壳本体几 MB，旧项目整包 13.6 MB | HTTP/WS 到 daemon | 推荐 |
| .NET WPF/WinForms + WebView2 | .NET 8 SDK，安装包 225,979,432 B（215 MiB） | self-contained 约 70 到 90 MB；framework-dependent 需 Desktop Runtime | 中 | HTTP/WS 到 daemon | 备选 |
| Tauri 2.12.5 | Rust（rustup-init 12,839,424 B，装完约 1.5 GB）+ 本机已有 MSVC | WebView2 | 约 5 到 10 MB | HTTP/WS 到 daemon | 不推荐（第三门语言） |
| Electron v45.2.3 | 无编译器，只需 npm 下载 | 自带 Chromium | win32-x64 zip 150.9 MB，装完约 230 MB | HTTP/WS 到 daemon | 不推荐（体积） |
| Edge --app 模式 | 无 | 本机 Edge | 0 | 直接指向 127.0.0.1 | 仅 P0 演示 |
| JVM 侧（Compose Desktop / JavaFX WebView） | 无（JDK 17 已在） | 需打包 jlink JRE（约 45 到 60 MB） | 估算 60 到 90 MB（含 JRE 与 Skia / WebKit） | 同进程 JVM，不需要 HTTP | 不推荐 |

体积来源：.NET 8 SDK 与 rustup 为 curl -I 的 Content-Length 实测；Electron 为 GitHub Release 资产 size 字段（electron-v45.2.3-win32-x64.zip = 150.9 MB）；C++ 为旧项目产物实测。Electron 与 .NET 装完后的体积是估算，标注在此，别当实测引用。

### 4.3 为什么推荐 C++ Win32 + WebView2

三个理由，按权重排：

1. 安装成本 0。装 .NET 要 215 MB 下载加约 1 GB 磁盘，装 Rust 要 1.5 GB，都只为开一个窗口。
2. 有先例。旧项目 native-cpp/tray 与 native-cpp/shell 就是 C++ Win32 + WebView2，带托盘、单实例、深链接、拖拽，产物 13.6 MB，在这台机器上构建成功过。要写的是裁剪版，不是从零探路。
3. 无运行时耦合。静态链接的可执行文件，不依赖 .NET 运行时版本，不需要 Electron 更新。WebView2 在 Win11 上是系统组件，本机版本 156.0.4314.58。

代码量估计：最小 WebView2 宿主约 300 到 600 行（窗口创建、CreateCoreWebView2EnvironmentWithOptions、消息循环、导航拦截、只允许 127.0.0.1 的策略）。托盘与单实例再加 200 行。比搭 Tauri 或 Electron 骨架 + 学一套新工具链便宜。

### 4.4 为什么其它三个不推荐

.NET 是唯一有竞争力的备选：WPF + WebView2 的官方封装成熟，C# 写窗口比 C++ COM 舒服。它输在安装成本和运行时依赖。如果 C++ 侧 COM 初始化卡住超过一天，转 .NET 是合理止损，不要再往 Tauri 绕。

Tauri 的问题不是技术，是账：为省 40 MB 体积多装 1.5 GB 工具链并引入第三门语言，而这个项目的体积瓶颈在 libtorrent 的 13.5 MB 和 JRE 的 50 MB，壳的体积本来不是主要矛盾。

Electron 把 Chromium 打进安装包，150.9 MB 起。同一个 WebView2 系统组件能做的事，没必要每台机器再带一份 Chromium。

JVM 侧方案（Compose Desktop 或 JavaFX 的 WebView）看起来省事，因为 JDK 17 已经在机器上，不必再装 .NET 或 Rust。它有两个过不去的地方。一是它不走 WebView2，等于在 Windows 上另起一套 UI 实现，而 AGENTS.md 第 4.6 条要求 Windows 用 WebView 壳承载同一套信息架构，这条直接冲突。二是 JavaFX 的 WebView 是 WebKit，现代 CSS 支持落后 Chromium 一个身位，同一个页面在 Android 的 WebView（Chromium）与 JavaFX 里会渲染成两个样子。若真要跨端共用 UI，WebView2 才是那条路，而它又回到了 C++ 或 .NET 宿主。

### 4.5 Edge --app 模式值得保留的理由

    msedge --app=http://127.0.0.1:PORT

零代码零安装，能立刻看到 Web UI 在 Windows 上的真实渲染。适合 P0 验证 UI 与接口，以及在 CI 里做截图回归。不能当产品：没有托盘、没有单实例、窗口图标是 Edge 的、用户关掉浏览器可能连带关掉「应用」。

### 4.6 Q3 结论

推荐 C++ Win32 + WebView2 宿主，指向本地 daemon 的 Web UI。P0 阶段先用 Edge --app 顶住，等内核接口稳定再写宿主。

推翻条件：

- WebView2 的 COM 初始化在本机 156 版本上无法稳定工作（例如 SetVirtualHostNameToFolderMapping 受限），转 .NET WPF。
- 若产品决定 Windows 也要原生控件而不是 Web UI，C++ 壳的价值归零，转 .NET 或 Compose Desktop。
- 若目标用户大量使用 Windows 10 且 WebView2 运行时缺失（本机是 Win11，覆盖不到这条），安装包需要带 WebView2 Evergreen 引导器，或改 Electron 求自包含。

---

## 5. Q4：Android UI 技术栈

### 5.1 版本矩阵（全部抓取，2026-11-05）

| 组件 | 版本 | 来源 |
| --- | --- | --- |
| Android Gradle Plugin | 9.5.1（9.6.0-alpha03 为最新预发） | Google Maven com/android/tools/build/gradle |
| Compose BOM | 2026.09.00 | Google Maven androidx/compose/compose-bom |
| Compose UI / Foundation | 1.12.1 | BOM 2026.09.00 的 pom 钉的版本 |
| Material3 | 1.4.0 | 同上（material3 独立最新为 1.5.0-rc01，未进稳定 BOM） |
| Kotlin | 2.3.0 | Now in Android 主分支 libs.versions.toml |
| navigation-compose | 2.10.2 | Google Maven |
| room-runtime | 2.8.5 | Google Maven |
| datastore-preferences | 1.3.0 | Google Maven |

AGP 9.5 的兼容性要求（官方 release notes 原文）：最大支持 API level 37，Gradle 最低 9.6.0，JDK 最低 17，默认 build-tools 36.0.0，默认 NDK 28.2.13676358。本机 JDK 17.0.20.1、NDK 28.2.13676358、build-tools 36.0.0 与 37.0.0，全部对上。

### 5.2 compileSdk 与 targetSdk

compileSdk 只能是 37。本机 E:/agentHome/android-sdk/platforms 下只有 android-37.0 一个平台，选 36 就得联网补装 SDK，与「构建可脚本化、可重复」冲突。AGP 9.5 的上限正好是 37，不存在版本不够。

targetSdk 建议 36，不直接上 37。理由：Now in Android 主分支的 targetSdk 仍是 36；Android 17 的行为变更（后台限制、前台服务类型、通知权限细化）必须在 API 37 真机上逐条验，而 OPPO PKJ110 是间歇在线。现在升等于把两端风险叠在一起。

### 5.3 minSdk

约束三条：必须覆盖 API 33 真机（26 以上都满足）、不低于 libtorrent4j 的 .so 构建 API、让存储代码路径尽量单一。

| minSdk | 覆盖 Android | 代价 |
| --- | --- | --- |
| 24 | 7.0 | 要处理一堆 24 到 25 的兼容分支，收益接近 0 |
| 26 | 8.0 | 推荐。需要 legacy 外部存储分支，但本应用只写应用私有目录与 SAF 目录，实际不受影响 |
| 28 | 9.0 | 若沿用官方 .so 且怀疑其 API floor 高于 26，退到这里最省事 |
| 29 | 10 | 彻底绕开 scoped storage 兼容代码，但砍掉的设备比 26 多 |

推荐 minSdk 26，同时把自编 .so 的 ANDROID_PLATFORM 定成 26，让两边对齐。旧项目自编时用 android-28，那是当时的选择，不是约束。

易错点：官方 libtorrent4j-android-arm64 的 .so 用什么 ANDROID_PLATFORM 编的，仓库里没有标注，属于待验证项。若最终沿用官方制品，minSdk 应保守取 28，先用 NDK 的 llvm-readelf 看 .note.android.ident 段确认。

### 5.4 Now in Android 可以借鉴什么

NiA 现状：22,044 星，settings.gradle.kts 里 35 个模块，AGP 9.3.2，Kotlin 2.3.0，targetSdk 36，近 6 月 8 次提交。它是示例工程不是依赖，版本永远落后于 Google Maven 上的最新稳定版，所以借模式不借版本。

值得抄的四件事：

1. build-logic 约定插件。把 compileSdk、minSdk、compose、lint 的配置收进 convention plugin，每个模块只写一行。对必须「构建可脚本化」的项目尤其重要，重复的 gradle 配置是「我这里能跑」的温床。
2. 单向数据流。ViewModel 持有 StateFlow<UiState>，UiState 是不可变 data class，界面只读不写。这与极简 UI 要求配合得好：一个页面一个状态机，状态就是页面的全部。
3. 分层按功能切，不按类型切。feature-xxx 模块各自带 ui 与 viewmodel，core-data / core-designsystem 提供共享层。
4. 截图测试与 Baseline Profile 的位置。NiA 把截图放在各模块 src/test/screenshots，基准测试放在 benchmarks 模块。真机断言脚本可以直接复用这个目录约定。

不要抄的两件事：

1. 35 个模块。本应用按 AGENTS.md 是「一个页面一个功能」，第一版 5 到 7 个模块足够（app、core-design、core-kernel、core-data、feature-transfer、feature-group、feature-settings）。模块数超过功能数就是纯负担。
2. Hilt 的完整注入图。NiA 用它管几十个依赖。本项目内核是单例、数据层只有两三个仓库，手动构造更清楚，等真需要再上。

### 5.5 Q4 结论

Compose + Material3，compileSdk 37 / targetSdk 36 / minSdk 26，AGP 9.5.1 + Gradle 9.6.0 + JDK 17，Compose BOM 2026.09.00。模块 5 到 7 个，用 convention plugin 统一配置，抄 NiA 的数据流与截图测试位置，不抄它的模块切分与 DI 规模。

推翻条件：

- AGP 9.5.1 在 compileSdk 37 上首次构建失败且无法用预发版绕过，降 compileSdk 36（需联网补 SDK，可接受）。
- 若 Android 17 真机出现只有 targetSdk 37 才能复现的问题，提前升 targetSdk 37 并把 OPPO 纳入日常验证。
- 若自编 .so 必须用 ANDROID_PLATFORM 28 才能稳定，minSdk 提到 28。

---

## 6. Q5：群组与同步协议

### 6.1 邀请码

旧项目做法（docs/49）：12 位 Crockford Base32，显示为 K7F2-M9QX-DP4T，规范化去连字符转大写后 SHA-256 存为 inviteHash，服务端只查哈希。60 位熵加服务端限流，在有中心服务端的前提下够用。

新架构没有服务端，暴力破解的限流点消失。60 位对纯离线校验的共享密钥偏低，推荐提到 80 位：

    码长：16 字符 Crockford Base32（每字符 5 bit，共 80 bit）
    显示：XXXX-XXXX-XXXX-XXXX（4 组 4 位）
    字符集：0123456789ABCDEFGHJKMNPQRSTVWXYZ（去掉 I L O U，避免手抄混淆）
    规范化：转大写、去连字符与空白、O 转 0、I 与 L 转 1
    校验位：末位为前 15 位的 CRC-5（或 Luhn mod 32），只查格式，不承担安全职责

可离线校验分成两件事设计：

1. 格式校验。解码加校验位，不需要网络。输入错一位立刻提示，不用等联网。
2. 归属校验。邀请码本身就是组密钥的种子：

        groupKey   = HKDF-SHA256(ikm = 邀请码规范化字节, salt = 组创建时间, info = bt-share/group/v1)
        inviteHash = SHA-256(groupKey || invite)                 对外公开的标识
        rendezvous = Base32(SHA-256(groupKey || rv))[0..16]      mDNS 服务名与 tracker passkey

任何持有码的成员都能独立算出 inviteHash 与 rendezvous，不需要连服务端。加入时的证明用 HMAC：申请方发 HMAC(groupKey, joinerPubKey || nonce)，任一在线成员本地验证即可。这就是可离线校验的落地方式。

安全提示写进设计：邀请码等同组密钥，泄露即失守。必须支持重置（重新生成 groupKey 并广播新 rendezvous），旧码立即失效。旧项目的 rotate 流程可以复用。

### 6.2 成员发现

| 手段 | 覆盖场景 | 成本 | 风险 |
| --- | --- | --- | --- |
| 自带 tracker（内核内置 HTTP/UDP announce） | 有公网可达节点时的主要发现 | 中，约 300 行 | 需要一台有公网 IP 的机器，或群主本机做 tracker 但 NAT 后不可达 |
| 公共 tracker | 公开种子社区 | 低，填 URL 即可 | 私有群的 info_hash 与 IP 会暴露给第三方，私有模式禁用 |
| 手动连接（加 peer 的 IP:端口） | 兜底，永远可用 | 低 | 需要用户互相报 IP，体验差 |
| 局域网 mDNS | 同一 Wi-Fi 下 | 中，Android 用 NsdManager，Windows 用 DnsServiceRegister | 只覆盖同一广播域 |

推荐分层，不是四选一：默认先 mDNS（同网段最快），再自带 tracker（跨网），最后手动连接兜底。三层的 rendezvous 标识都用同一个邀请码派生值，不出现第二套身份。

关于 libtorrent 自带的 LSD（BEP 14）：它是 mDNS 实现，但私有模式下必须关（enable_lsd=false）。我们自己的 mDNS 是独立实现，只广播「我在这个群、我在这个端口」，且只对同网段可见。两者的区别要写进代码注释，否则后来的人会以为关了 LSD 就没有局域网发现。

### 6.3 私有网络的强制关闭项

libtorrent 侧（settings_pack，旧项目 BtEngine.kt 与 native-cpp/bt/src/bt_settings.cpp 都是这么写的，可直接复用）：

    enable_dht          = false
    enable_lsd          = false
    enable_upnp         = false
    enable_natpmp       = false
    enable_incoming_utp = false（可选，按需）
    enable_outgoing_utp = false（同上）

种子侧：BEP 27 私有标志。libtorrent 对 private=1 的种子自动禁用 PEX 与 DHT 通告，这是第二道保险。旧项目连种子格式都统一成 BitTorrent v2-only（BEP 52），避免 v1 的 pieces 字段被误当作公共种子处理。

验证方式必须可证：抓包或会话统计。libtorrent 有 dht_nodes、dht_torrents 统计项，私有 session 上这两个值必须恒为 0；再用 netstat 确认没有 DHT 出站。这比读代码可靠。

### 6.4 公开种子社区与私有模式的共存

这是需求里最容易忽略的冲突。公开社区要 DHT 与公共 tracker，私有传输要全关。这些开关在 settings_pack 上是会话级的，一个 session 不可能既开又关。

结论：必须两个 SessionManager。

| 会话 | DHT | 公共 tracker | 用途 | 存储 |
| --- | --- | --- | --- | --- |
| private | 关 | 只用自带 | 群内传输 | 应用私有目录 |
| public | 开 | 允许 | 公开社区 | 独立下载目录 |

两个会话要分端口、分存储目录、分限速配置，UI 上让用户一眼看出当前下载属于哪一种。混用一个 session 是隐私事故的起点。

### 6.5 评论的去中心化同步

候选对比：

| 方案 | 许可 | 语言 | 星 | JVM/Android 绑定 | 适配度 |
| --- | --- | --- | --- | --- | --- |
| Loro | MIT | Rust | 6,273 | 核心仓库 crates/ 下只有 loro-wasm，无 JNI crate | 高（若需富文本/编辑） |
| Automerge | MIT | Rust + JS | 6,718 | 仓库只有 javascript / rust / interop，无 Java 绑定 | 中 |
| Yjs | MIT | JS/TS | 23,025 | 无，Android 需内嵌 JS 运行时（QuickJS 或 WebView） | 低 |
| cr-sqlite | MIT | C/Rust | 未核 | 需 SQLite 扩展加 JNI | 低 |
| 签名追加日志（自研） | 自有 | Kotlin | 0 | 原生 | 高（评论场景足够） |

推荐自研签名追加日志，不上通用 CRDT。理由是评论的数据形状：只追加、不改写、按作者分区、按时间展示。CRDT 解决的是并发编辑同一段文本，评论场景里不存在并发编辑。上 Loro 或 Automerge 的代价是要么新写一层 Rust JNI（新增 .so 与工具链），要么在 Android 里塞一个 JS 引擎，两条都为不存在的问题付钱。

日志结构（每条评论一个 op）：

    op = { opId, authorPub, seq, lamport, ts, prevHash, targetFileId, bodyCid, sig }
    opId     = SHA-256(authorPub || seq)[0..16]
    prevHash = 该作者上一条 op 的 opId（哈希链，防篡改与乱序）
    sig      = Ed25519(authorPriv, canonicalBytes)

合并规则三条，按优先级：

1. 因果排序：按 (lamport, authorPub 字典序, seq) 全序。lamport 在收到别人的 op 时取 max(local, remote)+1。
2. 去重：opId 唯一，重复收到直接丢弃。
3. 删除：保留墓碑（tombstone），不物理删，否则别处的旧副本会把删掉的评论传回来。

同步沿用旧项目的分页游标：请求方带 knownIds（上限 200），应答方按 lamport 升序返回缺的部分。这套在旧项目里跑通过（docs/49 §5），可以直接借形状，只把 msgId 换成 opId、把 (at, msgId) 排序换成 (lamport, authorPub, seq)。

保留升级路径：把接口定义成「版本向量 + op 流」，而不是「评论列表」。将来真需要富文本或协同编辑时，换 Loro 只需替换 op 的载荷类型，同步层不动。

### 6.6 Q5 结论

推荐：16 字符 Crockford Base32 邀请码加 HKDF 派生组密钥与 rendezvous；发现层用 mDNS 加自带 tracker 加手动连接三层；评论用签名追加日志加 Lamport 全序加墓碑，同步沿用分页游标。

推翻条件：

- 若产品要求评论可编辑且要处理并发编辑同一段文字，引入 Loro，并接受 Rust JNI 的体积与工具链成本。
- 若群规模超过 64 人（旧项目上限），日志全量同步的流量会失控，改成基于版本向量的差集拉取，而不是按条数游标。
- 若自带 tracker 在真实 NAT 环境下无法被群成员访问（无公网节点），退化为 mDNS 加手动连接，并把「跨网需要一方有公网 IP」写进产品文案，不伪造可用性。

---

## 7. 推荐路线（一张表）

| 决策点 | 推荐 | 备选 | 何时切换 |
| --- | --- | --- | --- |
| BT 内核 | libtorrent4j 2.1.0-39 + 自编 arm64 .so（NDK 28.2.13676358） | frostwire-jlibtorrent 2.0.15.3（同 API 形状，AAR 含四 ABI） | 自编不可在 CI 重现，或需要跟更新的 libtorrent |
| 架构 | A：纯 JVM 内核库 + 本地 HTTP/WS 控制面，Android 进程内 / Windows daemon | C：C++ 内核两端各编 | daemon RSS 超 300 MB 或冷启超 3 s；或 Windows 要求无运行时绿色版 |
| Windows 壳 | C++ Win32 + WebView2（工具链已在，运行时 156 已在） | .NET WPF + WebView2（需装 215 MB SDK）；P0 期用 msedge --app | WebView2 COM 初始化受阻，或团队 C++ 产能不足 |
| Android UI | Compose + M3，compileSdk 37 / target 36 / min 26，AGP 9.5.1 + Gradle 9.6.0 + JDK 17，BOM 2026.09.00 | minSdk 28（沿用官方 .so 时） | AGP 在 compileSdk 37 构建失败 |
| 群组 | 16 字符 Crockford Base32 + HKDF 派生组密钥与 rendezvous | 12 字符（若确认有中心限流点） | 群规模扩大或需要可编辑评论 |
| peer 发现 | mDNS（自研）+ 自带 tracker + 手动连接，三层 | 公共 tracker（仅公开社区） | 无公网节点时退化为两层 |
| 评论同步 | 签名追加日志 + Lamport 全序 + 墓碑，游标分页 | Loro（需 Rust JNI） | 出现并发编辑需求 |
| 私有/公开隔离 | 双 SessionManager，分端口分存储分限速 | 分进程（更彻底） | 出现跨会话串扰证据 |

### 7.1 建议的验证顺序（每一步都能证伪）

1. 桌面 JVM 上跑内核：建私有种子 → 手动加 peer → 传 32 MB → 两端 md5 一致。不碰 Android。
2. 加私有约束断言：私有 session 的 dht_nodes 恒为 0，netstat 无 DHT 出站。
3. 装到 MIX 2S：同场景加 10 次 343 MB 压测，grep 日志无 SIGABRT / Scudo。这是自编 .so 的回归门，旧项目就是这么验的。
4. Windows：Edge --app 指向 daemon 的 Web UI，跑同一个传输场景。
5. 三端互通：Android 与 Windows 互传，这是旧项目 R0-2 失败的那一格。

---

## 8. 推翻条件汇总

按「这条被证伪，哪个结论作废」组织，方便后续复核直接对照。

| 编号 | 若观察到 | 则作废 | 复核方式 |
| --- | --- | --- | --- |
| F1 | 自编 .so 在第二台机器或 CI 上产不出等价二进制 | Q1 推荐（转 frostwire AAR） | 干净容器里跑旧项目 NATIVE-LIBTORRENT.md 的构建步骤 |
| F2 | 官方 2.1.0-40+ 修复 Scudo 崩溃 | Q1 的自编必要性 | 用官方 .so 跑 10 次 343 MB，grep SIGABRT |
| F3 | Windows daemon 常驻 RSS 超 300 MB 或冷启超 3 s | Q2 的 A 方案（转 C） | jcmd VM.native_memory + 冷启计时 |
| F4 | 私有 session 出现非零 dht_nodes | 整个私有模式 | libtorrent 统计项断言 + netstat |
| F5 | WebView2 156 上 COM 初始化失败 | Q3 的 C++ 壳 | 写一个 50 行宿主跑通再说 |
| F6 | AGP 9.5.1 + compileSdk 37 构建失败 | Q4 的 compileSdk | gradle assembleDebug 首跑 |
| F7 | 官方 .so 的 .note.android.ident 显示 API 高于 26 | Q4 的 minSdk 26（提到 28） | llvm-readelf 看运行时 API |
| F8 | 无公网节点导致跨网永远连不上 | Q5 的 tracker 层 | 两台不同网络设备实测 |
| F9 | 评论出现并发编辑需求 | Q5 的无 CRDT 结论 | 产品评审，不是技术验证 |
| F10 | 三端互传时只有 Android 复现缺陷 | Q2 的共享内核假设 | 按 7.1 第 5 步跑 |

---

## 9. 未决项（本报告不替 L1 决定）

1. 应用名与包名。影响 Android applicationId、Windows 安装目录、mDNS 服务名前缀。
2. 自带 tracker 部署在哪。群主机器（NAT 后不可达）还是单独一台公网机器。这是产品与运维决策。
3. 公开种子社区的数据来源。是否要抓公共索引站，涉及合规。
4. 旧项目的私有盘与原子落盘方案（docs/58）是否继承。它解决「下载到一半的文件被用户看到」，与 libtorrent 的存储后端强相关。
5. 是否要求 Windows 绿色版（免安装）。这一条会直接推翻 Q2 的 A 方案。

---

## 附录 A：证据复现命令

体积与版本（本机）：

    # 旧项目 APK 里的 .so 体积
    python -c "import zipfile;z=zipfile.ZipFile(r'E:/agentHome/p2p-share/android/app/build/outputs/apk/debug/app-debug.apk');[print(i.filename,i.file_size) for i in z.infolist() if i.filename.endswith('.so')]"

    # 旧项目 Windows 产物
    ls -la E:/agentHome/p2p-share/native-cpp/build-bt/*.exe

    # 本机工具链
    "/c/Program Files (x86)/Microsoft Visual Studio/Installer/vswhere.exe" -products '*' -format text
    ls "/c/Program Files (x86)/Microsoft/EdgeWebView/Application"

Maven 与 Google Maven：

    curl -s https://repo1.maven.org/maven2/org/libtorrent4j/libtorrent4j-android-arm64/maven-metadata.xml
    curl -sI https://repo1.maven.org/maven2/org/libtorrent4j/libtorrent4j-android-arm64/2.1.0-39/libtorrent4j-android-arm64-2.1.0-39.jar
    curl -s https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/maven-metadata.xml
    curl -s https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml

GitHub 活跃度：

    curl -s https://api.github.com/repos/aldenml/libtorrent4j
    curl -s "https://api.github.com/repos/aldenml/libtorrent4j/commits?since=2026-05-06T00:00:00Z&per_page=100"
    curl -s "https://api.github.com/repos/frostwire/frostwire-jlibtorrent/releases?per_page=5"

代码事实（不要信文档，回代码核）：

    # 私有约束的真实写法
    sed -n '30,75p' E:/agentHome/p2p-share/android/app/src/main/java/dev/p2pshare/app/bt/BtEngine.kt
    sed -n '1,44p'  E:/agentHome/p2p-share/native-cpp/bt/src/bt_settings.cpp

    # 手动加 peer 的现实写法
    sed -n '150,156p' E:/agentHome/p2p-share/android/app/src/main/java/dev/p2pshare/app/bt/BtEngine.kt

    # 两端互通的失败记录
    grep -n "R0-2\|R5-1" E:/agentHome/p2p-share/STATUS.md

    # 自编 .so 的完整步骤与验证结论
    cat E:/agentHome/p2p-share/NATIVE-LIBTORRENT.md

## 附录 B：引用清单

本机文件：

- E:/agentHome/p2p-share/STATUS.md（R0-2、R5-1 失败记录）
- E:/agentHome/p2p-share/NATIVE-LIBTORRENT.md（自编 .so、Scudo 崩溃、10 次 343 MB 验证）
- E:/agentHome/p2p-share/BUILD.md（三端构建，含 8.6 与 13.0 MB 产物对照）
- E:/agentHome/p2p-share/docs/49-群组与BT分发协议-2026-10-26.md（邀请码、私有种子、同步规则）
- E:/agentHome/p2p-share/docs/79-界面功能对齐-安卓到Windows-2026-10-29.md（两端漂移记录）
- E:/agentHome/p2p-share/android/app/src/main/java/dev/p2pshare/app/bt/BtEngine.kt（settings_pack 与 connect_peer 实写）
- E:/agentHome/p2p-share/native-cpp/bt/src/bt_settings.cpp（C++ 侧同源设置）

外部：

- https://api.github.com/repos/{aldenml/libtorrent4j,arvidn/libtorrent,frostwire/frostwire-jlibtorrent,ikatson/rqbit,anacrolix/torrent,transmission/transmission,android/nowinandroid}
- https://repo1.maven.org/maven2/org/libtorrent4j/（版本与 jar 体积）
- https://dl.frostwire.com/maven/（jlibtorrent 制品，302 到 GitHub Release）
- https://dl.google.com/dl/android/maven2/（AGP、Compose BOM、Material3）
- https://developer.android.com/build/releases/gradle-plugin（AGP 9.5：最大 API 37、Gradle 9.6.0、JDK 17）
- https://crates.io/api/v1/crates/{rqbit,tauri}
- https://raw.githubusercontent.com/android/nowinandroid/main/gradle/libs.versions.toml

标准：BEP 3（HTTP tracker）、BEP 14（LSD）、BEP 15（UDP tracker）、BEP 27（私有种子）、BEP 52（v2 种子）、RFC 8032（Ed25519）、Crockford Base32。

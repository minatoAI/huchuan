# 旧项目考古：p2p-share（main @ 3c9b250）

范围：`E:\agentHome\p2p-share`，工作区 13 项未提交。同族里 `p2p-share-dev` 只剩空 `tmp/`，`p2p-share-archive` 是两个 git bundle，
`p2p-share-audit` 是 2026-10-31 的后继设计稿（未实现），`reference/` 只有 Natter 与 nowinandroid 两个外部参照。
本文只读旧仓，未改其任何文件；未在本机重跑 `verify/`，结论以仓内落盘证据与代码为准。

## 一句话结论

旧项目是一个**能跑的局域网私有 BT + 完整浏览器分享链**，工程水平中等偏上、资产密度高，但**不可整体续用**：
局域网双机 BT 有真机 SHA 证据（32 MiB / 25.4 MB/s），网页端 177 项测试全绿，tracker 是真实现；
然而 1:1 传输跨端已断（安卓→Windows 不认 `BtOffer`）、跨网零穿透、群聊全明文、验收门仍是 6 FAIL + 5 MANUAL
（`p2p-share/STATUS.md`）。复用价值在**代码资产、协议定义与验证装置**，不在架构。

## 一、功能完成度

| 域 | 判定 | 证据 |
| --- | --- | --- |
| 传输内核（BT） | 可用。安卓 libtorrent4j + Windows libtorrent；私有种子 v2-only，局域网双机 32 MiB 两端 SHA 一致 | `STATUS.md` R1-2；`docs/76-L2双机实测-2026-10-29.md` |
| 吞吐 | 群下载 25.4 MB/s ≈ WiFi 天花板 23–32 MB/s；1:1 DataChannel 仅 7–10 MB/s | `docs/66-传输基准复核-2026-10-29.md` §1–§5 |
| 起传延迟 | 裸引擎 v2 种子 62.3–62.9 s，v1 2.11 s；四组对照排除了调用方式与做种端 | `docs/76` §七；`STATUS.md` R1-3 FAIL |
| 私有分享（1:1） | 安卓↔安卓走 BT + 配对码/SAS；安卓→Windows **断**（native 端 `BtOffer` 源码零命中）；Windows 1:1 仍走数据通道 | `STATUS.md` R0-2；`docs/72` 需求 0；`docs/79:4` |
| 公开种子与社区 | **不存在**。DHT/LSD/UPnP/NAT-PMP/uTP 全关，无磁力导入、无公共 swarm、无社区/发布流 | `android/.../bt/BtEngine.kt:44-52`；`docs/72` 需求 5；社区概念只在 `p2p-share-audit/docs/designs/concepts_brief.md` |
| 群组 | 能跑：建群/邀请码/目录全量分页/缩略图/做种策略/冷做种唤醒；但明文、无踢人、passkey 随 `.torrent` 外泄 | `docs/49-群组与BT分发协议-2026-10-26.md` §4–§7、§11；`STATUS.md` R3-1 FAIL / R3-2 MANUAL |
| UI | 安卓 Compose 全套（20 个 ui 文件：记录/连接/设置 + 群三页 + 1:1 会话）；Windows 托盘 9 视图，无 1:1 会话页 | `android/app/src/main/java/dev/p2pshare/app/ui/`；`docs/79` §二 |
| tracker 服务端 | 真实现、有测试、线上路由可探测；只做 WAN 发现，不传字节 | `tracker/src/*.ts`（8 文件）；`tracker/README.md`；`docs/72` 需求 2 |
| 验收装置 | 5 档脚本（静态 / 单测 / 双机 / 跨网 / UI），`STATUS.md` 机器生成 | `verify/verify.sh`、`verify/levels/{L1-ui,L2-lan,L3-crosswan}.sh` |

规模：Kotlin 25,449 行 / 71 文件，C++ 20,275 行（其中 `bt/` 模块 3,323），网页侧 TS 10,759 行，`docs/` 75 份 md。
测试：网页 147 core + 30 worker（`docs/72` §二）、tracker 8 项（`STATUS.md` H-5；`tracker/README.md` 记 6 单测 + 15 smoke）、安卓 2 个测试文件 389 行、C++ 1 个 `identity_test`。

## 二、技术栈与关键决策

- **BT 引擎**：安卓用 `org.libtorrent4j:libtorrent4j:2.1.0-39`，但 `.so` 是自编译替换件（libtorrent RC_2_1 `170663b`），
  修官方 pin 的 `24a3adf` 在 Android 13 上的 `Scudo ERROR: invalid chunk state` 崩溃；SWIG 面与官方一致（3659 个 `Java_*`）。
  `android/app/libs/libtorrent4j-android-arm64-2.1.0-39-lt-170663b.jar`、`NATIVE-LIBTORRENT.md`。
- **Windows 原生层**：`native-cpp/bt/`（3,323 行：建种 `torrent_factory`、会话 `bt_session`、组传输 `group_bt`、UTF-8 边界 `fs_text`、断点 `resume_store`），
  `native-cpp/tray/`（WebView2 托盘）、`native-cpp/shell/`（send-to / 第二实例 / CLI）。
- **tracker/**：Cloudflare Worker + 两个 Durable Object（`RegistryDO` 存 passkey 哈希，`SwarmDO` 每 infohash 一个），
  BEP 3 `/announce`，compact peers + `peers6`，P-256 签名注册，v1 40hex 与 v2 64hex infohash 都收，interval 1800 / min 900 / numwant ≤50。
  `tracker/src/index.ts`、`tracker/wrangler.jsonc`、`tracker/README.md`。
- **传输分流（已定决策）**："链接参与走 WebRTC，其余全走 BT"。`docs/74-决策记录-2026-10-29.md` 决定二。
  理由有实测支撑：DataChannel 7–10 MB/s vs BT 25.4 MB/s（`docs/66` §5）。
- **签名 / 身份**：P-256，`deviceId = SPKI SHA-256 前 10 字节`；HTTP 管理接口验签，WebSocket 不验签（`docs/72` 需求 4）。
- **构建**：安卓 gradle 9.7.1 + AGP 9.4.1 + JDK 17，`compileSdk/targetSdk = 37`、`minSdk = 26`（`android/gradle/wrapper/gradle-wrapper.properties`、`android/app/build.gradle.kts:52-60`）。
  Windows 必须 Ninja（多配置生成器会让 `p2p-setup` 的 rc.exe 报 RC2135），vcpkg `x64-windows-static`（`AGENTS.md` §Windows 端构建、`BUILD.md`）。
- **无 CI**：仓里没有 `.github`（`STATUS.md` H-3 FAIL、`NEXT.md` §7 明确不做）。

## 三、可直接搬运的代码与资产

许可基线：整仓 MIT（`p2p-share/LICENSE`），第三方声明在 `THIRD_PARTY_NOTICES.md`。注意该文件**没有列 libtorrent / libtorrent4j**。

| 路径 | 是什么 | 许可 | 可用性 |
| --- | --- | --- | --- |
| `tracker/src/` + `tracker/test/announce.test.ts` | 私有 BT tracker 全套（8 文件，零依赖，`node --test` 可跑） | MIT | **直接可用**，最干净的一块 |
| `tracker/scripts/smoke.mjs` | 端到端 smoke（错误签名/过期 ts/错误 passkey/未注册 infohash） | MIT | 直接可用 |
| `app/src/core/`（4,261 行） | 网页核心：protocol / share / transfer / session / pairing / candidates | MIT | 可用；与安卓 Kotlin 实现是两份，需选一份为准 |
| `app/tests/core/`（12 文件）+ `app/tests/worker/` | 147 + 30 项自动化测试，含 native-interop | MIT | 直接可用，是旧仓唯一"机器判真"的那层 |
| `app/src/worker/presence.ts`（1,321 行） | 灯塔 DO：presence、群注册表、帧转发、lan 地址校验 | MIT | 可复用，但群消息明文，需重写加密层 |
| `android/.../groups/GroupBt.kt`、`GroupStore.kt`、`GroupFiles.kt`、`GroupSeedingPolicy.kt` | 群 BT：挂种守卫、目录对账、分片规则、计量网络自动停种 | MIT | **高价值**，踩过的坑都写在里面 |
| `android/.../bt/BtEngine.kt` | libtorrent4j 会话配置（监听/加密/开关） | MIT | 可用，但需改 `0.0.0.0` 监听与准入 |
| `native-cpp/bt/include/p2p/bt/fs_text.h` + `src/fs_text.cpp` | Windows UTF-8 ↔ path 边界 | MIT | **直接抄**，一次解决中文文件名乱码 |
| `native-cpp/bt/src/torrent_factory.cpp` | v2-only 私有建种 + piece 自动放大 | MIT | 可用（v1 种子慢 30 倍的问题见 §六.7） |
| `verify/`（`verify.sh` + 5 个 level + 3 个 check） | 验收门：PASS/FAIL/MANUAL 三态、STATUS 生成、反向测试过 | MIT | **直接可用**，改包名即可 |
| `scenarios/*.yaml`（6 份） | 需求 → 可判定场景 → 检查编号的映射 | MIT | 直接可用，是"需求可验收"的样板 |
| `ui-proto/`（`proto.js` 66 KB / `proto.css` 24 KB） | 双端静态原型（手机底栏 + 桌面三栏同一套页面逻辑） | MIT | 可用作信息架构参照，不宜当视觉稿 |
| `design/capture.spec.ts` + `build-deck.mjs` + `screens.html` | Playwright 自动截屏 → 生成界面清单页 | MIT | **直接可用**，是"用编号讨论界面"的工作法 |
| `design/shots/`（18）、`docs/shots/`（48） | 真机与网页截图 | MIT | 可用，注意含旧品牌名 |
| `android/app/libs/libtorrent4j-...jar`（5.3 MB，内含 14 MB `.so`） | 修过崩溃的安卓 BT 运行库 | 上游 libtorrent BSD-3 / libtorrent4j 各自主张；**jar 内无 LICENSE** | 可用，但必须补版权声明后再分发 |
| `native-cpp/tray/web/qrcode.js` / `jsqr.js` | 内联二维码生成 / 识别 | MIT / Apache-2.0 | 直接可用（文件头保留原声明） |
| `android/app/src/main/assets/licenses/Kalam-OFL.txt` | 图标里 "P2P" 字形用的 Kalam 字体许可 | SIL OFL 1.1 | 随图标资产一起带 |
| `p2p-share-release-assets/*.apk / *.exe`、`p2p-share-local/2026-10-22/` | 旧品牌安装包（0.4.4 / 0.4.5） | MIT + 同上第三方 | **不建议复用**，仅作回归对照 |
| `p2p-share-archive/*.bundle` | 三个已删旧仓的完整历史（79.8 MB + 6.8 MB） | — | 只在需要考古时 `git clone` |

## 四、Android 17 本地网络权限的历史经验

**在哪遇到。** 2026-10-24 在 OPPO PKJ110（API 37）上"链接分享发不过去"：OS 层的 `ACCESS_LOCAL_NETWORK` 默认拒绝，
未获批的局域网 TCP/UDP 被系统静默丢弃、无任何提示。该厂商实现比 AOSP 文档口径更宽——连第三方浏览器的公网 STUN/UDP 采集也一起掐，
系统浏览器豁免（`docs/42-断网恢复-局域网直连-交接-2026-10-24.md:406`、`docs/47`:97）。
授权后局域网 TCP/UDP/WebRTC 恢复正常（`docs/44`:50）。

**怎么处理。**
1. 声明：`android/app/src/main/AndroidManifest.xml:8`。
2. 请求：`MainActivity.kt:69-75`，`SDK_INT >= 37` 且未授权时 `registerForActivityResult(RequestPermission)` 拉起系统弹窗；
   拒绝则 Toast「需要允许本地网络访问，才能和同一 WiFi 下的设备直连」。
3. 归因：`P2PSession.kt:333-336` 判定 + `:1158-1175` 把失败原因收敛成权限提示，并**抑制自动重试**
   （`retry = !missingLocalNetwork && ...`）。这条注释写明：此前最容易把它误判成"VPN/代理拦截"。
4. 网页侧兜底：`app/src/adapters/browser/index.ts:446-473` 逐个试浏览器层 permission name（`local-network-access` 等），
   查不到就退 `unsupported`，真正判据是随后 2–3 秒的 LAN probe；识别出被系统拦就引导用户改用系统浏览器（`docs/47`:15）。

**还缺什么（新项目必须补）。**
1. **群/BT 路径完全没接这个权限**：`android/.../groups/` 零引用，只有 1:1 会话有归因；群局域网候选被拦时表现为"没有在线种子"。
2. **没有跳系统设置**：全仓 `ACTION_APPLICATION_DETAILS_SETTINGS` 零命中，用户点过"拒绝"后 App 只能反复报同一句。
3. **只在 `onCreate` 请求一次**：拒绝后无二次入口；`hasLocalNetworkPermission()` 是现算的，但没有任何地方在动作前调用它。
4. **权限不等于准入**：BT 监听 `0.0.0.0`、源码 `ip_filter` 零命中（见 §六.4），局域网内知道 infohash 即可连。
5. **诊断要单列**：把"本地网络权限"做成诊断页一行结论（有/无 + 一键跳设置），而不是散落在错误文案里。

## 五、UI 原型与 "Now in Android" 参考

- **原型**：`ui-proto/`——`index.html` 手机与桌面并排预览，`proto.html` 单壳，`proto.js`（66 KB）是全部交互，`serve.mjs` 起 8130 端口。
  规格定稿在 `docs/50-UI规格-QQ式三栏原型定稿-2026-10-26.md`，且明写"**原型即权威，与本文冲突以原型为准**"。
  信息架构：记录（群/设备/链接三区 + 传输中分区）/ 连接（只放入口）/ 设置（含网络诊断）。
- **截图工作法**：`design/screens.html` 把真跑出来的 18 屏编号，`design/variants.html` 存首页三个方向，
  `design/capture.spec.ts` 用 Playwright 自动截、`design/build-deck.mjs` 拼页——"改哪里直接说编号"就是这么来的。
- **Now in Android：旧仓里没有**。全仓 grep `Now in Android|NiA` 零命中；旧仓的 Compose 是单模块 `:app` + 自写组件（`android/settings.gradle.kts` 只 include `:app`）。
  真正的参考检出于 `E:\agentHome\reference\nowinandroid`（`android/nowinandroid` @ `2982e63`，Apache-2.0，198 MB，含 `.gradle` 缓存）。
  新仓 AGENTS.md 要求的 Material 3 + NiA 组件/动效范式，旧仓**没有落地样例可抄**，只能从该 checkout 取。

## 六、不要再犯的错误

1. **一个事实源裂成三仓四副本**。`presence.ts` 同文件三代 1,321 / 519 / 496 行，只有第一份有群功能；子 agent 读到死副本，报出"群后端不存在"。
   `docs/72` §三·病灶一、`docs/75`。
2. **改动不提交，文档照提交状态写**。HEAD `3c9b250` 上仍有 13 项未提交（`git status --porcelain`），其中 3 个是通知栏切片的新文件；
   `docs/66` 开头那段"§7 的结论已过时"就是同一种病的自白。规则写在 `AGENTS.md` 第一条与 `README.md` 硬规则二，但仍复发。
3. **文档记录从未发生的验证**。`docs/22`:26 写"UPnP 已验证（UDP 42000-42009）"，源码 `miniupnpc` 零命中、`42000` 零命中，
   `UPnP` 实际是 `BtEngine.kt:47` 硬编码 `false`。带端口号的假证据会被后续文档引用成共识（`docs/72` §三·病灶三）。
4. **验收门自己造假绿**。`verify/verify.sh:168` 用 `grep -rq 'ip_filter' "$AND" "$REPO/native-cpp"`，会扫进 `native-cpp/build-bt/*.exe`；
   源码里 `ip_filter` 是 0，二进制里有 libtorrent 符号，于是 R4-1 判 PASS。复核命令：
   `grep -rn 'ip_filter' --include=*.kt --include=*.cpp --include=*.h android native-cpp | wc -l` → 0。
   教训：grep 类检查必须白名单后缀并排除 build 目录。
5. **用"能编译"替代"能用"**。Windows 群功能曾因 `CMakeLists.txt:28` 默认 `OFF` 而在成品里整块消失（8.6 vs 13.0 MB）。
   HEAD 已改成默认 `ON` 并写注释，但 `docs/79` §零 仍写"默认关"——**文档比代码旧，这正是第 2 条的变体**。
6. **验收验了没人用的代码**。`BtRuntime`（token + 7001/6891）在主代码没有调用方，只有 debug 探针用；通知栏读的是没人写的状态（`WISHLIST.md` 一、`NEXT.md` §1.2）。
7. **只量吞吐，漏掉起传延迟**。v2 种子 62 s 才起传，v1 是 2 s；单看 25.4 MB/s 会得出"没问题"（`docs/76` §七）。
   一个指标测一件事，用户感知的"点了没反应"必须单独一条。
8. **Windows 窄字符串按 ANSI 泄漏**。`std::filesystem::path` 的 `.string()` 在中文系统出 GBK，nlohmann 抛 `type_error.316`，
   对端文件名变乱码。只准走 `path_from_utf8/path_to_utf8`（`native-cpp/bt/include/p2p/bt/fs_text.h`、`docs/49` §9.1）。
9. **JSON `null` 把信令线程带走**。`value(key, "缺省")` 遇 `null` 抛 `type_error.302`，回调线程无 catch → `std::terminate` →
   进程以 `0xc0000409` 静默消失、日志一字不留。所有取值走 `text_at/flag_at/...`，`handle_signal` 外套 catch（`docs/49` §9.2）。
10. **在同一个上下文里自我审查**。用户 2026-10-29 明令：审查类任务必须开 subagent，且提示词里只放事实不放理由。
    证据是同轮出现过"把'这是设计'当挡箭牌"和"结论重复两遍没发现错"（`AGENTS.md` 验证者一节、`docs/70`）。
11. **拿局域网结论当需求达成**。需求 1 要求"人多越快"，而人多意味着不在同一 AP；只在同 AP 跑通就判达成，是口径错误（`scenarios/REQ-1-bt-group.yaml` 的 `rule` 已写明）。
12. **把设备问题当代码问题**。UI 验收在 OPPO 息屏后 dump 到锁屏，报成"缺少文案"；脚本后来固定 `stay_on_while_plugged_in` 并按原值恢复
    （`verify/levels/L1-ui.sh` 头部注释）。同类：`adb shell cat` 会转换二进制，必须 `exec-out`（`AGENTS.md` 已知的坑）。
13. **Compose Dialog 在 uiautomator 里抓不到文本**（dump 只有 2,691 字节、text 全空）。断言要换信号：窗口数 10 → 16，或按 `y = H - 76dp` 算坐标（`NEXT.md` §1）。
14. **先设计后核对现状**。上一轮 9 次人工介入里 4 次是设计类错误，静态检查拦不住。修法是开工前先出"现状事实表（行为 + file:line + 一个反例）"（`NEXT.md` §4、`docs/73`）。

## 七、图标资产

旧仓**没有密度位图**：`res/` 下只有 `mipmap-anydpi-v26/` 一个 mipmap 目录，没有 `mipmap-mdpi…xxxhdpi`、没有 png/webp 启动图标，
也没有 `android:roundIcon`——圆角/圆形遮罩完全交给启动器对 adaptive-icon 裁剪，因此**不存在独立的"圆角遮罩"资源**。
（`minSdk = 26` 所以够用；若新项目要支持 <26 或上架商店，需另出 512×512 PNG 与密度位图。）

（表中 `…` 表示与首行相同的前缀 `E:\agentHome\p2p-share\`。）

| 绝对路径 | 尺寸 / 格式 | 说明 | 许可 |
| --- | --- | --- | --- |
| `E:\agentHome\p2p-share\android\app\src\main\res\mipmap-anydpi-v26\ic_launcher.xml` | 350 B，XML | adaptive-icon，background + foreground + monochrome 三件套 | MIT |
| `…\res\drawable\ic_launcher_background.xml` | 861 B，矢量 108dp / viewport 108 | 线性渐变 `#FF1E6A55 → #FF0C362A`，注释"形状交给启动器裁剪" | MIT |
| `…\res\drawable\ic_launcher_foreground.xml` | 7,608 B，矢量 108dp | 浅绿圆托 + 白色手绘箭头 + "P2P"；6 条 path，2/3 缩放、圆半径 28，只用中间 72×72 | MIT（箭头由 Rough.js 生成，MIT；字形来自 Kalam Bold） |
| `…\res\drawable\ic_launcher_monochrome.xml` | 6,757 B，矢量 108dp | 主题图标，只留形状，`scale 0.6667` / pivot 54 | MIT |
| `…\res\values\ic_launcher_background.xml` | 125 B，XML | 兜底色 `#1F6F5C`（深玉青） | MIT |
| `…\assets\licenses\Kalam-OFL.txt` | 4,466 B，文本 | "P2P" 字形所用 Kalam 字体的 SIL OFL 1.1 全文 | SIL OFL 1.1 |

同族其它端的品牌资产（一并拷贝，供 Windows / Web 壳使用）：

| 绝对路径 | 尺寸 / 格式 | 说明 | 许可 |
| --- | --- | --- | --- |
| `…\native-cpp\tray\assets\tray.ico` | 110,646 B，ICO，8 帧多尺寸 32bpp | Windows 托盘图标（含动画帧体系） | MIT |
| `…\native-cpp\tray\assets\preview-128.png` | 1,899 B，128×128 RGBA | 托盘图标预览 | MIT |
| `…\app\public\icon-512.png` | 22,253 B，512×512 RGBA | 网页 PWA 图标 | MIT |
| `…\app\public\favicon.svg` | 462 B，SVG viewBox 64 | 蓝色 `#2f6feb` 圆角矩形（rx 15）+ 线性箭头 | MIT |
| `…\app\public\favicon-32.png` | 498 B，32×32 RGBA | 网页 favicon | MIT |
| `…\app\public\apple-touch-icon.png` | 3,606 B，180×180 RGBA | iOS 主屏图标 | MIT |

**视觉并不统一**：安卓是深玉青渐变 + 手绘箭头 + Kalam 字形，网页是纯蓝 `#2f6feb` + 线性箭头。
新应用定名「互传」后若要统一品牌，这是必须处理的一处（旧仓两份视觉各自成体系，`proto.css` 里的 UI 主色又用 `#2f6fed`）。

**复制结果**：已复制 12 个文件到 `E:\agentHome\bt-share-app\assets\brand\`，保持旧仓相对目录结构，逐字节原样：

| 目标路径（`assets/brand/` 下） | 字节 | 来源 |
| --- | --- | --- |
| `android/res/mipmap-anydpi-v26/ic_launcher.xml` | 350 | `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` |
| `android/res/drawable/ic_launcher_background.xml` | 861 | 同名源文件 |
| `android/res/drawable/ic_launcher_foreground.xml` | 7,608 | 同名源文件 |
| `android/res/drawable/ic_launcher_monochrome.xml` | 6,757 | 同名源文件 |
| `android/res/values/ic_launcher_background.xml` | 125 | 同名源文件 |
| `android/assets/licenses/Kalam-OFL.txt` | 4,466 | `android/app/src/main/assets/licenses/Kalam-OFL.txt` |
| `windows/tray/tray.ico` | 110,646 | `native-cpp/tray/assets/tray.ico` |
| `windows/tray/preview-128.png` | 1,899 | `native-cpp/tray/assets/preview-128.png` |
| `web/icon-512.png` | 22,253 | `app/public/icon-512.png` |
| `web/favicon.svg` | 462 | `app/public/favicon.svg` |
| `web/favicon-32.png` | 498 | `app/public/favicon-32.png` |
| `web/apple-touch-icon.png` | 3,606 | `app/public/apple-touch-icon.png` |

复制用 `cp`，抽查 `cmp` 三组（矢量、PNG、ICO）全部一致。旧仓未改动。

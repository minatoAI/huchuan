# 状态

更新：2026-11-06 · 管理层

## 阶段

P3 进 M1：无头内核先行，UI 只做视觉修正

## 环境事实

- 真机：Mi MIX 2S（API 33，720x1280，长期）；OPPO PKJ110 **已撤出调试环境（用户占用），回归时间未定**
- Android SDK：E:\agentHome\android-sdk（platform 37；NDK 28.2.13676358）
- 本机：Java 17 / Node / pnpm / Python 3.14；MSVC 14.44 / Win SDK 26100 / cmake 3.31.6 / ninja 1.13.2 / WebView2 156；无 maven / gradle / dotnet / rust / scrcpy
- 调试入口：tools/devkit（唯一真机操作入口）

## 决策记录

- 应用名 互传；包名 app.huchuan；图标沿用旧项目 adaptive-icon；品牌色深玉青 #1F6F5C
- 技术基线见 AGENTS.md §9（内核 libtorrent4j 2.1.0-39 / 架构 A / C+++WebView2 / Compose）
- 私有种子用 v1；PEX 走 per-torrent flag 0x380000
- 单文件限速只在 P4；P0 三档放行；邀请码 16 字符只输后 8 位
- targetSdk 先 36，M1 壳过后单开一轮升 37 + ACCESS_LOCAL_NETWORK
- M1 不做踢人 / 退群 / 改群名
- 邀请码 M1 只收后 8 位（40 bit）+ 在线限速；保留「P5 收全 16 位」的升级路径，协议零改动
- mDNS 默认不广播群名与在线人数（只 v/gid/bt/r），隐私优先
- 群规模上限 32 人
- P0 是自检开屏页，不是诊断页：无检测项列表、无「进入」、无「重测」；成功自动进、降级显警告后自动进、失败停留给「重试」
- P7「网络诊断」不再跳 P0，改为只读状态行
- 新增需求（L0）：接入系统分享菜单（ACTION_SEND）；应用内为文件创建链接分享，终态是二维码 + 「分享链接」按钮；接收方浏览器直接打开；链接可在设置里撤销。排 M2，系统分享入口可提前
- 链接分享的传输走 WebRTC DataChannel（接收方是浏览器，没有 BT 客户端），信令服务器是必要条件
- 撮合层统一用 Cloudflare Worker + Durable Object（免费额度）：BT tracker（M2 跨网）+ WebRTC 信令（M2 链接分享）共用一个部署
- STUN 用 Cloudflare 公共 STUN；TURN 暂不做（对称 NAT 兜底，M3 再议）
- M1 不依赖任何服务器：局域网走 mDNS，跨网走手动 IP:port
- 私有群禁用公开 tracker 与 DHT：公开 tracker 会泄露 infohash 与成员 IP
- 链接分享：有效期 7 天可撤销；同时访客 1；浏览器单文件 1 GiB；撤销即消失；只做 ACTION_SEND 不做 ACTION_VIEW；多选合成一条（≤10）；Windows 同时给二维码与复制链接
- tracker announce interval 600 s（与 protocol.md 一致）；免费额度按 10 分钟口径重算
- 连接方向由「可达性」决定：双方广播自身可达性（公网端点 / NAT 类型 / 全局 IPv6 / UPnP 结果），不可达的一方主动拨号；与谁在分享无关
- 分层 fallback：局域网 → IPv6 → 公网 IPv4 → UPnP/NAT-PMP/PCP 映射 → STUN 打洞 → 如实告知失败；TURN 永久排除
- UPnP 策略：默认关，跨网分享窗口内按需开，结束撤销，随机端口
- ip_filter 改为「私网 + 群成员公网地址」，否则跨网永远不可能
- 不做 TCP 同时打开与端口预测；UDP 打洞排 M3
- 连接总预算 20 秒，超时给 FAILED_NO_PATH，不转圈

## 在飞

- DEVV 真机验证做种计数与重启续种（含 app 兜底清理）
- JARFIX 截图门禁的 jar 时效判断

## 已完成
- WIN Windows 打包 → dist/Huchuan 34.9 MiB（jlink 运行时，无需装 Java）+ IFileOpenDialog 文件选择器；m1-windows 37/37 PASS
- SHOT WebView2 离屏截图 → 桌面不渲染环境下 build-windows 16/16 PASS（index.json mode=offscreen）
- CORE-FIX 做种计数与重启续种 → SeedingLedger 单一来源（下载完成/休眠/重启/文件缺失全走它）、state.json 存 seeding 数组、重启按原 info 字典续种；selftest 110 条，headless 35 CHECK PASS
- APP Android 接未读角标 / 成员做种数与平台 → 真机 25 checks PASS；角标、清未读、成员平台与做种数全部有真数据
- CORE 内核缺口 → 创建者公钥登记（no_pub 从 232 条降到 0）、peer.announce 补 plat/seeds、未读游标、removeGroup；selftest 90 条
- DAEMON Windows 本地服务 → daemon/（9 文件，单文件 jar 8.0 MB）+ Web UI 接真实数据；管理层复跑 m1-windows.sh：36/36 PASS（双 daemon、32 MiB、SHA 一致、WS 推送、shutdown 干净）
- M1.1 协议内核修正 → join.ok 独立签名域、file.list 条目签名、公钥随 join.ok/member.name 分发、时延改本地口径、单帧失败不再断链；管理层复跑 25 CHECK PASS
- C2 真机 M1 闭环 → scripts/m1-device.sh（26 checks）；管理层复跑 --quick：PASS 19 checks；Android↔桌面 32 MiB 双端 SHA 一致、评论双向、Keystore 包裹 keySeed、无 Scudo
- P2 协议补齐 → docs/design/protocol.md（388 行）：hello 两阶段、join.ok 签名域、file.list 条目签名、公钥分发；新增 §13 实现偏差登记 9 条
- H1 验收装置加固 → scripts/m1-headless.sh（25 条 CHECK、runId 锚定、反射读 libtorrent 真值）；管理层复跑：正常 PASS，precopy 与 dht_on 变异均 FAIL
- N1 NAT 穿透研究 → docs/design/nat-traversal.md（279 行，libtorrent 源码级核实；含 7 条待拍板）
- R1 审计 → docs/reviews/m1-harness-audit.md：结论「有条件可信」，两条高危（dht 断言可绕过、传输来源无断言）
- C1 M1 无头内核 → core/m1/（14 文件 3750 行）+ harness；管理层复跑 PASS（20s，14 CHECK，mDNS 加入、32MiB、SHA 一致、评论 5ms、dht=0）
- U1 视觉修正（暂停待续）→ 深玉青主题两套、P0 自检开屏、P7 只读状态、P2 层级；P0 四态截图待 core 编译通过后补
- S1 Android 骨架 → 提交 30166de（43 文件）；APK 11.7 MB，两台真机安装启动 0 crash

- K2 arm64 .so 可复现构建 → tools/build-libtorrent-android/（6 次构建，canonical sha256 97d2663a…，13.36 MB，3659 个 JNI 符号与旧件相同）
- D2 协议 v0 → docs/design/protocol.md（349 行，20 个消息类型，M1 15 个）
- A1 旧项目考古 → docs/recon/legacy.md；品牌资产 → assets/brand/
- A2 调试链路 → tools/devkit + docs/tooling/android-verify.md（两台真机实测）
- A3 技术选型 → docs/research/tech-options.md
- D1 产品规格 → docs/design/product-spec.md（P0–P7）
- K1 内核 spike → spikes/k1-jvm-kernel/（管理层复跑 PASS：v1/v2 各 10 轮，SHA 一致，dht=0）
- S2 Windows 壳 → windows/build/Huchuan.exe（236 KB，单文件绿色版，WebView2 渲染截图已验）

## 里程碑

| 阶段 | 内容 | 状态 |
| --- | --- | --- |
| M1 | 私有闭环：建群 → 后 8 位加入 → 共享 → 下载 → 评论；Android↔桌面；崩溃压力 | **已达成**（android-android 待第二台设备） |
| M1.5 | ~~Windows daemon + Web UI 接后端~~（已完成）；JRE 打包、系统分享入口、OPPO 回归、targetSdk 37 | 进行中 |
| M2 | 组队加速、通知三档、限速 UI、tracker 跨网、UPnP 窗口、链接分享与分享管理 | 待排 |
| M3 | 公开种子社区、UDP 打洞、大文件磁力加种 | 待排 |

## M1 定义

两台真机跑通：建群 → 输后 8 位加入 → 共享文件做种 → 对方下载 → 评论双向可见；下载后两端哈希一致；连通性用 socket/日志断言，不看 UI 文字。

## 已知风险

- F1 已消：arm64 .so 两次独立构建字节一致；但 Scudo 崩溃是否修好仍未在 Android 13 真机验证
- K1 只在同机 loopback 验证，跨机/跨安卓未验
- K2 的 Linux CI 分支未实机验证（只在 Windows/MSYS 跑过）
- spikes/ 被 .gitignore 忽略，K1 代码暂不进库（待定归档策略）
- docs/build.md §6 的 P0 文案对照仍是旧版，需另派单更新
- app 依赖 core，core 大改期间 APK 出不来（排期约束）
- OPPO 撤走后，M1 真机验收改为「MIX + 桌面 JVM 对端」；API 37 权限与 1440x3168 布局的验证全部挂起
- harness 的 Probe 靠反射读 core 私有字段，core 一改名就整轮红；需 core 补 2 个公开读口
- 跨设备评论延迟用对端 ts 计算，时钟不同步会出现负值（实测 -1907 ms）；改为本地接收时刻
- devkit 缺 input text 与 launch --extra，真机脚本仍需直调 adb
- P7 全局限速与通知档只存设置，未接内核
- core 有 8 条缺口（退群/重做种/成员做种数/未读数/组队/评论删除/下载目录可指定/tracker 状态），UI 上对应「没有未读角标、没有组队人数、别人做种数显示 0」
- Windows 端「添加文件」现在是绝对路径输入框，不是系统文件选择器
- Windows 未打包 JRE，目标机需装 JDK 17+
- keySeed 目前明文落 state.json，上真机前必须换成 Keystore 包裹
- magnet 加种路径未实现：M1 靠 file.add 内联 .torrent，大文件会超过 47 KiB 帧上限
- protocol.md §13 登记了 9 条实现偏差；其中 2/3/4（join.ok 验签、file.list 签名、公钥分发）要等 C2 落地后同批改，避免两端不兼容
- Windows 占位页按钮是蓝色 #2f6bff，违反品牌基准，接正式 UI 时必须改
- libtorrent 许可声明缺失；旧图标无 512 PNG

## 待决策

- 自带 tracker 部署位置（M2）
- 公开种子社区来源合规（M3）
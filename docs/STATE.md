# 状态

更新：2026-11-06 · 管理层

## 阶段

目标已交付（2026-11-07）；里程碑表按下文实际状态，剩余项见「里程碑」与「待决策」

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
- libtorrent4j 许可为 MIT（Maven POM 与源仓 LICENSE.md 均为 MIT），旧文档写成 BSD-3 的一律以 MIT 为准
- 默认 tracker 暂保留项目自带实例；README 已声明其为共享演示实例、额度有限、可关闭，用户自建见 docs/deploy-cloudflare.md
- 跨网仍未打通：ip_filter 只放行私网段，需并入群成员公网地址（M2 首项）
- P7 文案口径定为「重启后生效」（规格为准，Android 的「下次启动生效」待改）
- Cloudflare Worker 名 huchuan-relay，账号 autoai233@gmail.com（f72eb499…），免费 workers.dev 子域，SQLite DO 迁移
- tracker 群注册是先到先得（TOFU），无账号体系；passkey 每小时轮换，客户端需每小时重新注册
- 组队只做通告（队友数读 kernel.teammates），不做显式分片、不用 0x53 调度
- super-seeding 不默认开启（省字节不省时间，小文件负收益）；若判据改为省流量可再评估，前提是修队友数口径
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

（无。目标项全部落地，见下）

## 已完成
- 链接分享（W1 接收页 / 内核 link 层 / Android P8+P9 / Windows daemon 适配）→ 两端 8 MiB 端到端、浏览器独立 SHA 一致；线上发版待 wrangler 重新授权
- 系统分享入口（ACTION_SEND）→ 真机六格；设计稿的透明 Activity 方案被真机推翻，改挂 MainActivity
- 跨网窗口（UPnP/NAT-PMP）→ 44 条断言，本机实测映射成功
- E2E 公开种子端到端 → A 发布 32 MiB、B 纯磁力经 pubBtPort 取元数据并下载、SHA 一致；变异 16 条红（manager 复跑 PASS/FAIL）
- APPPUB P4 公开到群外/取消公开 → 真机 27 条 CHECK
- 交付物：app-debug.apk（26,195,145 B）、dist/Huchuan（35.12 MiB，离屏截图可渲染）
- 最终回归：m1-headless PASS / e2e-public PASS / m1-windows PASS（全部在最后一次代码变更之后跑的）
- PUBLISH 公开到群外 → 另建 private=0 v1 种子并做种、magnet、幂等、可取消；自检 376 条，反向变异 19 条红
- WINPUB Windows 公开种子 UI → 离屏 19 张 + CDP 真实点击链路断言
- 崩溃修复 → AlertType 表补洞（真机 filled=105,106,107）+ 公开会话收紧 mask；6 分钟存活、无 FATAL
- 模拟器评估 → 装好但缺 WHPX/AEHD，需管理员+重启；x86_64 镜像含 ARM 翻译层，但局域网发现过不了 slirp
- OSS 开源材料 → LICENSE(MIT) + THIRD_PARTY_NOTICES(13 项逐个核实) + README(76 行)
- TV tracker 接线完整验收 → 47/47 PASS（3m40s）；确认此前 21/2 是单独跑 phase b 的假失败
- TEAMVERIFY 组队通告真机验证 → MIX 上 9/9 PASS：P3「组队 2 人」、P4「正在组队 · 2 人」、100% 后消失、logcat teammates_changed count=1
- PUB 公开种子社区设计 → docs/design/public-swarm.md（240 行，独立 PublicBtSession，六条已拍）
- WORKER Cloudflare 服务 → 已部署 https://huchuan-relay.autoai233.workers.dev（40/40 本地测试；线上 GET 经代理验证 /health 与 bencode 失败路径；正向注册+announce 因本机 workers.dev 被 DNS 污染/SNI 拦截未能实测）
- CORETEAM 拨号缺陷与 E0 → hasPeers 短路修复（C1 向 C2 上传 183 MiB）、duplicate_link 竞态修复（mutant 自证）、kernel.teammates 读口、E0 D=1.1394 → 不做显式分片
- SUPERSEED 对照 → super-seeding 能消掉重复上传（D 1.09→1.00），但 T_all 只改善 3.1%、小文件慢 59%、污染队友数 → 不默认开启
- APPP6 P6 在线状态 → peersChanged 驱动、离线灰显、真机 39 checks PASS
- RECONNECT 重启重连与完成快通道 → PeerReconnector（退避/并发/去重）、torrent_finished_alert 快判；headless 与真机 --quick 均 PASS，两次反向验证证明断言可红
- SPEC 组队口径对齐 → 队友数改为观测口径（实际互连），P2 去掉群级组队数，M2 验收改为记录 D 与 T_all
- TEAM 组队研究 → docs/design/team-acceleration.md：显式分片收益 ≤0.6%，只做通告；发现 hasPeers 短路缺陷
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
| M1 | 私有闭环：建群 → 后 8 位加入 → 共享 → 下载 → 评论；Android↔桌面；崩溃压力 | **已达成**（android↔android 未验：第二台设备已撤走） |
| M1.5 | Windows daemon + Web UI、JRE 打包、targetSdk 37 | **已达成** |
| M1.5 | 系统分享入口（ACTION_SEND） | **未做** |
| M1.5 | OPPO 回归（API 37 / 1440x3168 定性） | **挂起**（设备已撤） |
| M2 | 组队加速（通告）、通知三档、限速 UI、tracker 客户端与 pages.dev 入口 | **已达成** |
| M2 | UPnP/NAT-PMP 跨网窗口 | **未做**（设计在 docs/design/nat-traversal.md） |
| M2 | 链接分享与分享管理（P8/P9 + Worker 信令） | **未做**（设计在 docs/design/link-share.md，Worker 已部署） |
| M2 | 跨网真实传输实测 | **未验**（tracker 注册已通，两台设备隔公网未传过文件） |
| M3 | 公开种子社区（内核 + 双端 UI + E2E） | **已达成**（E2E 为同机回环） |
| M3 | 大文件磁力加种 | **已达成**（磁力导入替代内联 .torrent） |
| M3 | UDP 打洞 | **未做**（基线里排 M3 评估） |

**为什么停在这里**：本次自动运行的目标（goal）只覆盖到 M2 的组队/通知/限速与 M3 的公开种子，这些完成即收口；上表剩余四项都不在该目标内，且各自有明确的阻塞或成本：

- 系统分享入口：纯 Android 工作量，无阻塞，只是没排进目标。
- OPPO 回归：设备已撤走，无法进行；API 37 的既有证据取自撤走前的 24 条断言。
- UPnP 窗口与跨网实测：需要能跑通 pages.dev 的异地环境与两端 NAT 组合，本机做不了。
- 链接分享：Worker 信令已部署并验过 8/8，客户端一行未写，是一个独立功能（约等于 P8+P9 两页）。

## M1 定义

两台真机跑通：建群 → 输后 8 位加入 → 共享文件做种 → 对方下载 → 评论双向可见；下载后两端哈希一致；连通性用 socket/日志断言，不看 UI 文字。

## 已知风险

- 安卓模拟器：SDK/镜像/AVD 已装，但本机 WHPX 未启用、AEHD 未装，且会话无管理员权限 → 需 L0 以管理员开启并重启后才能复跑

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
- E0 只跑一轮，D 与 1.00 的区分度不足；super-seeding 两臂各 2 轮，区间重叠
- duplicate_link 竞态在真机半开连接路径未自动化覆盖
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
# 跨网直连（NAT 穿透）研究与分层策略

- 版本 nv1 · 2026-11-06 · 执行层研究 agent（只读）· 唯一产物：本文件
- 适用：M2 跨网档（私有群 + 公开群同一套机制）
- 输入：`docs/design/protocol.md`、`docs/research/tech-options.md`、`AGENTS.md §9`、旧项目 docs/25·42·44·47·66·67·68·69·70·74·76·77·78、`reference/Natter`（v2.2.1）
- 证据口径：libtorrent 源码（`RC_2_1`，2026-11-06 抓取，行号可复核；本机 dll 内版本串 `2.1.0.0`）> 本仓/旧项目实测 > 文献 > 推断（标「估」）。标「待验证」的，动手前必须先验。

## 0. L0 硬约束（本文不再给反例）

1. TURN / 任何数据中继永久排除（无带宽预算；Cloudflare TURN 开通要绑卡，旧项目 docs/25 实测）。
2. 云服务只用真正免费、无需绑卡：Cloudflare Workers 免费层可用（100k 请求/日，developers.cloudflare.com/workers/platform/limits）；TURN 不可用。
3. 私有群禁用 DHT / PEX / LSD（会把 infohash 与 IP 广播到公网）。

问题因此收敛为：**在 1–3 之下，把「能连上的比例」和「连不上时的可解释性」同时做到最好。**

## 1. 结论速览

1. 跨网能不能通，只由一件事决定：**至少一端在目标传输上可被入站，或两端都能被同一套打洞流程打穿**。与「谁在分享」无关（§10）。
2. 成本最低、成功率最高的一档是**端口映射**（UPnP / NAT-PMP / PCP）。libtorrent 2.1 三种都有，绑定层能调（§3）。
3. **BEP 55 打洞在本项目永远不触发**（private 种子没有 PEX 插件，源码级确认，§3），不要把它算进阶梯。
4. 我们自己的消息层（TCP+TLS 47100）比 BT 数据面更适合做穿透：协议、端口、同时打开、保活都由我们写（§11）。
5. 两端都在对称 NAT / CGNAT 后且没有全局 IPv6 时**物理上不通**。如实告知是唯一正确结果（旧项目 docs/74 决定四）。

## 2. NAT 类型 × 穿透手段矩阵

术语先钉死（IETF 的「映射/过滤」二分比锥形说法精确；BEHAVE `draft-ietf-behave-p2p-state` §2 明说锥形术语是混乱来源）：

| 维度 | 取值 | 含义 |
|---|---|---|
| 映射 | EIM（端点无关）/ EDM（端点相关，即「对称」） | 同一内网源端口访不同目的地，公网映射端口是否相同 |
| 过滤 | EIF（端点无关）/ ADF / APDF（端口相关） | 外部哪些源地址能打进来 |

| NAT 类型 | 能被什么手段打穿 | 依据 |
|---|---|---|
| 全锥（EIM+EIF） | UDP 打洞必通；TCP 同时打开必通；可叠加端口映射；Natter 式「钉住映射」成立 | BEHAVE §3.4–3.5；Natter 只在 full-cone 下成立（`natter.py:1796` 映射一变就告警） |
| 受限锥（EIM+ADF） | UDP 打洞通（双方各发一包，各自的出站包打开自己的过滤器）；同时打开通 | 同上 |
| 端口受限锥（EIM+APDF） | 打洞通，但必须「两端几乎同时发包」，先到的包被丢 | 同上 |
| 对称（EDM） | 基本不通：给对端的映射端口 ≠ 给 rendezvous 的映射端口。只剩端口预测/生日扫描（低概率） | BEHAVE §3.5 逐条列出失败条件：端口非顺序分配、被别的会话抢占、双层 NAT |
| CGNAT（运营商双层 NAT） | 上述手段再打一折：外层 NAT 归运营商，映射协议到不了；若为 EDM 则等同对称 | 旧项目 docs/77：蜂窝 IPv4 为 `10.71.217.225/32`，两个 STUN 服务器都拿不到映射 |

一句话判据：**锥形（EIM）能被外部打穿，对称 / CGNAT 只能自己拨出去。** 两两组合的成功率见 §12。

## 3. libtorrent 2.x 实际有什么（源码级实核）

对象：`arvidn/libtorrent` `RC_2_1` 源码 + 本机 `libtorrent4j 2.1.0-39` 制品（jar 内符号、javap、dll 内字符串）。

| 能力 | 有没有 | 证据 | 可用性 |
|---|---|---|---|
| UPnP-IGD 客户端 | 有 | `src/upnp.cpp`；`settings_pack.hpp:866 enable_upnp`；结果走 `portmap_alert` / `portmap_error_alert` | Java 侧有 `PortmapAlert` / `PortmapErrorAlert` |
| NAT-PMP | 有 | `src/natpmp.cpp`；`settings_pack.hpp:877 enable_natpmp`；`natpmp_gateway` 可指定网关（`:399`） | 同上 |
| **PCP（RFC 6887）** | **有**，藏在 natpmp 模块里 | `natpmp.cpp:139-141`「assume servers support PCP and fall back to NAT-PMP」，`m_version = version_pcp`；`:455-470` 构造 PCP MAP；`:46 pcp_error_category` | 无独立开关：开 `enable_natpmp` 即先试 PCP |
| uTP | 有，且**当前默认开着** | `src/utp_socket_manager.cpp`、`src/utp_stream.cpp`；`settings_pack.hpp:661-662`；本仓 `BtSession.kt` 未关 | 与旧项目「关 uTP」记载不一致，见 §13-5 |
| LSD（BEP 14） | 有 | `src/lsd.cpp`；`settings_pack.hpp:883 enable_lsd`；本仓已关（`BtSession.kt:205`） | 私有侧必须保持关 |
| BEP 55 holepunch | **有实现，但本项目永不触发** | `bt_peer_connection.cpp:2488` 无条件宣告 `ut_holepunch`、`:1489 on_holepunch()`；触发点 `peer_connection.cpp:4287-4293` 要求 `supports_holepunch` + `find_introducer`；而 `ut_pex.cpp:605` 对 `priv()` 种子直接 `return {}` | `settings_pack` 无开关；jar 内 `grep -ril holepunch` 零命中 → **无可调用接口** |
| WebRTC / WebTorrent 传输 | 有（2.1 新增） | `src/rtc_signaling.cpp`、`src/rtc_stream.cpp`、`settings_pack.hpp:403 webtorrent_stun_server` | 只服务 WebTorrent 浏览器 peer，需 `wss://` tracker 信令；libtorrent4j 只暴露 STUN 字符串，**不能用于原生↔原生穿透** |

绑定层两个事实，直接决定实现方式：

- `org.libtorrent4j.SettingsPack` 只有 69 个公开方法，**没有** `setEnableUpnp` / `setEnableNatpmp`。必须走原始枚举：`s.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)`（本仓 `BtSession.kt:207-208` 关的时候就是这么写的）。
- `SessionManager.externalAddress()` 可用，配合 `ExternalIpAlert` / `PortmapAlert` 能拿到「路由器把端口映射成了什么」。这是可达性上报的现成数据源（§10.1）。
- **默认值实测**（2026-11-06，本机实跑 `SettingsPack.defaultSettings()`，libtorrent4j 2.1.0-39 + libtorrent4j-windows 原生库）：`enable_incoming_utp` / `enable_outgoing_utp` / `enable_upnp` / `enable_natpmp` / `enable_lsd` / `enable_dht` **全部 true**，默认 `listen_interfaces = 0.0.0.0:6881,[::]:6881`。结论：关是我们自己加的硬约束（`BtSession.kt:204-209`），**开只是一个布尔值，零库改造**。

## 4. IPv6 直连

| 场景 | 路径 | 结论 |
|---|---|---|
| 双方都有全局 v6（蜂窝 + 已开 v6 的家宽） | 各自 listen `::`，交换 `[v6]:port`，直接 TCP 连 | 无 NAT、无映射，**链路上最快最稳的一档** |
| 只有一端有全局 v6 | 有 v6 的一端拨 v4-only 对端的公网 v4（若其可入站），否则不通 | v6 无法「反向」到达 v4-only 主机 |
| 两端有 v6 但 CPE 默认拒入站 | SYN 被丢，看不到任何回应 | RFC 6092 要求 CPE 默认 stateful 过滤、拒未请求入站（REC-6 等）。**有全局 v6 ≠ 可被连入** |
| NAT64（v6-only 接入访问 v4 服务） | 只影响「我们访问 v4 目的地」这一半，不影响 v6↔v6 | 不构成穿透能力 |
| 464XLAT / CLAT（手机侧 v4 走翻译） | CLAT 给的是运营商侧私网 v4，语义等同 CGNAT | v4 打洞难度按 CGNAT 算；v6 侧无法呼叫 v4-only 主机 |

实测锚点（旧项目 docs/44 §4.1）：OPPO 蜂窝有 `240e:476:7041:777::/64` 全局 v6，家里 Windows **没有全局 v6**。所以首批真实用户里「双 v6」并非默认成立，v6 档要按「碰运气命中」设计，不能当主路径。

工程结论：v6 档**成功判据只能是「拨过去通了」**，不能靠「我有全局地址」提前宣布可用。代价极低（一次 TCP 连接、3 s 超时），所以排阶梯第二档。

## 5. WebRTC 无 TURN 的边界

去掉 TURN 后候选只剩 host 与 srflx（加检查产生的 prflx）。**没有 relay 候选 = 没有兜底**，任何「两端不可互达」的组合直接 failed。

| 约束 | 事实 | 证据 |
|---|---|---|
| host 候选被 mDNS 混淆 | Chrome / Edge / Firefox / Safari 都把本地 IP 换成随机 `<uuid>.local` | bloggeek.me《WebRTC mDNS》(2019)，四浏览器均做；规范草案 `draft-ietf-mmusic-mdns-ice-candidates` |
| 原生端解析不了 mDNS 候选 | 网页↔原生托盘的局域网直连**永不成立**，只能走公网反射 | 旧项目 docs/47 §4.5（实测定性结论） |
| STUN 被拦 = 零 srflx | OPPO 的 Chrome/Edge 在 WiFi 与 4G 下都**只有 host 候选、一个 srflx 都没有**（Cloudflare/Google 双零） | 旧项目 docs/42 §附2（CDP 抓真实候选） |
| 本地网络权限能一票否决 | iOS 14 拒绝本地网络权限 → ICE 失败；macOS 26+ 只要创建 `RTCPeerConnection` 就触发该权限（因 mDNS） | issues.webrtc.org/42221977；w3c/webrtc-pc#3109 |
| Android 17 更狠 | `ACCESS_LOCAL_NETWORK` 默认拒绝，连第三方浏览器的 STUN/UDP 采集都掐 | 旧项目 docs/47 §4.5、docs/42 §附2（同机系统浏览器豁免、秒连成功） |

ICE 失败矩阵（无 TURN）：

| A \ B | host（需 mDNS 可解析） | srflx（锥） | srflx（对称/CGNAT） |
|---|---|---|---|
| host | 仅同网段可能通 | 通（同 NAT 需 hairpin） | 不通 |
| srflx（锥） | 通 | 通 | 不通 |
| srflx（对称） | 不通 | 不通 | 不通 |
| 无 srflx（STUN 被拦） | 仅同网段 | 不通 | 不通 |

Chrome 与 Safari 的差异（只列会影响我们的）：

| 项 | Chrome / Edge | Safari | 状态 |
|---|---|---|---|
| host 候选 mDNS 混淆 | 是（Chrome 76+） | 是 | 已验证（bloggeek） |
| 本地网络权限 | 无此权限（Android 侧由 `ACCESS_LOCAL_NETWORK` 管） | iOS 14+ / macOS 26+ 有，拒绝即 ICE 失败 | 已验证（两个 issue） |
| 入站能力 | 无（浏览器不监听） | 无 | 平台性质 |
| 纯 DataChannel 场景的候选采集 | 稳定 | 历史有「不加媒体流就采不到候选」，2018 SO 帖 | **待验证** |

产品含义：网页只出现在链接分享里，且浏览器永远是 guest。**无 TURN 时链接分享的可用范围 = 原生端可入站，或两端同网段且 mDNS 可解析。** 后者在我们这套（原生端解析不了浏览器 mDNS）基本不成立，实际只剩前者。

## 6. 我们自研的可能

### 6.1 用自己的消息层做信令 + UDP 打洞

三样东西缺一不可：

1. **一条与数据面无关的信令路**：打洞时得交换「我看到的你的地址」。候选是 Cloudflare Worker（免费层，一次连接尝试约 4–6 次请求）、mDNS（同网段）、手动 IP:port。**不能拿消息层自己当信令**——消息层正是要打通的那条路（先有鸡还是先有蛋）。
2. **能自由收发的 UDP socket**，且与保活/打洞同端口。
3. **两端几乎同时发包**（时间对齐）。工业做法是借信令往返做 RTT 对齐（DCUtR 用高精度 RTT 同步）。

成功率：文献基线 **70% ± 7.1%**（arXiv 2510.27500 / 2604.12484；440 万次 DCUtR 尝试、85,000+ 网络；TCP 与 QUIC 无差别；97.6% 首次尝试即成）。它统计的是「打洞阶段」，前提是两端已能交换地址；我们的信令由 Worker 承担，打洞本身不依赖数据中继，所以这个数字可当上界参考。我们更差的地方：旧项目实测**蜂窝下两个 STUN 服务器都拿不到映射**（docs/77 §二）——UDP 若被运营商拦，本档归零。

### 6.2 TCP 同时打开

- 机制成立：BEHAVE §3.4 明确「多数 NAT（>50%）是 EIM 且不回 RST，同时打开在多数尝试中能成」。
- 硬条件：两端 SYN 必须在各自 NAT 建好会话后、对端映射过期前相互到达；**出站 socket 必须绑在监听端口上**（否则对端拿到的映射端口对不上）。
- 我们能不能做：消息面可以（自己写 socket，`SO_REUSEADDR`/`SO_REUSEPORT` 同端口共存）；**BT 数据面基本不行**——libtorrent 的监听 socket 是否允许我们另开同端口出站连接，未验证（docs/78 §五 已列为关键未知项）。
- 失败率：抖动、双层 NAT、对端 NAT 处于 end-of-session 超时态都会失败（BEHAVE 逐条列出）。按「能成就赚、不成不亏」的附加档处理，不进主阶梯。

### 6.3 端口预测（Natter / N+1 / 生日扫描）

- Natter **自己不做预测**：全仓 `predict|birthday|simultaneous` 零命中；它只在 full-cone 下把映射钉住（`natter.py:252` STUN 探测 → `:338` 保活 → `:1796` 再探测比对）。
- 预测只在「NAT 顺序分配端口」时成立；BEHAVE §3.5 已把失败条件写全（端口被占跳过、被同 NAT 其它会话抢占、双层 NAT）。
- 判定：**不做成一档**。它要么无效，要么把客户端变成端口扫描器，与「信息不能泄露」的前提冲突。最多作为诊断页的高级探测，必须限速、限次数、写进诊断日志。

## 7. UPnP / NAT-PMP 的隐私代价，以及 AGENTS §9 要不要改

代价是真实的，不是理论：

- 映射建立后**公网任何人都能扫到这个端口**。旧项目已有结论：`任意人可探测，持有 infohash 者可下载`（docs/69 §二），而且那还是「只在传文件时开」的情况下。
- IGD 本身是漏洞高发面（CallStranger，CVE-2020-12695，影响面以十亿设备计）。

能压到什么程度（按性价比排）：

| 措施 | 效果 | 成本 |
|---|---|---|
| 只在传输窗口内映射，结束立即撤销 | 暴露窗口从「永久」降到「分钟级」 | 极低（mapping 自带 lease：`natpmp.cpp:452-454`，未配置时回退 3600 s，要调小） |
| 随机高位端口（不复用 51413 / 47100 固定值） | 去掉固定指纹，全端口扫描成本高一个数量级 | 极低 |
| 只接受已知 infohash 的 BT 握手（已有） | 不知道 infohash 连不进 swarm | 已有 |
| 消息层 hello 验签 + 非成员只允许 4 种帧（已有） | 端口被扫到也进不了业务 | 已有 |
| 非白名单 IP 直接拒绝（`ip_filter` 白名单） | 从「可探测」降到「只对群成员可见」 | 中（§13-2） |
| 连接数与新建速率上限 | 抗扫描、抗误用 | 低 |

**AGENTS §9「私有侧关 UPnP / NAT-PMP」建议改**。理由不是「开着更方便」，而是：关掉等于主动放弃**成本最低、成功率最高**的一档（不需要任何中继、不需要任何服务器），而跨网档的价值全在这一档。风险用上表五条压到「分钟级窗口 + 随机端口 + 只认已知 infohash + 白名单」。这条要 L0 拍板（§14-1）。

## 8. 最终分层策略（fallback 阶梯）

顺序与 `docs/STATE.md` 已定的一致：局域网 → IPv6 → 公网 IPv4 → 端口映射 → STUN 打洞 → 如实告知。总预算 20 s，逐档超时，任何一档成功即停。

| # | 档 | 何时尝试 | 成功判据 | 失败降级 |
|---|---|---|---|---|
| 1 | 局域网直连 | 候选表里有 `lan` 来源（mDNS 命中） | 2 s 内 TCP 建连且 hello 验签通过 | → 2 |
| 2 | IPv6 直连 | 双方都上报了全局 v6（非 ULA/链路本地），或本机有 v6 且对端上报 v6 | 3 s 内 TCP 握手成功且 hello 通过 | → 3 |
| 3 | 公网 IPv4 直连 | 任一端上报「我在公网」（Worker/tracker 回显源地址 == 本机地址且非私网） | 3 s 内 TCP 成功 | → 4 |
| 4 | 端口映射后直连 | 路由器支持且映射成功（`PortmapAlert` 拿到 extern port + 公网 IP） | 对端 4 s 内连上映射端点；传完或超时即撤销映射 | → 5 |
| 5 | STUN 打洞（UDP） | 前四档全失败，且两端都拿到了 srflx | 8 s 内双向收到对端 UDP 探针（各发若干包，收到即锚定） | → 6 |
| 6 | 如实告知失败 | 总预算用完或第 5 档失败 | — | 终态 `FAILED_NO_PATH`，见 §10.4 |

成本排序理由：1–3 零成本（不需路由器配合、不需新代码路径）；4 需要路由器配合但不需新传输；5 需要新增 UDP 能力与 Worker 信令对齐，是唯一有实现风险的档。成功率排序见 §12：4 与 1–3 同档（高），5 明显更低。

## 9. 旧项目在这块的结论与教训

| 结论 | 出处 |
|---|---|
| BEP 55 内核有、绑定层无接口、private 无 PEX → 永不触发 | docs/67、docs/70 §二 |
| qBittorrent 零行打洞代码，靠的是「swarm 里总有公网 peer」 | docs/68 §一 |
| Natter 只暴露本机端口，连不起两个 NAT 对端；蜂窝 CGNAT 救不了 | docs/68 §二、docs/78 §三 |
| 不做 TURN，极端情况如实告知 | docs/74 决定四 |
| 首次跨网实测 FAIL（蜂窝 CGNAT，全程 `peers=0(0)`），蜂窝下 STUN 不可用 | docs/77 |
| 所有吞吐数字都是同一 AP 下的（BT 25.4 MB/s、DataChannel 7–10 MB/s），不能当跨网指标 | docs/66、docs/69 §一 |
| 网页↔原生的局域网直连永不成立（浏览器 mDNS 候选） | docs/47 §4.5 |

**它为什么把 UPnP 关掉**：旧项目在两端把 `enable_upnp=false` / `enable_natpmp=false` 写死（Android `BtEngine.kt:47-48`、Windows `bt_settings.cpp`），当时的说法是「不开放任何端口」。但 docs/70 §七 自己复盘时写明：这是执行 agent 自加的字面要求，用户从未这么说，用户的要求是「开放端口可以，但必须有认证，不是谁都能连」。

**后来遇到了什么**：关掉之后跨网只剩手动 IP:port，而蜂窝侧是 CGNAT，这条路也不存在。结果是 docs/77 的首次跨网实测 FAIL（全程 `peers=0(0)`），旧项目 R5-1「跨网穿透机制」验收 FAIL——UPnP、NAT-PMP、uTP、DHT、打洞、IPv6、TURN 全部关闭或不存在（docs/research/tech-options.md §3.2 引 STATUS.md 原文）。也就是说：这一档被关掉，等于把「家宽自动映射」这个唯一免费的跨网可能性一起关掉了。

三条教训，写进规则：

1. **「这是设计」不是理由**——把库的行为当产品决策（UPnP 关着、端口全开）是上一轮明确认过的错（docs/70 §七）。
2. **先测 NAT 类型再决定路线是反模式**：正确做法是机制先实现、运行时逐档自检、失败自动降级（docs/74 决定五）。
3. **同一 AP 的数字不能外推**：跨网瓶颈是上行带宽（20–100 Mbps 上行 → 2.5–12 MB/s），局域网 25 MB/s 与 7–10 MB/s 的差距在跨网时基本消失。

## 10. 拨号方向策略（L0 补充要求）

原则：**连接方向由「可达性」决定，不由「谁在分享」决定。** 分享方向只决定业务语义（谁有文件），与谁发起、谁监听无关；BT 连接建立后两个方向都能传。

### 10.1 每层的主动方与判据

| 层 | 谁发起 | 「我可达」的判据（机器可算，不问用户） |
|---|---|---|
| 局域网 | deviceId 字典序小者主动（沿用 protocol §5 已有规则，避免双连） | 收到 mDNS SRV 即视为局域网可达 |
| IPv6 | **不可达方拨号**，可入站方只监听 | 有非链路本地、非 ULA 的全局 v6；Worker 回显源地址 == 本机地址；入站能力**不靠猜**，由对端一次 3 s 拨号证明 |
| 公网 IPv4 | 同上 | Worker / tracker 回显源地址 == 本机地址，且不在私网段 → 我在公网 |
| UPnP / NAT-PMP-PCP | **映射成功方监听**，对端拨号 | `PortmapAlert` 给出 extern port 且 30 s 内仍有效；`ExternalIpAlert` 的公网 IP == Worker 回显 |
| STUN 打洞 | **双方同时发**（打洞本质对称，没有「谁拨」） | 双方都拿不到可达端点时才进本档；用信令做 RTT 对齐，两端同时向对方 srflx 端点发包 |

双方都可达时（都公网 / 都映射成功）：deviceId 小者拨，另一方只监听——避免双拨与重复连接。tracker 回显源地址是除 STUN 之外最便宜的「我看起来是什么样」：它只告诉你公网 IP（TCP 的源端口是这条连接的临时端口，不能当入站端口用），据此可判断「我是否直接在公网」「我是否在 CGNAT」。

### 10.2 BT 数据面怎么实现

libtorrent 侧只有两个动作，其余都是我们自己算：

1. **保持监听**：`listen_interfaces` 已是 `0.0.0.0:port`（`BtSession.kt:211`），可达方不用改。
2. **注入端点让对端拨**：`connect_peer(TcpEndpoint(host, port))`（`BtSession.kt:145`，走 swig，高层 API 没有）。不可达方拿到的 `host:port` 必须是**对端映射后的公网端点**，不是内网地址——这正是 `peer.announce` 现在缺的字段（§13-4）。
3. **不依赖 libtorrent 自己发现**：DHT/PEX/LSD 全关，peer 表只能由我们注入。所以「拨号方向」完全由我们的信令决定，libtorrent 只是执行者。

### 10.3 WebRTC 链路的特别之处

- 浏览器**不能被动接受入站**（没有监听 socket），「谁可达」这一维在浏览器侧不存在：永远由原生端提供可入站端点，或两端都靠 srflx。
- 但 ICE 连通性检查是**双向**的：任一方发起 check 都可能打穿（只要两端发包窗口对上）。所以 WebRTC 的拨号方向不决定数据方向，只影响候选采集与信令顺序。
- 与 BT 的差别：BT 是「一端监听 + 一端 connect」，非对称；ICE 是「两端各有候选集 + 两端都发包」，对称。因此 BT 缺一条入站路就死，ICE 缺一条还有 srflx↔srflx 的可能。
- 实践含义：链接分享不能照抄 BT 的「谁可达谁监听」，两端都要做候选采集；而无 TURN 时只剩 srflx，srflx 又依赖 STUN 放行（§5 实测坑）。

### 10.4 全部失败时的用户可见结果

- 状态机终态 `FAILED_NO_PATH`：不转圈、不无限重试。自动重试只做一次，之后给手动「重试」。
- 文案只写「是什么 / 会怎样」：
  - 主界面：「双方网络无法直连」
  - 诊断（P7 只读状态行 + 设置里的诊断页）：「已尝试：局域网 / IPv6 / 公网 IPv4 / 端口映射 / 打洞；失败原因：对端无全局 IPv6、本机 CGNAT、路由器不支持端口映射」
- 不允许「正在连接…」超过总预算（§8 的 20 s），预算到就出结论。

## 11. 两条通道分别受什么影响

| 维度 | BT 数据面（libtorrent 监听端口） | 消息面（自研 TCP+TLS 47100） |
|---|---|---|
| 谁决定端口 | libtorrent（`listen_interfaces`，现为 `0.0.0.0:port`） | 我们自己（47100–47199 顺延，`PeerServer.kt:38`） |
| 端口映射 | 开 `enable_upnp` / `enable_natpmp`（含 PCP），结果看 `PortmapAlert` | 需自己实现 SSDP / NAT-PMP / PCP 客户端（约 300–500 行），或复用自己的映射结果 |
| 打洞 | **不推荐**：libtorrent 的 socket 我们碰不到；BEP 55 永不触发 | **可做**：自定义 UDP socket + 信令 + 同时发包 |
| 同时打开 | 不可行（拿不到监听 socket 控制权，未验证） | 可行（自写 socket，同端口 REUSEADDR/REUSEPORT） |
| mDNS | 已有（自研 `Discovery.kt`，与 BT 无关） | 同左（SRV 指向消息层端口） |
| 加密 | MSE/PE（RC4 优先，旧项目验收口径 `rc4>=1 且 plain=0`） | TLS 1.2+，套件白名单已定（protocol §5） |
| 身份 | 只认 infohash（BEP 27 + 白名单） | hello 签名 + chanId 绑定（protocol §1.2） |
| 隐私面 | 端口开放 = 任意人可探测；未知 infohash 连不进 swarm | 端口开放 = 任意人可探测；非成员只能发 4 种帧 |

结论：**跨网穿透的能力上限在消息面，不在 BT 面。** BT 面只能做「端口映射 + 让对端 `connect_peer` 拨过来」；打洞、同时打开都得在消息面做，做成之后再考虑把 BT 字节搬过去（或干脆用消息面做隧道）。

## 12. 现实成功率（工程估计，非承诺）

| 组合 | 可用手段 | 现实成功率 | 依据 |
|---|---|---|---|
| 同网段（任何 NAT 类型） | mDNS + 直连 | 必通 | docs/66 实测 25.4 MB/s |
| 一端公网 IPv4 / 一端任意 | TCP 直连（公网端监听） | 必通 | 公网端无映射问题 |
| 一端可映射 / 一端任意 | 映射 + TCP 直连 | 高（家宽路由器默认开 UPnP 的占多数） | 估；`PortmapAlert` 会给出确定答案 |
| 两端都锥形（EIM） | UDP 打洞 | 高 | BEHAVE §3.4；DCUtR 的 70% 是含对称的混合分布 |
| 一端锥形 / 一端对称 | 打洞 | 低（估 5–15%） | 对称端映射随目的地变 |
| 两端对称 / 两端 CGNAT | 无（v6 除外） | ≈0 | docs/77 实测 `peers=0` |
| 两端全局 v6 且至少一端入站放行 | v6 直连 | 高 | 无 NAT；入站放行是前提 |
| 两端全局 v6 但都拒入站 | 无 | ≈0 | RFC 6092 默认拒未请求入站 |
| 浏览器 ↔ 原生（无 TURN） | srflx↔srflx | 仅当原生端可入站；STUN 被拦则 0 | docs/42 §附2 实测零 srflx |

## 13. 因本文需要修改的既有决策

| # | 现决策 | 位置 | 建议改为 | 理由 |
|---|---|---|---|---|
| 1 | 私有侧关 UPnP / NAT-PMP | AGENTS §9、`BtSession.kt:207-208`、protocol §8 | 默认关；跨网档按需开，窗口内有效，结束撤销 | 放弃这档等于放弃唯一免费的高成功率路径（§7）；库默认就是 true，改动量是删两行 |
| 2 | `ip_filter` 只放行私网 | protocol §8、`BtSession.kt:217-237` | 私网 + 「已入群设备公网地址」白名单；跨网档必须放行 | 现状下即使 UPnP 打通，公网 peer 也被 `0.0.0.0/0 BLOCK` 拒掉 |
| 3 | 发现层三层（mDNS / tracker / 手动） | AGENTS §9 | 加第四层「可达性上报 + 按可达性决定拨号方向」 | L0 新增要求（§10） |
| 4 | `peer.announce` 只带 `btPort`，`btHost` 缺省用 TLS 对端地址 | protocol §9 | 显式带 `btHost` + 映射后外部端口 + 传输类型（tcp/utp） | 打洞/映射下 TLS 对端地址 ≠ BT 端点 |
| 5 | 未记录 uTP 状态（实际默认开着） | `BtSession.kt:202-215` | 显式写死 `enable_incoming_utp` / `enable_outgoing_utp` 并注释理由 | 现在 UDP 端口也在 `0.0.0.0` 上开着，属未记录的暴露面 |
| 6 | 阶梯顺序与超时未成文 | STATE.md 决策行 | 按 §8 的阶梯表 + 20 s 总预算写成状态机 | 需要可验证判据，不能凭感觉 |
| 7 | 链接分享隐含「无 TURN 也能兜底」 | `docs/design/link-share.md` | 明确写「浏览器无入站、无 TURN 时只剩 srflx」 | 现文案未覆盖 §5 的失败矩阵 |

## 14. 需要 L0 拍板

1. **UPnP / NAT-PMP-PCP 是否在私有侧按需开启**（建议开：窗口内 + 随机端口 + 白名单）。与 AGENTS §9 现行文字冲突，只能 L0 改。
2. **`ip_filter` 是否改为「私网 + 群成员公网地址白名单」**（不改则跨网档在代码层面不可能成立）。
3. **消息面是否新增 UDP 能力**：打洞需要自定义 UDP socket 与信令对齐，BT 面做不了。要不要为此扩协议（新端口、新帧类型、NAT 类型上报字段）？
4. **要不要做 TCP 同时打开**（成本中、收益不确定，且需与监听端口共存，未验证）。
5. **端口预测明确不做**（理由见 §6.3）——请确认这是决策而非遗漏。
6. **v6 入站放行的产品口径**：诊断页是否给「你的路由器挡住了 IPv6 入站」提示与操作指引（涉及引导用户改路由器）。
7. **失败文案与 20 s 总预算**是否按 §8 / §10.4 落地。

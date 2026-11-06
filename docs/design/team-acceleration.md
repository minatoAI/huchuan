# 组队加速（0x53 team.part）设计与可证伪验证方案

- 版本 tav1 · 2026-11-06 · 执行层设计 agent（只读）· 唯一产物：本文件
- 适用：M2 组队条目（`protocol.md:292` 的 0x53 空定义；`product-spec.md:274` 流程 6、`:306` 队友数、`:338` M2 验收行）
- 证据口径：libtorrent `RC_2_1` 源码与官方文档、libtorrent4j 2.1.0-39 jar（javap 实取）、本仓 core/harness 行号 > 旧项目实测 > 算术推演（标「推」）；不在范围：M3 公开社区、WebRTC 数据面（`link-share.md`）

## 0. 结论速览

1. **组队不解决吞吐，只可能解决「做种者上行里的重复字节」。** 收益上限 = 在飞请求窗口的字节量，推演为 **3 秒级**（§2.1）。
2. rarest-first 在「单做种者 + 成员全互联」拓扑下已经完成去重：做种者持有全部片 → 任一成员收完的片对他人变成 availability+1 → 他人转向更稀有的片（§6.1）。
3. 因此分两档：**0x53 做「队形通告」（不动调度）**；**显式分片（piecePriority IGNORE）不做**，除非 §9 实测 D ≥ 1.25。
4. 任何一档的数据面都走 BT + `connect_peer`，不走 DataChannel（7–10 MB/s 对 25.4 MB/s，`link-share.md:6`）；分片还有一个会让它从「没用」变成「灾难」的前置缺陷，见 §1.3。

## 1. 问题 1：组队到底解决什么

### 1.1 三种说法，只有一种成立

只有第三种成立。「多人一起下更快」错：局域网介质被 N 人共享，全员完成的下限是 N·F/L，与人数无关。「BT 缺分工」半错：rarest-first + availability 已经在分工（§6.1），缺的只是首轮窗口。「做种者被 N 人重复喂同一批片」对，但量级只有在飞窗口那么大（§2.1）。

### 1.2 收益只可能来自做种者上行的去重

设文件 F 字节、做种者上行 C、成员间带宽 L、成员数 N、D = 做种者实际上行 ÷ F。

| 拓扑 | 不做组队 | 理想分片 | 差（收益） |
|---|---|---|---|
| 局域网饱和（C 远大于 N·L） | N·F/L | N·F/L | **0** |
| 做种者上行受限（C 远小于 L） | D·F/C | F/C + (N−1)/N·F/L | **(D−1)·F/C** |

结论：组队的全部价值就是 (D−1)·F/C 这一项。D 由 libtorrent 的 picker 决定，不由我们的协议决定 —— 所以这个功能成立与否是**测量问题**，不是设计问题。

### 1.3 成员互助已经免费，但有一个前置缺陷

- `peer.announce`（`Protocol.kt:386-418`）已广播 dev→(host, btPort)；`GroupRuntime.kt:219 connectSeeders` 对每个成员拨 `connect_peer`（`BtSession.kt:142-150`）。成员互为 BT peer 在 M1 就存在，**组队不新增「互助」，只新增「首轮不重复」**。
- **缺陷（做分片前必须先修）**：`GroupRuntime.kt:223` 与 `:229` 的 `hasPeers` 短路 —— 只要该 torrent 已有 1 条 peer 连接就不再拨其它成员。单做种者先被连上后，两个下载者永远不互连，此时分片会把双方各自钉死在 1/N。

## 2. 问题 2：收益出现的条件

| 条件 | 必要性 | 本机能否判定 |
|---|---|---|
| 做种者上行是瓶颈（C ≤ L/4） | **必要**，否则介质已饱和，落在 §1.2 第一行 | 不能直接判；用 P4 单文件限速或跨网场景当代理 |
| 全员两两 BT 连通 | **必要**，否则卡在 (n−1)/n | `numPeers(fileId) ≥ n` |
| 做种者只有 1 个 | **必要**，多源时重复字节摊到每条上行，收益再除以做种者数 | `peer.announce.seeds` + 连接数估 |
| N ≥ 3 | 影响收益系数 (N−1)/N：N=2 只拿到一半 | `members.size` |

### 2.1 重复字节的推演上界（本文的关键算式）

- libtorrent 每个 peer 的出向请求队列长度 = `request_queue_time`，默认 3 s（官方 Settings 参考：the length of the request queue given in the number of seconds it should take for the other end to send all the pieces），故每人窗口 W ≈ 3·C/N 字节。重复只可能发生在窗口内：成员看不见别人「正在拿」的片，availability 只在片完成并 flush 后才 +1（§6.1）。
- 于是 D − 1 ≈ (N−1)/N · 3C/F，**收益 = (D−1)·F/C ≈ (N−1)/N · 3 秒**。
- 数字：N=2 → ≤ 1.5 s；N=3 → 2.0 s；N=4 → 2.25 s。对照 512 MiB @ 2 MiB/s 的基线 256 s，即 ≤ 0.6%。
- **可证伪预测**：E0 若实测 D ≥ 1.25（做种者多传了 1/4 个文件），说明「picker 已去重」这个前提错了，必须重估；若 D 在 1.0x，则如期，不做。

## 3. 问题 3：代价

| 代价项 | 量级 | 触发点 |
|---|---|---|
| 协调消息 | 0x53 ≈ 40 B + 16·n B，≤ 3 次/文件 | 成员进出 |
| 重排失配 | 在飞请求不可撤回（§6.3），每人白下 ≤ W（1–3 MB，推）；部分下载的片作废 | 每次 epoch 变更 |
| 成员进出 | O(n) 重排；加入者拿走 1/n，原成员的剩余集整体变化 | 加入/离开 |
| **最坏更慢** | 名单里有别人连不上的成员 → 全员卡 (n−1)/n，靠 §7.1 门禁 + 15 s 重排兜 | 门禁失效 |
| resume 污染 | piece_priority 写进 resume（`torrent.cpp:5913-6004`、`6012-6047` 的 `set_need_save_resume(if_config_changed)`）→ 崩溃重启后只下 1/n 且没人补 epoch → 永久卡住 | 进程被杀 |
| 实现面 | 1 个消息类型 + 内核调度器 + 4 条失败路径 + 2 处 UI，对照 1.5 s 的收益上限 | — |

## 4. 语义

### 4.1 什么算「在组队」

同 gid、同 fileId、本地处于「部分」（`product-spec.md:306`）、满足 §7.1 全部门禁、且持有有效 epoch 的成员集合。**做种者不是队员**（它已是完整源）。

> L1 裁决（2026-11-06）：不实现 epoch，队友数按**观测口径**——当前实际与本机互连、且同处于该文件「部分」的成员数，以 BT 连接实况计。

- n = |members|（含自己）；队友数 = n − 1；UI 的「组队 N 人」= n。`product-spec.md:306` 现写「队友数 = 同时处于部分的成员数 − 1」，那是**观测口径**（本机猜的），本文是**协议口径**（epoch 里写明的）。冲突处见 §10。

### 4.2 协调者：members[0]，不做选举

- 协调者 = members 中 deviceId 字典序最小者。同一 epoch 内全员一致，零选举流量。
- 退出：成员集合一变就是新 epoch，新的 members[0] 自动接管；**没有迁主流程** —— 这正是选它的理由。职责只有两件：提议新 epoch、维护重排计时。

### 4.3 epoch：唯一的状态载体

- 每 fileId 一份 `(epoch:u32, members[])`，从 1 起，只在成员集合变化时 +1。
- 收敛（无需签名）：接受条件 = epoch > cur，或 epoch == cur 且 hash(members) 字典序更小。任何成员都可提议，并发提议按该全序收敛；同一 epoch 5 s 内不重复提议。
- 越界保护：epoch > cur + 16、members 非升序或有重复、members 含本群成员表外的 dev → 丢帧回 E_PROTO。
- 本机不在 members 里 → 清空所有 IGNORE、退出 ACTIVE，**不回改别人的 epoch**。

## 5. 片分配

### 5.1 先钉死一个语义陷阱（这一节决定成败）

`IGNORE` 的语义是「这片我不下载」，对任何 peer 都成立，不是「不从做种者要」。所以「只下载自己那 1/n」的实现必然永远到不了 100% —— 到 1/n 就停住。

唯一自洽的机制是**首轮独占 + 一轮后交还**：

| 阶段 | 优先级设置 | 效果 |
|---|---|---|
| 首轮（STEAL） | 非本机片 = IGNORE(0)，本机片 = DEFAULT(4) | 本机只从做种者拉自己那 1/n，窗口内无重复 |
| 交还（HANDOFF） | 一旦拥有全部本机片，立即全片重置 DEFAULT | 剩余 (N−1)/N 从队友处按 rarest-first 拿，交回 libtorrent |

- IGNORE 只存在于「本机进度不到 1/n」的有限窗口，**没有死锁面**：本机从不等别人的片才前进。
- 首轮结束判定：对本机负责的片逐片 `TorrentHandle.havePiece(p)` 全 true（每 2 s 检查一次，2048 片可接受）。

### 5.2 交错，不是连续区间

- 规则：片 p 归 `members[p % n]`（片级交错，不引入块粒度参数）。
- 理由：成员中途退出时，连续区间留下 1/n 的大洞必须整段补，交错只留散片；片大小 64 KiB–4 MiB（`BtSession.kt:286-296`），交错对顺序写盘没有实测意义。

### 5.3 0x53 team.part 字段（取代 `protocol.md:292` 的空定义）

```
{"t":"team.part","gid":"7Q2F9C4M1XZ8KD3V","dev":"K2M9QXDP4T8VNB2C","nonce":"9b2e44…","ts":1762381300000,
 "fileId":"8b3f0c0e1d2a4b6c8d0e2f4a6b8c0d1e2f3a4b5c","epoch":2,
 "members":["7Q2F9C4M1XZ8KD3V","K2M9QXDP4T8VNB2C"],"n":2,"reason":"join"}
```

| 字段 | 类型 | 规则 |
|---|---|---|
| fileId | 40 hex | 必须是本群已知条目；未知 → 丢帧，不排队 |
| epoch | u32 | ≥1；接受条件见 §4.3 |
| members | 数组 ≤32 | deviceId 升序、无重复、全部在本群成员表内；含本机才参与 |
| n | u16 | MUST == members.length，不等即丢（防截断） |
| reason | 枚举 | join / leave / done / drop，只用于日志与 UI |
| gid/dev/nonce/ts | 通用 | `protocol.md` §10 通用字段；dev = 提议者 |

- **分配表不进报文**：片 p 归 members[p % n]，两端各自算，报文只带成员列表。少一层不一致源。caps 沿用 `protocol.md:202` 的 `team`，方向是广播（`:292` 已定）。
- **不加签名**：帧已在 TLS 且 hello 绑定之后（`protocol.md` §8），team.part 只影响本机调度、不产生持久事实；伪造后果有界（退回普通调度）。epoch 越界与 members 校验挡住最坏情况，加签名要多一个签名域和公钥查找，不值。
- 为什么不能省掉这条消息、只靠 peer 列表推断：`PeerInfo` 只有 `ip()` 没有端口（javap 实取），只能用 IP 匹配 dev → 局域网可用、同一 NAT 后不可用，而且拿不到 epoch。

### 5.4 重排规则

- 第 2 人进入：members[0] 提议 epoch+1（含两人）。成员离开或失联 15 s：任一观察者提议 epoch+1（去掉该成员）。
- 本机拿到全部本机片：交还（HANDOFF），不动 epoch。全员完成：广播 reason=done，epoch 状态丢弃。
- 上限：每次下载 ≤ 3 次 epoch 变更，超出即降级为 BACKOFF（§7.2）。

## 6. 与 BT 的关系：API 级证据

| 能力 | 有没有 | 证据（libtorrent4j 2.1.0-39 / libtorrent RC_2_1） | 用不用 |
|---|---|---|---|
| 逐片优先级 | **有** | `TorrentHandle.piecePriority(int, Priority)`、`prioritizePieces(Priority[])`、`piecePriorities()`；`Priority.IGNORE/LOW/TWO/THREE/DEFAULT/FIVE/SIX/TOP_PRIORITY` | **用**，仅用于 §5.1 首轮独占 |
| 优先级语义 | 0 = 根本不下 | 官方 Torrent_Handle 参考：0 means not to download the piece at all；Piece priority takes precedence over piece availability；lower priority values means less likely to be picked | 支撑 §5.1 |
| 片截止时间 | 有 | `setPieceDeadline(int,int[,deadline_flags_t])`、`resetPieceDeadline`、`clearPieceDeadlines` | **不用**：它是正向强制（pieces with a more recent deadline will always be prioritized），挡不住别人也请求同一片，做不出分片 |
| 逐 peer 片选择 | **没有** | picker 是 per-torrent（`piece_picker.cpp:1963 pick_pieces`） | §5.1 陷阱的根因 |
| 对做种者生效 | 否 | `torrent.cpp:5913` 的 `if (!valid_metadata() \|\| is_seed()) return;` | 对做种者零副作用（好事） |

### 6.1 rarest-first 为什么已经去重

`piece_picker.cpp:1944-1948` 写明 rarest_first = pick the rarest pieces first；`update_pieces()`（`piece_picker.cpp:1506`）按 `pos.priority()` 把片分桶写进 `m_pieces`，`pick_pieces()` 顺序走桶，availability 参与桶序（官方：Piece priority takes precedence over piece availability）。做种者持有全片 → 所有片 availability ≥ 1；某个成员收完一片后，该片对他人的 availability 变 2 → 他人转向 availability 1 的片。**这就是去重机制，它已经在跑。**

### 6.2 三条实施必守

1. 批量设置：一次 `prioritizePieces(Priority[2048])`，不要 2048 次 `piecePriority`。
2. 磁力无 metadata 时是 no-op（`torrent.cpp:5913` 的 `valid_metadata` 判定）→ 只在 metadata 就绪后设。
3. 起 session / 加种后无条件把全片重置 DEFAULT：resume 会把旧优先级带回来（§3）。

### 6.3 在飞请求不可撤回

`torrent.cpp:5913-5940`：`set_piece_priority` 只改 picker 与 interest 标志（写完才在 6004 存 resume），不取消已发出的 block 请求 → 重排后最多 W 字节（1–3 MB/人，推）白下。这是 §3「重排失配」的来源。

## 7. 失败与回退

### 7.1 成队门禁（四条全过才进 STEAL）

1. 本地该 fileId 处于「部分」且 metadata 已就绪（`status().state()` 在下载态、`isFinished() == false`）。
2. 收到含本机的 team.part，且 n ≥ 2。
3. `numPeers(fileId) ≥ n`（成员互连 + 做种者；全互联的必要条件）。
4. 该 fileId 不在 BACKOFF 冷却期，且本次下载内 epoch 变更次数 ≤ 3。

任何一条不过：不进 STEAL，UI 只显示「队友」计数，不做任何优先级改动。

### 7.2 状态机

| 状态 | 进入条件 | 退出 |
|---|---|---|
| IDLE | 默认 | 收到含本机的 team.part（n≥2）→ PROBING |
| PROBING | 门禁 3–4 检查中（≤10 s） | 全过 → STEAL；任一不过 → IDLE（不改优先级） |
| STEAL | 已设 IGNORE | 本机片齐 → HANDOFF；触发回退 → BACKOFF |
| HANDOFF | 已全片复位 | 完成 → IDLE；无法推进 → BACKOFF |
| BACKOFF | 回退 | 5 min 冷却后回 IDLE（同一文件不再组队） |

回退触发：协调者或任一成员失联 15 s；连续 30 s 无片完成且 numPeers ≥ 2；60 s 内 2 次重排；门禁 3 失效。**回退动作统一为：全片重置 DEFAULT + 停发 0x53 + 冷却 5 min**，之后完全交回 libtorrent。HANDOFF 态本身已复位优先级，所以成员掉线时「补齐」是自动发生的，不依赖协调者。

## 8. UI

| 位置 | 显示 | 消失 |
|---|---|---|
| P4 进度下方 | `正在组队 · N 人`，当且仅当状态 ∈ {STEAL, HANDOFF} | 状态回 IDLE/BACKOFF、本机完成、n<2 |
| P3 行内 chip | 状态 chip 右侧并列一枚次级 chip `组队 N 人`（仅 ACTIVE 时）；状态 chip 不变（仍是 `下载中 42%`） | 同上 |
| P2 群组行 | **建议删掉** `product-spec.md:115` 的 `组队 2 人`：群级聚合无意义（3 个文件各 2 人 ≠ 组队 6 人），首页只做导航 | — |

- 队友离队：N 就地减一重绘，不弹 toast、不弹 dialog（`AGENTS.md` §4.5：动效只服务状态变化）；N 掉到 1 时整行消失，不显示「组队 1 人」。
- 只计数不做分片（PROBING 或门禁未过）时**不显示任何组队文案** —— 显示就意味着调度已生效，否则是谎报。

## 9. 验证方案

### 9.1 E0：单机三进程（先跑；不需要真机，不需要 0x53 实现）

装置：1 个做种进程 + 2 个下载进程（现有 harness 加一个角色 S 与第二个下载者 C，复用 A/B）；做种侧 `setUploadLimit(2048)`（`BtSession.kt:167-172`，单位 KB/s）。**E0 必须对两个 peer 各显式拨一次 `connect_peer`**：现有 `connectSeeders` 的 `hasPeers` 短路会让第二次拨号被跳过（§1.3）。

- F = 512 MiB → 256 KiB × 2048 片（`BtSession.kt:286-296`）；F/C = 256 s。快版：256 MiB → 128 s。
- 单机 loopback 远大于 C，不是瓶颈，恰好把被测机制隔离成 D 一个量：T_all ≈ D·F/C。**基线臂不需要任何新代码** —— 这一臂就能判定「做/不做」，这是排期上最重要的一句话。

### 9.2 指标与采集（全部现成）

| 指标 | 来源 | 定义 |
|---|---|---|
| T_all | harness 时间戳 | 第一个成员开始 → 最后一个成员 100% |
| S_up | `Probe.torrentLine` 的 `payload_uploaded`（`Probe.java:118-127`） | 做种者累计上行 payload 字节 |
| D | S_up ÷ F | 重复比（主判定量） |
| 人均从做种者拿 | `TorrentHandle.peerInfo()` 按 `ip()` 聚合 `totalDownload()` | 交叉校验 D（PeerInfo 只有 ip） |

### 9.3 预期数字（每臂 3 轮，交替执行，首轮作热身后丢弃）

| 臂 | 配置 | 预期 T_all | 预期 D |
|---|---|---|---|
| A | 基线，2 人，C=2 MiB/s | 256–262 s | 1.00–1.03 |
| B | 分片，2 人，同 C | 256–260 s | 1.00（首轮定死；尾部与基线同阶） |
| C | 基线，3 人，C=1.5 MiB/s | 341–350 s | 1.00–1.03 |
| D | 分片，3 人，同 C | 341–347 s | 同上 |
| E | 对照：做种者不限速，2 人（介质饱和） | 2F/L ≈ 42 s | 分片臂不得劣化 > 5% |
| X | 反向门禁：只连做种者、成员不互连（复现 `hasPeers` 短路） | 分片臂必须回退到 A 的水平 | — |

- **A/C 臂必须同时记录每个成员的 `numPeers(fileId)`**（全互联时应为 2 与 3：做种者 + 队友）。若只有 1，说明复现了 §1.3 的短路，本轮作废 —— 那种情况下的 D≈2 只证明「成员没互助」，不能当「picker 没去重」用。

### 9.4 判定阈值

| 主判定（A 臂 D，N=2） | 结论 |
|---|---|
| D ≥ 1.25 | **做**显式分片（收益 ≥ 20%，够补偿 §3 的实现面） |
| 1.10 ≤ D < 1.25 | **有条件做**：仅当 N ≥ 3 且 C ≤ L/4 时启用 |
| D < 1.10 | **不做**（收益 < 10%，落在轮间噪声内） |

- 统计口径：3 轮中位数，两臂 T_all 的 [min,max] 不重叠或中位数改善 ≥ 20% 才算赢；若 A 臂 T_all 接近 512 s（D≈2）说明 picker 没去重，全部结论推翻、直接转「做」。
- 安全项（任一不过即「不做」或「必须先修」）：分片臂失败率 ≤ 1/3（`team_*` 日志），E 臂回归 ≤ 5%，X 臂必须回退不得卡死（卡死 = FAIL，对应 §1.3 的缺陷）。
- E0 通过后由 E2 真机复核（MIX 2S + Windows daemon + 第二台安卓；OPPO 已撤出，见 `STATE.md:11`，缺口需 L1 解决）。真机只复核「分片不劣化」与两处 UI，不复核收益。

## 10. 结论：做 / 不做 / 有条件做

**做（无条件，与实测无关）**：0x53 的「队形通告」语义（§5.3 报文只用 fileId/epoch/members/n/reason）+ P4「正在组队 · N 人」+ P3 次级 chip。不需要任何 `piecePriority` 调用，最坏情况与今天一致；它解决的是「看得见谁在同时下」，不是吞吐。

**有条件做**：显式分片（§5.1 首轮独占 + 交还、§5.2 交错、§6.2 三条、§7 门禁与回退）。条件按序：

1. 先修 `GroupRuntime.kt:223/229` 的 `hasPeers` 短路，否则分片必然灾难。
2. E0 的 A 臂实测 D ≥ 1.25（§9.4），且安全项全过。
3. 只在「单做种者 + 做种者上行受限 + 全员两两连通 + N ≥ 3」时启用；其余一律只显示计数。

**不做**：DataChannel 数据面（7–10 MB/s 对 25.4 MB/s）；跨网组队（成员两两不可达，与 §2 第二行直接冲突）；按成员带宽再分配片（BT 已按 peer 速率选源）；`set_piece_deadline`（做不出分片）与 `SEQUENTIAL_DOWNLOAD`（与分区正交且破坏 rarest-first），见 §6。

**要改的既有规格（需 L1 派单，本文不动）**：

1. ~~`product-spec.md:306` 队友数口径 → 改为「有效 epoch 的成员数 − 1」。~~ 已由 L1 裁决为观测口径，本条作废。
2. ~~`product-spec.md:115` P2 的 `组队 2 人` → 删（§8）。~~ 已删。
3. ~~`product-spec.md:338` M2 验收行~~ 已改为「显示队友人数正确 + 记录 D 与 T_all」。

**一句话**：这个功能的上限是 3 秒。先花半天量出 D；D 不动就不做，把 M2 的排期还给通知三档与限速 UI。

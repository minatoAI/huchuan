# core/ · M1 内核（无 UI）

- 版本：2026-11-06 · 内核 agent · 对应协议 v0（docs/design/protocol.md，唯一事实源）
- 状态：**M1 全流程在桌面 JVM 上用两个独立进程跑通**（建群 → 后 8 位加入 → 32 MiB 共享/下载/SHA 一致 → 评论 3 s 内入库 → 全程 dht_nodes=0）
- 本轮（M1.6 跨网发现）：内核接上自建 tracker（协议 §3.2）—— 建群 / 入群时登记 `groupHash20`，每群一条线程按**服务端下发的 interval**（默认 600 s、下限 300 s）announce；passkey 每小时轮换（`hex(HMAC-SHA256(kGrp, "huchuan/trk1/v1"‖gid‖小时整数))`），换小时先重新注册（带 `prevPasskey` 做连续性证明）再 announce；回来的 peers 进候选表，与 mDNS / 手动地址按 §3.4 合并去重、**优先级最低**（`CandidateMerge`）。配置项 `trackerBaseUrl` 为空 = 整个功能关闭：只失去跨网那一档，局域网照常，不报错、零请求。私有群的 infohash 仍然不进 DHT / PEX / LSD（协议 §8 不变），上报给 tracker 的只有伪 swarm 的 `groupHash20`；日志里 passkey 只留前 4 字符。接口与口径见 §3.5，常量见 §6
- 上一轮（P7 全局限速接线前置）：内核补**会话级（全局）限速** —— `setSessionUploadLimit` / `setSessionDownloadLimit` / `sessionUploadLimitKbps` / `sessionDownloadLimitKbps`，运行期改立刻生效、对会话里**已存在**的 torrent 同样生效；入参 KB/s，内部 ×1024 字节/秒，0 = 不限（口径见 §6）。**踩到的坑**：libtorrent 默认把局域网与回环对端排除在全局限速之外（`upload_rate_limit` 只作用在 global peer class 上），只设那两个值时读数一切正常、局域网上一点没慢（实测 16 MiB loopback：设 2048 KB/s 仍按 31 MB/s 跑完）；内核在建会话时把私网段并进 global peer class 修掉（同一流量压到 ≈1.4 MB/s）。顺带修掉「端口兜底用 0」的缺陷（见 §8-2）。app 侧接线不在本轮
- M1.5（上一轮）：三件事 —— ① **修「组队不成立」的缺陷**：`connectSeeders` 原来「有 1 条 peer 就整轮不拨」，单做种者先被连上后多个下载者永不互连；现在按「每成员一次拨号」，幂等去重 + 复用冷启动重连的退避与预算（`MemberDialPolicy`）。② **修「两端同时拨号永远连不上」的缺陷**：被 `duplicate_link` 裁决关掉的一侧原来只会退避重拨；现在认出「同一成员已有活链路」并直接沿用（`settleOnExistingLink`），僵尸链路不再参与裁决。③ **新增队友数观测读口** `kernel.teammates(gid, fileId)`（数据来自 libtorrent 的 peer 列表，无新协议字段、不用 0x53）。E0 实测 `D=1.1394 / T_all=301.7s / peers=2/2` → 结论「只做通告，不做分片」，见 docs/reports/e0-team.md
- M1.4（上一轮）：补两个内核缺口 —— ① 冷启动后主动重连群成员（mDNS + 落盘的手动地址 + 上次连过的端点，指数退避 1/2/4/8/16/30 s、每群并发 3），完成后暴露 `KernelEvents.peersChanged` 给 P6；② 完成判定不再等 2 s 巡查节拍，libtorrent 的 `torrent_finished_alert` + 200 ms 兜底巡查当场推 `fileCompleted`（实测：事件比「本进程第一次看到 isComplete」只早 51 ms，另一次跑早 135 ms）。见 §3.3
- M1.3（上一轮）：做种集合与完成判定合成一份来源（`SeedingLedger`），下载完成即进入做种集合，休眠、文件被移走删掉、数据不完整都移出；重启续种（原 G2）按 `state.json` 里的做种集合恢复做种，文件不在或大小不对就降级为休眠并记日志。修掉真机上「下完转做种自报 seeds=0」的缺陷（docs/m1-device.md §6-6）
- 不在本文件范围：UI 实现细节（app/ 已通过 `M1Runtime` + `M1GroupStore` 接内核，没有 runtime 时回退 `InMemoryGroupStore`）、Android .so。跨网发现的**内核侧**本轮已就位（§3.5），但 `app/` / `daemon/` 本轮没有把 `trackerBaseUrl` 从配置读进来（那是下一条接线单），所以默认行为仍是「只有 mDNS + 手动」
- M1.2 收敛（上一轮）：建群/入群时创建者登记自己的公钥（修掉 `file_list_rejected reason=no_pub`）、成员平台与做种数（G3，协议 §10 的 0x30 补两个可选字段）、每群每文件的未读游标（G4）、幂等的退群 `removeGroup`（G1）；接口签名见 §3.1
- M1.1 收敛（对应 protocol.md §13 的第 2/3/4 条）：join.ok 用独立签名 domain 且验不过即断开、file.list 每条 entry 原样带 file.add 的原 sig 并按 addedBy 验签、公钥随 join.ok.members 与 member.name 分发；评论时延改本机时钟口径、单帧发送失败不再断整条连接

## 1. 怎么跑

```bash
bash scripts/m1-headless.sh
```

一条命令做四件事，最后一行打印 `PASS` / `FAIL`，退出码 0 / 1：

1. `./gradlew :core:jar :core:m1Deps :core:m1Native`（构建内核 + 拉依赖 + 解出 `libtorrent4j.dll`）
2. `javac` 编译 harness（`harness/src/main/java`）
3. 跑字节层自检 `SelfTest`（219 条：B32、HKDF（RFC 5869 向量）、邀请码、P-256 签名、join.ok/file.list/member.name 的签名口径、signBytes、JSON、帧；**本轮新增跨网发现一组**（见 §3.5：groupHash20 与 passkey 的公式用 JDK 原语独立复算、小时边界、passkey 打码、候选合并优先级与 128 上限；再起一个真内核对着本机 mock tracker 断言注册 / announce 的参数 / peers 并入候选表 / 回到 `stopped` / 空 baseUrl 时零请求）；会话级限速一组：设 512/0 的读回、连续改两次以最后一次为准、私网与回环对端是否也在限速范围内、以及 loopback 真流量（8 MiB 在 1024 KB/s 限额下必须 ≥5 s 才走完）；另有两组 —— 创建者用自己的公钥验自己的条目（含「没登记必须 no_pub」的反向断言）、peer.announce 的 plat/seeds 字段与「不进签名域」；未读游标的全序与落盘往返；做种集合与重启续种（下载完成 0→1、休眠回到 0、重启恢复仍为 1、文件缺失判定为不恢复且不抛异常）；冷启动重连的编排 `ReconnectPolicy`：退避表 1/2/4/8/16/30 s 与 30 s 封顶、每群并发 3、候选去重 / 在途跳过 / 预算截断 / 非法端口丢弃）
4. 起 A、B 两个 JVM 进程跑 M1 全流程，然后只用 grep 复核日志

产物：`artifacts/m1/log-A.log`、`log-B.log`、`selftest.log`、`a-data/`、`b-data/`（含 `state.json`、`identity.txt`、`downloads/`）。`artifacts/` 不进版本库。

一次完整跑（Gradle 增量 + javac + 自检 + 两进程 + 断言）本机实测 **1 分 40 秒左右**（B 角色多出两次内核重启，见 §3.2；A 现在还要等 B 冷启动后自己连回来，见 §3.3；loopback 限速用例固定多花 ~10 s；本轮跨网发现里要起两个真内核 + 两个本机 mock tracker，再多花 ~8 s）；脚本的 35 条 CHECK 全 PASS（自检 219 条另计，其中跨网发现一组 68 条，正向证据行 `selftest.tracker checks=68 failures=0 register_requests=1 announce_requests=2 candidates_from_tracker=3 tracker_http_uris=3 disabled_kernel_requests=0`）。

本轮新增的三条硬断言在 harness 里。`scripts/m1-headless.sh` 本轮不动，它仍然只看 harness 的退出码（`process.rc_0_0` / `catchup.process_rc_0_0_0`）：harness 判 FAIL 即整轮 FAIL，脚本输出里也有对应的证据行。

| 断言（日志名） | 判据 | 现场证据（`artifacts/m1/log-A.log` / `log-B.log`） |
|---|---|---|
| `M1 B reconnect_after_restart` | B 冷启动（内核重建 + 恢复群）到重新绑定 A 的耗时 ≤ **10 s**（`limit_ms=10000`），拿不到就 `RESULT=FAIL` | `ok=true elapsed_ms=170`（走落盘的端点 `source=peer`，拨号本身 26 ms） |
| `M1 A peer_rebound` | A 侧看到对端自己连回来（第 2 次 `peersChanged(online=true)`），且重连后那条 `peer.announce` 的 `plat` 不是 `unknown`、`seeds` > 0 ——「未知 / 0」就是原缺陷的样子 | `ok=true plat=windows seeds=2` |
| `M1 B completion_event` | 「本进程第一次看到 `isComplete()`」（100 ms 轮询）到 `fileCompleted` 回调 ≤ **500 ms**，且事件必须来自完成快通道（`source=alert\|sweep`），落回 `monitor` 即 FAIL | `arrived=true lag_ms=51 source=sweep fast_path=true`（同机制另一次跑是 `lag_ms=-135`：事件比轮询看到完成还早） |

G1/G3/G4 的运行时验证不在这条脚本里（脚本本轮不动），用一次性探针跑的：`artifacts/gaps-probe/`（`GapsProbe.java` + `probe.log`，38 条断言，不进版本库），覆盖两内核互联后的未读计数与清零、游标跨重启、成员平台/做种数跨线、以及退群时 `torrents_stopped=1 links_closed=1 key_wiped=true` 与重复调用返回 false。

本轮（M1.2）回归后的关键差异，都能在 `artifacts/m1/` 里直接 grep：`reason=no_pub` 从上一轮的 232 条降到 **0 条**（创建者公钥已登记）；`peer_announce` 多了 `plat=windows seeds=0|1`（「B 下载完成后自报的 seeds 从 0 变 1」这一句在 M1.3 才真的成立，之前是 0 一直不变，见 §3.2）；`op_ingested` 多了 `unread=1`；`state.json` 的 `keys` 里有本机自己的公钥、每个群多一个 `read` 数组。

真机链路另跑：`bash scripts/m1-device.sh --quick`（手机 + 桌面 JVM 对端，把 `:app` 也编一遍），本轮 **34 条 CHECK 全 PASS**。harness 故意写成纯 Java、由 `javac` 直接编译（不进 `settings.gradle.kts`，避免动到 app 的构建图）。

本轮两个缺口在真机上的现场（`artifacts/m1-device/logs/`，同一轮 --quick）：

- 冷启动重连：手机 `am force-stop` 后重启，`kernel_started … restored_groups=1 reconnect=on` → `reconnect_attempt … sources=peer` → `peer_reconnected addr=192.168.31.218:47100 source=peer elapsed_ms=52`；桌面端的对照行是同一时刻的 `peer_bound dev=<手机> role=in`。重启前这条链路要等对端先连过来（docs/m1-device.md §2.7）。
- 完成推送：桌面端下载完 32 MiB，`completion_alert`（10:17:03.277）之后 **201 ms** 就是 `download_completed source=sweep`（10:17:03.478），不再等 2 s 巡查。

### 可 grep 的验收点

| 验收 | 日志 | 脚本断言名 |
|---|---|---|
| A 建群并打印 16 字符码 | `M1 A invite_code=<16>` `invite_pretty=` `invite_tail8=` | `flow.A_invite_code_16_chars` |
| B 只用后 8 位加入 | `M1 B join_input tail8=<8>` + `M1 B joined` | `flow.B_joined_with_tail8_only`、`flow.B_joined`、`flow.same_group` |
| A 共享 32 MiB | `M1 A file_shared … size=33554432` | `flow.A_shared_32MiB` |
| B 下载完成且两端 SHA 一致 | `M1 A file_sha256=` / `M1 B download_done … bytes=33554432 sha256=` / 脚本自己 `sha256sum` 复算 | `sha256.equal`、`sha256.independent_recompute` |
| B 评论，A 3 s 内收到并入库 | `M1 B comment_sent op_id=` / `M1 A op_received … latency_ms=` / `M1 A op_in_store=true` | `comment.same_op`、`comment.latency_0_lt_x_le_3s`、`comment.A_in_store` |
| 全程 dht_nodes=0 | 所有 `dht_nodes=` 采样 + 两进程 `bt_probe` | `dht.a/b.enable_dht_false_and_running_false`、`dht.a/b.samples_ge3_all_zero` |
| 传输真走 libtorrent（不是本地拷文件） | `M1 B transfer_peers`、`M1 A/B transfer_stats` | `provenance.b_peers_during_transfer`、`provenance.*_from_libtorrent`、`provenance.download_mtime_after_run_start` |
| mDNS 真在发现 | `M1 B join_candidates lan=<n>` + `M1 B join_via source=lan\|manual` | `mdns.discovered_lan_candidate` |

## 2. 模块结构

全部在 `core/src/main/kotlin/app/huchuan/core/m1/`（包名 `app.huchuan.core.m1`）。旧的 `Model.kt` / `Ports.kt` / `InMemory.kt` 原样保留，app/ 继续用它们编译。

| 文件 | 职责 | 协议 |
|---|---|---|
| `Crypto.kt` | hex、SHA-256、HMAC、Crockford Base32、HKDF-SHA256、signBytes、domain 常量 | §1.2 §2.1 §2.2 |
| `Identity.kt` | P-256 身份（PKCS8 落盘）、低-S 签名与验签、deviceId、邀请码（校验位 + kJoin） | §1 §2 |
| `X509SelfSigned.kt` | 手写最小 DER 生成自签 EC 证书（CN=deviceId，10 年） | §5 |
| `Tls.kt` | TLS 上下文（信任全开、双向证书）、套件白名单、ALPN `huchuan/1`、chanId | §5 §1.2 |
| `Json.kt` | 严格 JSON（禁浮点、未知字段忽略） | §5 |
| `Protocol.kt` | 帧编解码（u32len‖u8ver‖u8type‖JSON，≤65536）+ 15 个 M1 消息的记录类型 | §5 §9 §10 |
| `OpLog.kt` | 评论日志：验签、opId 去重、prev 链、Lamport、乱序 pending、作者游标、读游标 `ReadCursor` / 未读计数 `Unread`（G4） | §6 |
| `PubRegistry.kt` | 设备公钥库（dev → pub64）与 file.list 条目验签口径 `EntryCheck`（no_sig / no_pub / bad_sig） | §7 §10 |
| `GroupRuntime.kt` | 一个群的运行时：成员表、文件清单、评论、连接、BT 任务、持久化快照；对外就是 GroupRuntime API。含「每成员一次拨号」(`connectSeeders`)、撞车后沿用既存活链路 (`settleOnExistingLink`)、队友数观测 (`teammates`) | §7 §11 |
| `BtSession.kt` | libtorrent4j 私有会话：建 v1 私有种、加种/做种、swig `connect_peer`、单文件限速、**会话级（P7 全局）限速**（含私网段并进 global peer class）、dht_nodes、做种健康状态 `seedState`、对端列表快照 `peerSnapshot`（队友数与拨号去重的唯一数据源） | §4 §8 |
| `Seeding.kt` | 「本机在做种的文件」唯一来源 `SeedingLedger`（下载完成进、休眠与文件缺失出、只数清单里还有的）+ 重启续种判定 `SeedRestore` | §7 |
| `Discovery.kt` | JmDNS `_huchuan._tcp` 注册/浏览；手动 `IP:port` 解析；四种来源的候选合并去重 `CandidateMerge`（manual/peer 3 > lan 2 > tracker 1，每群 128） | §3.1 §3.3 §3.4 |
| `Tracker.kt` | 跨网发现（自建 tracker）：`TrackerProtocol`（groupHash20 / passkey / peer_id / 日志打码，纯函数）、`TrackerHttp`（HttpURLConnection，info_hash 按字节 %XX）、`TrackerReply`（BEP 3 bencode 应答）、`GroupTracker`（每群一条线程：注册 → announce → 券商候选 → 按服务端 interval 续）、`TrackerService`（内核装配点：baseUrl 为空就整个不存在） | §3.2 §3.4 |
| `PeerLink.kt` | 一条消息层连接的全部语义：hello 绑定 → join → sync → 广播 → ping/bye/err | §2.3 §5 §7 §8 |
| `PeerServer.kt` | 消息层监听（端口顺延）、TLS 握手、每条连接一个线程 | §5 |
| `KernelEvents.kt` | 宿主事件出口 + 加入状态 + join 限速器 | §8 |
| `Reconnect.kt` | 冷启动重连（缺口一）：退避与候选编排 `ReconnectPolicy`（1/2/4/8/16/30 s、每群并发 3）+ 驱动 `PeerReconnector`（mDNS / 落盘端点 / 手动地址三处候选，连上即 hello 绑定，不重新 join）。另含 ① 成员间 BT 拨号编排 `MemberDialPolicy`（每成员一次、幂等、同一套退避与预算）；② 撞车兜底开关 `LinkRaceGuard`（`-Dhuchuan.mutants=no_link_fallback` 只给反向验证用） | §3.1 §3.3 §3.4 §11 |
| `M1Kernel.kt` | 门面：生命周期、建群/加入、公钥登记、持久化、后台巡查（心跳/补连/进度）、完成判定快通道、重连器装配 | §11 |

数据落在 `<dataDir>`：`identity.txt`（PKCS8 + X509 公钥）、`state.json`（群/成员/文件/评论/公钥）、`bt/`、`downloads/`。

`state.json` 里的群密钥不按明文写：`M1Kernel(secretBox = ...)` 决定包裹方案，落盘两个字段 —— `keySeed`（token）+ `keySeedProt`（方案名）。桌面 harness 用 `PlaintextSecretBox`（token 就是 hex，与 M1 早期的 `state.json` 兼容）；Android 的 `M1Runtime` 传 `KeystoreSecretBox`（Android Keystore AES-GCM），Keystore 不可用时退到 `IdentitySecretBox`（身份私钥派生）。方案名认不出、或 token 打不开（刷机 / 恢复出厂 / 密钥被系统清掉）时**跳过该群，绝不回退明文**，日志是 `state_key_scheme_unknown` / `state_key_unseal_failed`。

## 3. 公共 API

```kotlin
val kernel = M1Kernel(
    dataDir = File(ctx.filesDir, "kernel"),   // 应用私有目录
    deviceName = "我的设备",
    portHint = 47100,                          // 消息层起始端口，占用顺延 47101–47199
    btHint = 51413,                            // 私有 BT 会话起始端口
    events = object : KernelEvents() {
        override fun log(event: String, fields: Map<String, String>) { /* 写日志 */ }
        override fun fileAdded(group: GroupRuntime, entry: FileEntry) { /* P3 加一行 */ }
        override fun fileCompleted(group: GroupRuntime, entry: FileEntry) { /* 转「做种中」 */ }
        override fun opReceived(group: GroupRuntime, op: OpRecord) { /* P2 未读 +1 */ }
        override fun memberJoined(group: GroupRuntime, deviceId: String, name: String) { /* P6 */ }
    },
)
kernel.start()

// P5 建群：16 字符码，P6 用 invitePretty 显示 4-4-4-4
val group = kernel.createGroup("老板的群")

// P5 加入：只输后 8 位；manualCandidates 是「手动 IP:port」兜底，可为空
val joined = kernel.joinByTail8("GNMNTCPA", emptyList(), 60_000)

// G1 退群 / 解散：停掉该群全部 torrent、关闭该群消息链路、删群密钥、移出本地库；幂等，删过了返回 false
val removed = kernel.removeGroup(groupid)

// G4 未读数：整群 / 单文件两种粒度；打开 P2、P4 时清零（返回清掉的条数），游标落在 state.json 里
val unread = kernel.unreadCount(groupid)               // 整群
val unreadOfFile = kernel.unreadCount(groupid, fileId) // 单个文件
kernel.markGroupRead(groupid)                          // P2 打开
kernel.markFileRead(groupid, fileId)                   // P4 打开
group.unreadCount(); group.unreadCount(fileId); group.markAllRead(); group.markRead(fileId) // 同一套，群对象上

// G3 成员做种数与平台：members() 返回的每条记录都带，来自 peer.announce 的 plat / seeds
for (m in group.members()) { m.dev; m.name; m.platform /* android|windows|unknown */; m.seedingCount }
group.seedingFileCount()      // 本机在该群真实做种数（SeedingLedger ∩ files，含「下完转做种」）
group.seedingCountOf(dev)     // 某个成员自报的做种数（本机取真值）

// 队友数（观测口径，产品规格 §306）：当前与该文件 torrent 实际互连、且本机处于「部分」的成员数，不含做种者
val team = kernel.teammates(gid, fileId)   // = group.teammates(fileId)，群/文件不存在返回 0
// 本机 100%、或没挂进会话 → 0（文件到 100% 即退出组队）

// P3/P4
val entry = group.addFile(File(...))        // 建 v1 私有种 → 做种 → 广播 file.add
group.startDownload(entry.fileId)            // 加种 + connect_peer
group.progress(entry.fileId)                 // 0.0–1.0
group.isComplete(entry.fileId)               // 落盘并校验通过
group.savedFile(entry.fileId)                // 下载后的文件
group.setUploadLimitKbps(entry.fileId, 512)  // 单文件限速，单位 KB/s（×1024 字节/秒），0 = 不限
// BEP 16 super-seeding（默认关）：做种者不再把同一片重复发给多个下载者
group.addFile(File(...), true)               // 加种时开；两参版本，Java 也能直接调
group.superSeeding(entry.fileId)             // 真值读回（libtorrent 的 torrent::flags()），别信自报开关
group.setSuperSeeding(entry.fileId, true)    // 运行中开关（BEP 16 允许中途切）
group.connectSeeders(entry.fileId)            // 把已知做种端点接上（swig connect_peer）
val op = group.comment(entry.fileId, "这条能收到吗？", null) // parent 非空即回复
group.files(); group.members(); group.visibleOps(fileId)

// P7 全局限速（会话级）：入参 KB/s，0 = 不限；运行期调用立刻生效，对已在传输的 torrent 同样生效
kernel.setSessionUploadLimit(512)      // 上行 512 KB/s
kernel.setSessionDownloadLimit(2048)   // 下行 2 MB/s
kernel.setSessionUploadLimit(0)        // 恢复不限
kernel.sessionUploadLimitKbps()        // 真值读回（读会话里生效的值），刚设 0 这里就是 0
kernel.sessionDownloadLimitKbps()

// 展示用端到端时延（毫秒）：减法两边都在本机时钟上，跨设备时钟不同步也不会出负值
val latencyMs = kernel.latencyMillis(op.author, op.ts, System.currentTimeMillis())

kernel.close()
```

接口按 Kotlin 写，但**没有用 suspend、没有默认参数**，Java 可以直接 new / 调用（harness 就是纯 Java）。

- `connectSeeders` 是**每成员一次拨号**（见 §3.4）：去重键是 `fileId@dev`（`connect_peer` 是「每个 torrent 一份」的连接，只按 `dev` 去重会让第二个文件永远接不上同一个成员）；已经互连的成员不重拨（幂等），未连上的按 `ReconnectPolicy` 的退避表（1/2/4/8/16/30 s 封顶）重试，每轮最多 `MAX_PARALLEL_DIALS`（3）个新拨号；`stopFile` / 降级 / 退群会清掉这个文件的拨号状态。
- `latencyMillis` 只给展示用：评论顺序固定按 `(lamport, author, seq)`（协议 §6），`ts` 和时延都不参与排序。
- `superSeeding` / `setSuperSeeding` 走的是 **per-torrent flag** `torrent_flags::super_seeding`（0x100）：
  2.1.0-39 的 `settings_pack` 里**没有**这个布尔（64 个全查过），只有已废弃的 `strict_super_seeding`。
  真值必须用 `group.superSeeding(fileId)` 读回（它读的是 `torrent::flags()`，编译掉该特性的 native 库里这一位恒为 0）。
  **副作用**：开着它时做种者对外报残缺 bitfield，于是「队友数」`teammates()` 会把做种者算成一个队友（实测 1 → 2），
  见 `docs/reports/e0-superseed.md` §3.3。默认关，实测 T_all 改善只有 3.1%。

### 3.1 缺口收敛（本轮）：创建者公钥 / G3 / G4 / G1

这一节是给宿主（daemon、Android UI）看的**接口签名**。core 这边已经全部实现并落盘，daemon/ 与 app/ 本轮未改，下一轮照这里接线即可。

**① 缺陷：创建者从不登记自己的公钥**

- 症状：建群后本机收到别人回传的 `file.list` 时，自己发布的条目一律 `file_list_rejected reason=no_pub`（证据 `artifacts/m1/log-catchup-a.log`，232 条）。
- 修法：`createGroup` 与 `installJoinedGroup` 都调 `registerPub(deviceId, identity.pub64)`；验签口径抽到 `EntryCheck.reason(pub, entry)`（`PubRegistry.kt`），SelfTest 用生产代码钉住「登记后能验自己的条目、没登记必须是 no_pub」。
- 宿主可见的变化：无（只是不再丢自己的条目）。诊断用 `kernel.pubHexOf(dev)`（未登记返回 `""`）。

**② G3 成员做种数 + 平台（协议只补字段，见 protocol.md §10 的 0x30）**

| 项 | 值 |
|---|---|
| 消息 | `0x30 peer.announce` 追加两个可选字段：`plat`（`android` / `windows` / `unknown`）、`seeds`（u32，自报本群做种文件数） |
| 签名 | 沿用 `peer1` 域 `[dev, gid, btPort, ts]`，两个新字段**不进签名**（口径与 `btHost` 相同）；老实现的 sig 仍然验得过 |
| 发送时机 | 建连绑定后首帧、共享新文件后、下载完成转做种后、休眠摘掉做种后 |
| 收端处理 | 写进成员运行期属性，认不出的 plat 归 `unknown`，负 seeds 夹到 0 |

```kotlin
group.members(): List<MemberRecord>   // MemberRecord(dev, name, nameSeq, pub, platform, seedingCount)
group.seedingFileCount(): Int          // 本机在该群真实做种数（SeedingLedger ∩ files）
group.seedingCountOf(dev: String): Int // 别人取最近一条 announce 的自报值，从未自报为 0；本机取真值
kernel.platform: String                // 构造参数，默认 Proto.detectPlatform()；宿主可覆盖
```

`platform` / `seedingCount` 是**运行期属性**：不写 `state.json`，也不进 `join.ok.members`。对端重连时会立刻补一条 `peer.announce`，所以重启后它们自己会回来；离线成员在重启后、重新握手前显示 `unknown` / 0。

**③ G4 未读数（每群每文件一条读游标）**

游标记「上次读到哪条 op」，键就是协议 §6 的全序 `(lamport, author, seq)`。未读数 = 该文件可见 op（墓碑与被墓碑指向的不出现）里键**严格大于**游标的条数 —— 不存计数器，所以重连补齐、乱序到达、重复广播都不会算错。自己写的评论会把游标一起推进，刚发的评论不会变未读。

```kotlin
// 群对象上（细粒度）
group.unreadCount(): Int              // 整群 = 各文件之和
group.unreadCount(fileId: String): Int
group.markRead(fileId: String): Int   // 打开 P4：清零并返回清掉的条数
group.markAllRead(): Int              // 打开 P2：整群清零
// 内核门面上（宿主只拿得到 groupId 时用，群不存在一律返回 0，不抛）
kernel.unreadCount(groupId: String): Int
kernel.unreadCount(groupId: String, fileId: String): Int
kernel.markGroupRead(groupId: String): Int
kernel.markFileRead(groupId: String, fileId: String): Int
```

持久化：`state.json` 每个群多一个 `read` 数组，元素是 `{fileId, lamport, author, seq}`。旧 `state.json` 没有这个字段，读回来等于「一条都没读过」，不做兼容补算。

**④ G1 退群 / 解散**

```kotlin
kernel.removeGroup(groupId: String): Boolean   // true = 这次真的删了；群不存在/已删过 → false（幂等）
// 事件：KernelEvents.groupRemoved(group)  —— 链接已关、密钥已抹、库已重写之后回调
// 日志：group_removed（gid, name, torrents_stopped, links_closed, files, key_wiped）/ group_remove_noop
```

一次做完五件事：① 该群全部 torrent 从 BT 会话摘掉（磁盘文件**不动**，不删用户数据）；② 关闭该群全部消息链路（`peer_link_closed reason=group_removed`）；③ mDNS 注销；④ `keySeed` 与派生的 `kGrp` 就地清零；⑤ `groupsById` 移除后 `saveState()`，`state.json` 里不再有这个群。

**daemon 下一轮要接的三处**（本轮不动 daemon/）：`groupJson` 的 `unreadCount` 改成 `kernel.unreadCount(g.groupId)`、加一个「打开即清零」的入口（`POST /api/groups/{gid}/read` 或 P4 打开时调 `markFileRead`）；`memberJson` 的 `seedingCount` / `platform` 直接透传 `MemberRecord` 的两个字段（不再写死 0 与「未知」）；新增 `DELETE /api/groups/{gid}` → `kernel.removeGroup`。

### 3.2 做种集合与重启续种（本轮）

**缺陷（真机发现）**：`seedingFileCount()` 只数 `addFile` 加进去的那一份，下载完成（`markCompleted`）不进集合。一台设备下载完并且正在做种，自报 `seeds=0`，P6 上显示「做种 0」（证据 docs/m1-device.md §6-6、artifacts/m1-device/screenshots 里的 `final-members` 截图：桌面电脑、在线、做种 0、Windows）。

根因是「谁在做种」有两套口径：完成判定写 `completed`，做种集合写 `localSeeds`，两边各写各的。现在收成一份：

| 项 | 值 |
|---|---|
| 唯一来源 | `GroupRuntime.seeding`（类型 `SeedingLedger`） |
| 进 | `addFile` 挂进会话之后；巡查判定 `isComplete()` 为真（`markCompleted`）；重启续种成功 |
| 出 | `stopFile`（休眠/暂停）；巡查发现文件被移走删掉、或片上校验后数据不完整；退群 |
| 读数 | `SeedingLedger.count(files.keys)`：只数清单里还有的。`seedingFileCount()`、`peer.announce.seeds`、`seedingCountOf(自己)`、P3 的「做种中」全部读它 |

巡查每 2 s 对账一次（`GroupRuntime.reconcileSeeding`）：文件不在就降级为休眠（`seed_stopped reason=file_missing`），libtorrent 校验后发现缺片也降级（`reason=incomplete`），torrent 不在会话里同样降级（`reason=torrent_missing`）。状态是 checking/pending 时不下判断，连续两拍异常才降，避开挂种瞬间的抖动。降级后界面按「休眠」显示，用户想继续再点激活。

**重启续种（原 G2）**：`state.json` 每个群多一个 `seeding` 数组，元素是 `{fileId, path}`。`M1Kernel.start()` 在 BT 会话起来之后、PeerServer 与 mDNS 之前调 `resumeSeeds()`，逐条判定：

| 判定 | 条件 | 结果 |
|---|---|---|
| `RESUME` | 条目还在清单里、条目带 inline torrent、本地文件在、文件大小与条目一致 | 按原始 torrent 字节挂种，进做种集合，日志 `seed_resumed` |
| `NO_ENTRY` / `NO_TORRENT` | 清单里没有，或条目没有 inline torrent | 降级为休眠，日志 `seed_resume_failed reason=...` |
| `FILE_MISSING` | 记录的路径上没有文件 | 降级为休眠，不崩、不动磁盘 |
| `SIZE_MISMATCH` | 文件在但大小与条目不一致 | 降级为休眠 |
| `activate_failed` | 判定通过但 `add_torrent` 抛异常 | 降级为休眠，日志 `seed_resume_error` |

一轮结束打一条 `seed_resume_done resumed=<n> skipped=<m>`，0 条也打：重启后没有要续种的东西同样是证据。

**为什么是「直接加种」而不是「重新建种」**（二选一，选前者）：`FileEntry.torrent` 是这条 file.add 原始的 info 字典，按它挂种得到的 infohash 与群里其他人看到的完全一致。重新建种（`TorrentBuilder` 再扫一遍文件）是按当前盘上的内容重算，文件被截断或换掉时会得到别人的清单里不存在的新 infohash，等于悄悄发布一个没人认的文件，还会覆盖本地条目。数据完整性不在这一步判，交给 libtorrent 的片上校验，校验不通过由上面那条巡查降级。

**口径与边界**

- 只续「上次在做种」的条目。未完成的下载不自动续传（重启后仍是休眠），避免用户没点激活就偷偷跑流量。想续传就再点激活，已完成的片会保留。
- 路径存在 `state.json` 里，因为共享文件的目录是用户选的，内核不猜。真机上共享副本在应用私有目录、下载在 `downloads/`，两条都在。
- 续种成功时先按「文件在且大小对」乐观进集合，片上校验随后判定；校验发现缺片的由巡查降级，这段时间里本机不会给对端发坏片（libtorrent 校验没过的片不对外提供）。
- 没有链接时不自报 `peer.announce`，对端在握手时（`PeerLink.onBound`）会拿到最新的 `seeds`。

**本轮回归证据**（`bash scripts/m1-headless.sh`，脚本未改，35 条 CHECK 全 PASS，自检 110 条另计）：B 角色的日志里有一整条状态机读数

```
M1 B seeding_transitions result=pass before_download=0 after_download=1 after_hibernate=0 after_reactivate=1 after_add=2 after_restart=2 after_restart_file_missing=1 missing_role=NONE after_torrent_detached=0
```

对应四个阶段：下载完成 0→1（原缺陷）、`stopFile` 休眠回到 0、再激活回到 1；关掉内核再按同一份 `state.json` 起来，两条在做种的条目都续上（`seed_resumed` 两行、`seed_resume_done resumed=2`）；删掉其中一条小文件的本地副本再重启，它 `reason=file_missing` 降级为休眠、`localRole` 回到 `NONE`，另一条照常续种，进程不崩。最后把下载来的那条 torrent 从会话里摘掉（Windows 上做种中的文件被句柄占着删不掉，只能走这条同源路径），巡查两拍内降级（`seed_stopped reason=torrent_missing`），做种数回到 0。

### 3.3 冷启动重连与完成推送（本轮 M1.4）

两个缺口都在真机上暴露过：**重启后不主动连回群成员**、**下载完成要等巡查节拍才推状态**。
放在同一节是因为它们改的是同一件事 —— 状态不再由「等下一拍」决定：前者由重连器主动发起，
后者在判定当场推事件。

**① 冷启动主动重连（缺口一）**

缺陷现场：`docs/m1-device.md` §2.7 与 `artifacts/m1-device/logs/app-logcat.log`。消息层只在「加入群」时建链，
进程被杀再起来之后群还在本地库里，但没有任何一条主动连出去的链路，P6 上对端的平台与做种数一直是
「未知 / 0」，直到对方先连过来。

`M1Kernel.start()` 恢复群之后，对**每个从 state.json 恢复出来的群**起 `PeerReconnector`（`Reconnect.kt`）：

| 项 | 口径 |
|---|---|
| 目标群 | 只在 `start()` 时从 `state.json` 载入的群（`restored_groups=N`、`reconnect=on`）。刚建 / 刚加入的群不进重连器 —— 它们的链路本来就由加入流程建立，让重连器也去拨会跟加入流程抢同一条对端 |
| 候选顺序 | ① 上次成功连过的端点（`state.json` 的 `peerAddrs`）→ ② 上次加入时用过的手动 IP:port（`state.json` 的 `manual`）→ ③ mDNS 浏览（按 TXT 的 `gid` 归群，排除本机自己那条实例） |
| 退避 | 一轮候选全部失败后等 1 / 2 / 4 / 8 / 16 / 30 s（30 s 封顶），成功建链立刻归零 |
| 并发 | 每群最多 **3** 条在拨（`ReconnectPolicy.MAX_PARALLEL_DIALS`）；mDNS 浏览另有一条线程，每 10 s 刷一次，不占拨号节拍 |
| 连上之后 | 不重新 join：双向 hello 验过、群成员身份两端都认，`PeerLink.onBound` 直接挂到群上、互发 `peer.announce` 与 `sync.req`，于是对端的平台与做种数当场补回来 |
| 落盘的新来源 | `peerAddrs` 只记「本机拨出去并绑定成功」的端点（入向链路拿到的是对端临时源端口，记了没用）；`manual` 是 join 成功时把本次的手动候选原样记下。端点按 `host:port` 存，IPv6 字面量（如 `fe80::1%wlan0`）存回来解析不出来，会被跳过，由 mDNS 兜底 |

重复链路：两端同时拨号时会短暂出现两条链路。`GroupRuntime.attach` 按「同向留新的、异向按 deviceId 定序」裁决，
两端算出同一条胜者（否则你关我的、我关你的，两条都不剩），被弃的那条以 `peer_link_closed reason=duplicate_link` 收尾。

```kotlin
// 宿主（P6 成员页）只需要这一个回调：在线状态、平台、做种数一次读齐
override fun peersChanged(group: GroupRuntime, deviceId: String, online: Boolean) {
    refreshMembers(group)   // group.members() / group.onlinePeers()
}
```

可 grep 的日志：`reconnect_started`（backoff_ms / max_parallel / tick_ms）、`reconnect_lan_scan`、
`reconnect_attempt`（addrs / sources）、`peer_reconnected`（addr / source / elapsed_ms）、`reconnect_backoff`、
`reconnect_no_candidates`、`reconnect_dial_failed` / `reconnect_dial_unbound`；链路上线本身仍是原来的
`peer_bound`（带 role=in/out）与 `peer_link_closed`，harness 把 `peersChanged` 打成了 `peers_changed`。

**② 完成判定当场推事件（缺口二）**

缺陷现场：下载完成后 P3 停在「下载中 100%」，要等下一次巡查（最多 2 s）才变「做种中」（`docs/m1-device.md` §2.5）。
根因不是界面丢状态 —— 完成判定本身只在 `huchuan-monitor` 的 2 s 巡查里做。

现在完成判定有自己的一条线程（`huchuan-complete`），两条路走**同一个** `M1Kernel.completeFile`：

| 路径 | 触发 | 时延 |
|---|---|---|
| `source=alert` | libtorrent 的 `torrent_finished_alert`（`BtSession.onTorrentFinished`）当场入队 | 告警线程 → 完成线程，毫秒级；但 libtorrent 自己是在 session tick 里发这条告警，实测常被兜底抢先 |
| `source=sweep` | 完成线程 `poll(200 ms)` 超时后扫一遍「挂进会话且还没进做种集合」的文件 | ≤ 200 ms（本轮实测事件比「本机第一次看到 `isComplete()`」还早 135 ms） |
| `source=monitor` | 2 s 巡查兜底（幂等，正常情况下此时已经完成过） | 不再承担时效 |

`completeFile` 幂等（`GroupRuntime.markCompleted` 的 `first` 位去重），所以告警与兜底撞在一起也只出一次事件。
事件顺序照旧：进做种集合 → `KernelEvents.fileCompleted` + `download_completed`（带 `source=`）→ 做种数变了才 `peer.announce`。

**边界**

- 只续「上次在做种」的条目、只重连「恢复出来的群」：未完成的下载重启后仍是休眠（见 §3.2），刚建的群也不会被重连器多拨一次。
- 对端离线超过 30 s 后重连器按 30 s 一拍一直重试（没有上限、没有退化成不重连）：P6 的「自动重连」是产品口径（AGENTS.md §4-7），要停只能退群。
- 重连不校验对端是否是成员：真正的准入仍是 hello 的成员分支（`g.members().any { it.dev == peerDeviceId }`），非成员连上来只会走到 join.req 那条路。

### 3.4 组队的两个阻塞缺陷、队友数（本轮 M1.5）

组队（多人同时下载同一文件、互相供片）在代码层面曾经**不成立**。两个缺陷都在内核的「谁跟谁连着」这一层，不是一个新功能。

**① 缺陷：`connectSeeders` 的「有 peer 就整轮不拨」**

现场：`GroupRuntime.connectSeeders` 先算 `hasPeers = bt.peers(fileId) > 0`，只要这个 torrent 已有 1 条 peer 连接就整轮跳过。于是单做种者先被第一个下载者连上之后，第二个下载者与第一个永远不互连 —— `numPeers` 恒为 1，队友之间拿不到互补的片。

修法（`MemberDialPolicy` + `GroupRuntime.connectSeeders`）：

| 项 | 口径 |
|---|---|
| 拨号单位 | **每个成员一次**，不是「这个 torrent 有没有 peer」 |
| 已经互连的成员 | 跳过，不重拨（同一成员重复调用 `connectSeeders` 幂等）；「互连」以 libtorrent 的 peer 列表为准 |
| 退避 | 直接复用冷启动重连那张表 `ReconnectPolicy.backoffMs`（1/2/4/8/16/30 s，30 s 封顶），不另立参数 |
| 预算 | 每轮最多 `ReconnectPolicy.MAX_PARALLEL_DIALS`（3）个新拨号 |
| 状态清理 | `stopFile` / 做种降级 / 退群都清掉该文件的拨号状态，否则休眠完再激活会被退避窗口卡住 |
| 端点变了 | 成员重启换了 BT 端口时按新端点重新开始计数 |

证据：`artifacts/e0/` 的三进程实验里两个下载者 `peers_max=2`（做种者 + 队友），C1 向 C2 上传 **191,807,488** 字节（`M1 C1 transfer_stats … payload_uploaded=191807488`）—— 互助真的发生了。

**② 缺陷：两端同时拨号 → 互相把对方的链路关掉 → 永不 attach**

现场：`artifacts/m1-device/reconnect-race-evidence.txt`（真机 Mi MIX 2S + 桌面）。两端都带重连器时，双方各拨一条，`peer_link_closed reason=duplicate_link` 反复出现；被关掉的一侧只会按 30 s 退避重拨，P6 一直停在离线。

根因有两层，都修在 `GroupRuntime` / `PeerConnector`：

| 层 | 修法 | 代码 |
|---|---|---|
| 僵尸链路参与裁决 | 裁决前先看旧链路是否已经关闭；关掉的不参与裁决（`peer_link_dead_replaced`），不会再出现「留一条死链路、把每条新的健康链路都判成 duplicate」 | `GroupRuntime.attach` |
| 拨号方没有「我已经被对方接住」的认知 | 自己这条链路被 duplicate 关掉之后，如果同一成员已经有一条**活**链路，就直接认它：补发 `peer.announce` + `sync.req`（都幂等），记 `reconnect_link_race_settled` 并按成功收工，**不进退避** | `PeerConnector.settleRace`、`GroupRuntime.settleOnExistingLink`、`PeerLink.announceAndSync` |

断言（`bash harness/reconnect-race.sh`，harness 自己的装置，不进 scripts/）：两端带着本地库同时起来、在同一个毫秒各掐一次链路让**生产重连器**各拨一条，判据三条 —— ① 10 s 内双方 `race_attached ok=true`（P6 在线双向 true）；② 撞车那一波收尾（`settle_ms=5000`）之后 60 s 内 `link_closed_tail=0`、`offline_tail=0`；③ `race_settled ≥ 1`（兜底真的走到过，不是「碰巧没撞」）。

反向验证：`bash harness/reconnect-race.sh --mutant no_link_fallback`（`-Dhuchuan.mutants=no_link_fallback` 关掉兜底）→ `race_settled=0`，断言 FAIL，脚本打 `PASS (mutant … 按预期让断言 FAIL)`。局限写在 docs/reports/e0-team.md：loopback 上没有半开连接，对称竞态本身能收敛，所以端到端的「永不 attach」症状要真机切断网络才复现；这条断言钉的是「兜底路径必须走到且撞完不再抖」。

**③ 队友数（观测口径，产品规格 §306）**

```kotlin
kernel.teammates(groupId: String, fileId: String): Int   // 群/文件不存在返回 0
group.teammates(fileId: String): Int
```

| 项 | 口径 |
|---|---|
| 定义 | 当前与该文件 torrent **实际互连**、且本机处于「部分」的**成员数**，不含做种者 |
| 数据来源 | libtorrent 的 peer 列表（`TorrentHandle.peerInfo()` → `BtSession.peerSnapshot`）；不看成员表推算、不新造协议字段、不用 0x53 |
| 互连判定 | `peer_info` 的 `connecting` / `handshake` 位都没置才算互连 |
| 做种者排除 | `peer_info` 的 `seed` 位，或对端完成度达到 1e6 |
| 成员匹配 | `peer_info.ip()` 是带 scheme 的端点串（`tcp://127.0.0.1:53513`），归一化成 `host:port` 后先按 host:port 精确配对（本机拨出去看到的正是对方监听端口），对不上再按 host 逐条分配（入向连接拿到的是临时端口）。**不归一化会恒为 0**——harness 实测踩过 |
| 本机「部分」 | 挂进会话且 `isFinished()` 为假；到 100% 立刻退组（`teammates=0`） |
| 读法 | 轮询读口（无事件）。`huchuan-monitor` 每 2 s 顺手算一次：第一次采到打 `teammates_first_sample`，之后只在变化时打 `teammates_changed count=N peers=M`；`download_progress` 每行都带 `teammates=`。算不出来（异常）打 `teammates_probe_error`，不吞 |

宿主不需要新事件：P3 的行内 chip / P4 的标题按需调 `kernel.teammates` 即可；文件到 100% 时同一个调用自动回到 0。

### 3.5 跨网发现（tracker，本轮 M1.6）

内核自己就是 tracker 客户端。**默认关**：只有宿主给了 `trackerBaseUrl` 才工作，且代码里没有写死的线上地址。

```kotlin
// 两种给法的效果一样；都必须在内核起来之前
val kernel = M1Kernel(dataDir, deviceName, 47100, 51413, events, trackerBaseUrl = "https://<worker 域名>")
// 或者
kernel.setTrackerBaseUrl("https://<worker 域名>")   // 起来之后再调 → 忽略并记 tracker_config_ignored
kernel.start()

kernel.trackerBaseUrl    // 规范化后的基址（尾部斜杠已去掉）；空串 = 关闭
kernel.trackerEnabled    // 真的在工作（配置有值 + 启动完成 + 没关）
group.candidates()       // 候选表快照：manual/peer → lan → tracker，已按 §3.4 去重
```

| 项 | 口径 |
|---|---|
| 什么时候注册 | `createGroup` 与 `installJoinedGroup` 各调一次 `TrackerService.watch(group)`；`start()` 时对 `state.json` 恢复出来的群补一次。`removeGroup` 停掉该群并尽力补一条 `event=stopped` |
| info_hash | `groupHash20 = SHA-256(UTF8("huchuan/gh1/v1") ‖ codeBytes)[0..20]`（协议 §2.2）。**不是文件的 infohash**：文件 swarm 那边 DHT / PEX / LSD 仍然全关（协议 §8），私有群的 infohash 不会出现在任何公网发现面上 |
| 注册 | `POST {base}/api/tracker/register`，JSON `{infoHash, gid, passkey, prevPasskey?}`（口径以 `worker/README.md` + `worker/src/share.js` 为准） |
| passkey | `hex(HMAC-SHA256(kGrp, UTF8("huchuan/trk1/v1") ‖ UTF8(gid) ‖ UTF8(小时整数十进制)))`，小时 = `floor(unix 毫秒 / 3600_000)`（UTC，与本地时区无关）。服务端只存它的哈希，从不看原文 |
| 轮换 | 每小时换值：先重新注册（`prevPasskey` = 服务端记着的那一把）再 announce；注册成功前继续用旧那把 announce。断线跨过整点时按最近 6 小时逐个补连续性证明（`tracker_register_backfilled hours_back=N`）—— worker 只认「上一把」的哈希，这是「先到先得 + 每小时轮换」的固有代价 |
| announce | `GET {base}/announce?info_hash=<%XX 20 字节>&peer_id=<%XX>&port=<消息层端口>&left=0&compact=1&numwant=32&event=started\|none\|stopped&passkey=<64 hex>`。`info_hash` / `peer_id` 按字节逐个 `%XX`：交给 `URLSearchParams` / `URLEncoder` 会按 UTF-8 解坏二进制 |
| 端口 | **消息层端口**（`kernel.localMessagePort()`），不是 BT 端口：tracker 上的群级伪 swarm 登记的是「谁能跟我建消息层链路」（协议 §3.2） |
| interval | 以服务端下发为准，夹在 `[max(min interval, 300), 3600]` 秒；没下发就用 600。下一拍同时不超过「本小时结束 + 1.5 s」，保证轮换赶在整点后第一拍 |
| peers 去向 | `GroupRuntime.setTrackerCandidates`（整体替换，只活在内存里）→ `reconnectCandidates` → `CandidateMerge`。同键时 manual / peer（3）> lan（2）> tracker（1）；每群上限 128 |
| 失败姿态 | 不抛、不弹窗、不影响局域网：注册/announce 失败按 30 s 退避重试，日志 `tracker_register_failed` / `tracker_announce_failed` / `tracker_announce_rejected`；服务端回 `bad passkey` 时清掉本地记住的那把，下一拍重新注册 |
| 打码 | 所有日志与事件里的 passkey 只留前 4 个字符（`TrackerProtocol.mask` → `abcd****`）。验收里有一条硬断言：整轮事件里**不许**出现 passkey 原文 |
| 关闭时 | baseUrl 为空 / 非法 → `tracker_disabled reason=base_url_empty\|base_url_must_be_http`，不建 `TrackerService`、不发任何请求 |

可 grep 的内核事件（字段名即日志名）：

| 事件 | 什么时候 | 关键字段 |
|---|---|---|
| `tracker_disabled` | 配置为空 / 非法 | `reason` |
| `tracker_service_started` | 服务起来（baseUrl 有值） | `base` |
| `tracker_started` | 某个群的 announcer 线程起来 | `gid` `base` `info_hash` |
| `tracker_register` | 注册成功（首次 / 轮换 / 补连续性） | `gid` `passkey_masked` `prev_masked` `created` `rotated` `hour` |
| `tracker_announce` | announce 成功 | `gid` `peers` `candidates` `interval` `external_ip` `passkey_masked` `event` |
| `tracker_announce_rejected` | 服务端回 `failure reason` | `reason` `passkey_masked` |
| `tracker_stopped` | 关内核 / 退群时补的 `event=stopped` | `gid` `passkey_masked` |

验收（harness）：`MockTracker` 用 JDK 自带的 `com.sun.net.httpserver` 起一个本机最小 BEP 3 tracker，
`TrackerSelfTest` 起真内核断言「注册参数 / announce 参数 / peers 并入候选表 / passkey 打码 / stopped / 空 baseUrl 零请求」。
**不用线上 Worker 做测试**：本机访问 `*.workers.dev` 被 DNS 污染 / SNI 拦截，而且线上 peer 表不可控、不可重复。
零请求那一条用 JVM 的 `ProxySelector` 记账（每次 `HttpURLConnection` 都会被问到），所以「没发请求」是可证伪的，
不是「我没看见」。

### 接 UI（下一轮）

- 一个进程一个 `M1Kernel`，放在 Application / daemon 单例，`start()` 一次；`close()` 走生命周期。
- 回调线程 = 网络线程 / 巡查线程，**必须切到 UI 线程**再改 Compose state。
- `joinByTail8` 是阻塞的（最多等 timeout），要放到 IO 协程/工作线程；P5 显示「加入中」。
- 现有 `GroupStore`/`InMemoryGroupStore` 是静态壳的假实现；接 UI 时把 P2/P3/P4 的读路径换成 `kernel.groups()` + `GroupRuntime`，不动 `Model.kt` 的展示类型即可。
- 评论排序用 `visibleOps()` 的返回顺序（已按 lamport/author/seq 排好），UI 不要再按时间排。

## 4. 线程模型

| 线程 | 干什么 | 注意 |
|---|---|---|
| `huchuan-accept` | accept 消息层连接 | 每条连接再起一条 `huchuan-link-in` |
| `huchuan-link-in/out` | 读帧、分发、写帧（写有锁） | 30 s ping、120 s 无帧断 |
| `huchuan-monitor` | 每 2 s：dht 心跳、做种集合对账、进度打点、补连做种者；每 30 s ping | 所有群的轻量巡查；对账只碰本机的集合，不改群清单。完成判定**不再**靠它（见下一行） |
| `huchuan-complete` | 完成判定快通道：`torrent_finished_alert` 入队即判，空闲时每 200 ms 兜底扫一遍在下的文件 | 与巡查走同一个幂等的 `M1Kernel.completeFile`；事件顺序与做种集合都只在它和巡查的交叉点上推进 |
| `huchuan-reconnect` | 冷启动重连的调度（250 ms 一拍）：按退避与并发上限（每群 3）派拨号 | 只对 `start()` 时恢复出来的群工作；有在线链路时立刻把退避归零 |
| `huchuan-reconnect-lan` | 每 10 s 刷新一次 mDNS 候选（按 gid 归群） | 浏览要 2–3 s，不能挂在拨号节拍上 |
| `huchuan-reconnect-dial` | 一条候选一个线程：TLS + hello 绑定，等 `isBound` 最多 6 s | 失败只影响这一条候选，退避由调度器统一记 |
| `huchuan-tracker-<gid 前 4>` | 每个群一条：注册（小时轮换）→ announce → 券商候选；睡到「服务端 interval」与「本小时结束」里更早的那个 | 睡得分段（500 ms 一步），内核一关 ≈0.5 s 内退出；只在 `trackerBaseUrl` 非空时存在 |
| 调用方线程 | `createGroup`/`addFile`/`comment` 同步执行 | `joinByTail8` 阻塞；`kernel.close()` 里会等最多 2 s 把 `event=stopped` 发出去 |

群内状态用 `GroupRuntime` 内部的锁保护；跨群/公钥/持久化用 kernel 的 `stateLock`。

写帧只有一种口径：`send()`／`sendQuietly()` 写失败只丢当前这一帧，记 `peer_send_failed`（带 `consecutive=` 与 `limit=`），连接保留；连续 `MAX_CONSECUTIVE_SEND_FAILURES = 3` 帧都写不出去才 `peer_link_closed reason=send_failures`。之前是任何一帧写失败就 `close()`，广播里一条超限帧会把该成员整体踢下线，还得重走 hello/join/公钥登记。

## 5. 依赖与构建

| 制品 | 坐标 | 作用域 |
|---|---|---|
| libtorrent4j | `org.libtorrent4j:libtorrent4j:2.1.0-39` | `:core` **compileOnly**，不进 APK |
| libtorrent4j-windows | `org.libtorrent4j:libtorrent4j-windows:2.1.0-39` | 只进 `m1Libs`（harness 运行期），解出 `libtorrent4j.dll` |
| JmDNS | `org.jmdns:jmdns:3.6.2` | `:core` implementation（纯 Java，Android 也能用） |
| slf4j-nop | `org.slf4j:slf4j-nop:2.0.7` | 只进 `m1Libs`（压掉 JmDNS 的 no-provider 提示） |

版本全部写进 `gradle/libs.versions.toml`。`:core` 里另建了可解析配置 `m1Libs` 与任务 `m1Deps`/`m1Native`，保证桌面制品不进 `:app`；`:app:assembleDebug` 仍通过（12.0 MB）。

## 6. 协议实现对照（M1 15 个消息）

0x01 hello ✓ · 0x02 join.req ✓ · 0x03 join.ok ✓ · 0x10 sync.req ✓ · 0x11 file.list ✓ · 0x12 file.add ✓ · 0x20 log.sync ✓ · 0x21 log.ops ✓ · 0x22 log.op ✓ · 0x23 member.name ✓ · 0x30 peer.announce ✓（本轮补可选字段 plat / seeds，见 §7-10）· 0x40/0x41 ping·pong ✓ · 0x42 bye ✓ · 0x7F err ✓

M2 的 5 个类型（group.rotate / log.del / file.del / team.part / tracker.hint）未实现，收到静默忽略（协议 §5「未知 type 静默忽略」）。

已实现的硬约束：帧上限、未知字段忽略、非成员只允许 hello/join.req/ping/pong/err/bye、join.req 的 ts ±300 s、nonce LRU 256、HMAC 常量时间比对、join 限速、帧限速、评论验签与去重、墓碑（kind=2）接受并隐藏、按 `(lamport, author, seq)` 全序、join.ok 验签失败即断开、file.list 条目验签、公钥登记前校验 deviceId。

### 口径与常量（以代码为准，这里只做速查）

| 项 | 口径 | 代码 |
|---|---|---|
| join.ok 签名 | domain `huchuan/joinok1/v1`，字段 `[dev, gid, keySeed, fp, nonce, ts]`；收端用 hello 阶段登记的应答方公钥验，验不过 `E_SIG` + 断开 + 不装群（`join_ok_rejected reason=dev_mismatch\|no_pub\|bad_sig\|nonce_mismatch`），nonce 必须回显本轮 join.req | `Domains.JOIN_OK`、`PeerLink.onJoinOk` |
| file.list 条目 | 每条 entry 原样带该文件 file.add 的原 `sig`（`file1` domain）；按 `addedBy` 的公钥验，验不过丢弃：不入库、不计入 fdigest（`file_list_rejected reason=no_sig\|no_pub\|bad_sig`） | `FileEntry.sig`、`GroupRuntime.onFileListEntry` |
| 公钥分发 | hello、join.ok.members、member.name 三处都带 `pub`（128 hex，64 B 裸值）；登记前先算 `deviceId(pub) == dev`，对不上的整条丢掉（`member_name_bad_pub`、`join_ok_member_bad_pub`） | `M1Kernel.registerPub`、`installJoinedGroup` |
| 评论时延 | 本机时钟口径：`latencyMillis = 本机收到时刻 − (对端 ts − 时钟偏移)`；偏移取历次 `对端 ts − 本机收到时刻` 的最大值（单程时延最小的那一次），只用 hello/join.req/join.ok/peer.announce/member.name 校准。只展示，排序固定 `(lamport, author, seq)` | `M1Kernel.latencyMillis` |
| 单帧发送失败 | 只丢这一帧并记 `peer_send_failed`；连续 `MAX_CONSECUTIVE_SEND_FAILURES = 3` 帧失败才断链（`peer_link_closed reason=send_failures`） | `PeerLink` companion |
| 单文件限速 | `setUploadLimitKbps(fileId, n)` 的单位是 **KB/s**：内部 `setUploadLimit(n × 1024)` 字节/秒，0 = 不限。早先按 kbit/s 算（`×1024/8`），实际限速只有标称值的 1/8 | `BtSession.setUploadLimit` |
| 会话级（P7 全局）限速 | 入参 **KB/s**（0 = 不限），内部 **×1024** 得字节/秒交给 libtorrent（`upload_rate_limit` / `download_rate_limit`）。走 `applySettings` 下发，**运行期立刻生效**，作用在本会话的带宽通道上，会话里所有 torrent（含已在传的）一起受约束；与单文件限速同时存在时每个 torrent 取更小的那个。读回读的是会话里生效的值（`session::get_settings()` 同步到会话线程），不是缓存。**私网段与回环段在建会话时被并进 global peer class** —— libtorrent 默认不限额局域网/回环对端，不并类的话限速在局域网上等于没设 | `BtSession.setSessionUploadLimit` / `bindLocalPeersToGlobalClass`、`M1Kernel.setSessionUploadLimit` |
| join 限速 | 计数单位是**校验失败次数**（滑动窗口，成功不计数）：5 次/10 min/源 IP、30 次/h/群 | `JoinRateLimiter` |
| 帧限速 | 60 帧/s/连接，每秒重置；超了回 `E_RATE` 并断开 | `PeerLink.MAX_FRAMES_PER_SECOND` |
| connect_peer 拨号（M1.5） | **每成员一次**，去重键 `fileId@dev`；已互连不重拨，未连上的按 `ReconnectPolicy` 退避（1/2/4/8/16/30 s 封顶）重试，每轮最多 3 个新拨号 | `MemberDialPolicy`、`GroupRuntime.connectSeeders` |
| 链路撞车（M1.5） | 裁决前把已关闭的旧链路当不存在（`peer_link_dead_replaced`）；自己这条被 duplicate 关掉而同一成员还有活链路时，直接沿用并补 `peer.announce`+`sync.req`（`reconnect_link_race_settled`），不进退避 | `GroupRuntime.attach` / `settleOnExistingLink`、`PeerConnector.settleRace` |
| 队友数（M1.5） | 观测口径：libtorrent peer 列表里与本机互连、不是做种者、且本机处于「部分」的成员数；端点串先归一化（`tcp://h:p` → `h:p`）再按 host:port 配成员 | `BtSession.peerSnapshot`、`GroupRuntime.teammates`、`M1Kernel.teammates` |
| keySeed 落盘 | `secretBox.seal(keySeed)` + `keySeedProt`；方案认不出或打不开就跳过该群，不回退明文 | `SecretBox.kt`、`M1Kernel.unsealKeySeed` |
| 创建者公钥 | 建群与入群都 `registerPub(自己)`；条目验签的 reason 口径 `no_sig` / `no_pub` / `bad_sig` 只写在 `EntryCheck` 一处 | `PubRegistry.kt`、`M1Kernel.createGroup` |
| 成员平台 / 做种数（G3） | `peer.announce` 的可选字段 `plat`（android/windows/unknown）与 `seeds`（≥0）；**不进 peer1 签名域**，认不出/缺省按 unknown / 0 | `Proto`、`GroupRuntime.onPeerAnnounce` |
| 未读游标（G4） | 每群每文件一条 `ReadCursor(lamport, author, seq)`；未读 = 键严格大于游标的**可见** op 数；自己写评论也推进游标；落盘 `state.json` 的 `read` | `ReadCursor` / `Unread`、`GroupRuntime.unreadCount` |
| 退群（G1） | `removeGroup` 幂等：停该群全部 torrent、关全部链路、mDNS 注销、抹 `keySeed`+`kGrp`、`saveState` 后库里不再有该群；磁盘文件不删 | `M1Kernel.removeGroup`、`GroupRuntime.purgeForRemoval` |
| 做种集合（M1.3） | 「本机在做种」只有一份：`addFile` 挂进会话、下载完成（`markCompleted`）、重启续种成功三处进；`stopFile`、文件被移走删掉、校验后缺片、退群四处出。做种数、`peer.announce.seeds`、P3 状态同一个读数 | `SeedingLedger`、`GroupRuntime.seedingFileCount` |
| 冷启动重连（M1.4） | 只对 `start()` 时从 `state.json` 恢复的群；候选 = 落盘端点 → 手动地址 → mDNS（按 gid 归群）；退避 1/2/4/8/16/30 s（30 s 封顶）、每群并发 3；连上走 hello 绑定，不重新 join | `ReconnectPolicy`、`PeerReconnector`、`GroupRuntime.reconnectCandidates` |
| 完成推送（M1.4） | `torrent_finished_alert` 入队即判 + 200 ms 兜底巡查；两条路同一个幂等的 `completeFile`，事件在判定当场推，不再等 2 s 巡查。日志 `download_completed source=alert\|sweep\|monitor` | `BtSession.onTorrentFinished`、`M1Kernel.completeFile`、`M1Kernel.COMPLETION_TICK_MS` |
| 跨网发现的 info_hash（M1.6） | `groupHash20 = SHA-256(UTF8("huchuan/gh1/v1") ‖ codeBytes)[0..20]`（20 字节 `%XX` 进查询串）；**与文件 infohash 无关**，文件 swarm 侧 `0x380000`（DISABLE_DHT\|DISABLE_LSD\|DISABLE_PEX）不变 | `TrackerProtocol.groupHash20`、`TrackerHttp.announce` |
| 跨网发现的 passkey（M1.6） | `hex(HMAC-SHA256(kGrp, UTF8("huchuan/trk1/v1") ‖ UTF8(gid) ‖ UTF8(小时十进制)))`；小时 = `floor(unix ms / 3600_000)`。拼接是**普通 UTF-8 字节**（与 worker 的 `tokenHashHex` / `passkeyHashHex` 同一口径），不是 signBytes 的长度前缀格式。日志一律 `TrackerProtocol.mask`（前 4 字符） | `TrackerProtocol.passkeyHex`、`Domains.TRACKER` |
| 跨网发现的节奏（M1.6） | 注册后立刻 announce（`event=started`，之后 `none`）；interval 取服务端下发值夹到 `[max(min interval,300), 3600]`，缺省 600；下一拍不超过「本小时结束 + 1.5 s」；失败 30 s 退避 | `GroupTracker.tick` / `nextDelay` |
| 候选合并（M1.6） | 四段来源按 §3.4 合并：manual/peer(3) > lan(2) > tracker(1)，同键留高优先级、同级留先出现的，非法端口丢弃，每群 128 | `CandidateMerge`、`GroupRuntime.reconnectCandidates` |
| 完成事件与日志的顺序（M1.5） | `completeFile` **先写 `download_completed` 日志行、再推 `fileCompleted` 事件**：宿主常在回调里立刻读 `source`，反过来写会出现「事件已到、source 还是空」的窗口（harness 因此把快通道误判成慢通道过一次） | `M1Kernel.completeFile` |
| 在线成员事件（M1.4） | `KernelEvents.peersChanged(group, dev, online)`：只在连接表变化（`attach` / `detach`）时回调，重连成功同样是一次 `online=true`；宿主据此重读 P6 | `KernelEvents.peersChanged`、`GroupRuntime.attach/detach` |
| 冷启动重连的落盘 | `state.json` 每群多两个字段：`peerAddrs`（`[{dev, addr}]`，只记本机拨出去并绑定成功的端点）与 `manual`（`[host:port]`，join 时用过的手动地址）。旧库缺这两个字段 → 重连改走 mDNS | `GroupRuntime.toStoreJson`、`M1Kernel.loadState` |
| 重启续种（原 G2） | `state.json` 的 `seeding` 数组（`{fileId, path}`）。逐条判定「条目在、inline torrent 在、文件在、大小对」才挂种；其余降级为休眠，日志 `seed_resumed` / `seed_resume_failed reason=entry_missing\|no_torrent\|file_missing\|size_mismatch\|activate_failed`。只续做种，未完成的下载不续传 | `SeedRestore`、`GroupRuntime.resumeSeeds`、`M1Kernel.start` |

## 7. 协议缺口与实现取舍（请协议 owner 裁决）

这几条协议 v0 没写死或自相矛盾，实现里做了选择，**写在这里等裁决**：

1. **hello 签名覆盖双方 nonce，单帧做不到。** §1.2 的字段是 `[dev, gid, 本端 nonce, 对端 nonce, chanId]`，而 hello 是「建连首帧」。实现：双方先各发一条 `sig=""` 的 hello 交换 nonce，再各发一条带 `sig` 的 hello；两条都是 0x01。建议协议把这一步写显式（或加一个 hello.ack 类型）。
2. ~~join.ok 的 sig 没有定义 domain 和字段顺序~~ **已收敛（M1.1）**：按协议 §10 用 `huchuan/joinok1/v1` + `[dev, gid, keySeed, fp, nonce, ts]` 签，收端用 hello 阶段登记的应答方公钥验，验不过 `E_SIG` + 断开 + 不装群，并核对 nonce 回显。`huchuan/join1/v1` 现在只用于 join.req 的 HMAC 和邀请码派生 kJoin 的 HKDF info —— 两者混用会让域分离失效，改任一处都会断掉所有旧码。
3. ~~file.list 的条目没有签名~~ **已收敛（M1.1）**：`FileEntry` 自带 `sig`，file.add（帧级 `sig` 与条目内签名都发）和 file.list（每条 entry 原样带）都覆盖；收端按 `addedBy` 的公钥验 `file1` 签名，验不过的条目丢弃、不入库、不计入 fdigest，对端下一轮 sync 还能把它要回来。残留风险：`magnet` / `torrent` 不在 `file1` 签名覆盖范围（协议 §7 只签 fileId/name/size/pieceSize/addedBy/addedAt），换掉 torrent 字节要靠下载后的 SHA-256 兜底。
4. ~~成员公钥只有 hello 一条分发路径~~ **已收敛（M1.1）**：join.ok.members 与 member.name 都带 `pub`，转发 member.name 时也带；接收方先算 `deviceId(pub) == dev` 再登记，对不上的整条丢掉。离线补齐时不再出现「作者没握过手就验不了签」。
5. **hello 只能带一个 gid。** 多群设备本实现只声明「唯一那个群」；多群同网段时要按 gid 分别建连，现实现未覆盖。
6. **keySeed 落盘方案。** 包裹接口 `SecretBox` 已落地（见 §2）：Android 走 `KeystoreSecretBox`（`state.json` 里只放 token + 方案名），桌面 harness 仍是 `PlaintextSecretBox`，Windows 桌面 daemon 的 PKCS12 未做。真机上「keySeed 不落明文」这条已满足（`m1-device.sh` 断言 `keyseed_box=keystore-aes-gcm-1`）；Windows 侧排 M1.5。
7. **ip_filter 走 swig 的 session_params 注入**（best-effort）：libtorrent4j 2.1.0-39 的高层 `SessionManager` 没有直接入口，实现里用 `session_params.setIp_filter` 在启动前塞进去；失败就退回「私有种子 + DHT/PEX/LSD 全关」。
8. **kind（u8）在 signBytes 里按 8 字节整数编码**，遵循 §1.2「整数固定 8 字节」；如果将来要严格 u8，需要协议明确。
9. **magnet 加种未实现。** M1 只用 file.add 的内联 .torrent（32 MiB / 64 KiB 片 ≈ 15 KB base64，远低于 47 KiB 上限）。§11-14 要求两条路径都支持，M2 补 magnet（需要 ut_metadata 先拿到元数据才能 `connect_peer`）。
10. **成员平台与做种数走 0x30 的两个可选字段，且不进签名域。** 协议 §10 的 peer1 域固定在 `[dev, gid, btPort, ts]`，同一条消息里的 `btHost` 本来就不进签名；本轮把 `plat`（android/windows/unknown）与 `seeds`（自报本群做种数）按同一口径补在 0x30，已写进 protocol.md §10。代价是这两个值自报、不防篡改（能被改的只是 P6 上显示的数字）；收益是不动既有签名域，老实现的 sig 仍然验得过，不需要动帧版本位。成员表里另有「谁是成员」这条判定，始终由 join.ok / 消息层链路决定，不看这两个字段。

## 8. 退化点（实现里真实存在，不是设想）

1. **mDNS 不可用 → 手动 IP:port。** JmDNS 3.6.2 在本机两进程实测可用（`join_candidates lan=1`，加入走 `source=lan`）。若运行环境没有组播（虚拟网卡、被防火墙拦、Android 没拿 MulticastLock），浏览结果为空，`joinByTail8` 自动退到调用方给的手动候选；日志 `join_candidates lan=0` 且 `join_via source=manual`。脚本对 `lan>=1` 是**硬断言**：`lan=0` 直接 `CHECK FAIL mdns.discovered_lan_candidate` 并打 `DEGRADED`，不允许静默降级到手动 IP:port（手动候选只用于兜底联通性，不替 mDNS 背书的发现层）。
2. **端口占用顺延。** 消息层 47100→47101…47199→系统分配；BT 51413→51413+99→系统分配。两个进程同一台机器时 A 拿默认口、B 顺延（日志里 `msg_port=47101 bt_port=51513` 就是 B）。**BT 侧的最后一级兜底本轮修过**：原来直接把 `listen_interfaces="0.0.0.0:0"` 交给 libtorrent，实测 2.1.0-39 会报 `listen_port>0`、`is_listening=true`，但那个端口根本不接 TCP（外部 connect 直接 ConnectException），于是兜底出来的会话收不到任何入向连接，还看不出报错。现在先在 JVM 里用 `ServerSocket(0)` 要一个空闲端口再交下去（`BtSession.freePort`，最多试 5 次），`btHint=0` 的场景实测可以正常做种/下载。
3. **候选连接顺序：lan 优先、manual 兜底。** §3.4 的 `manual(3) > lan(2)` 是「同一个 endpoint 去重时记谁」的优先级；两者 key 不同时不构成同键冲突，这里按 M1「mDNS 是主路径」（§3.1）先试 lan。
4. **Windows/MSYS 路径。** 脚本对 java/javac 一律用 `cygpath -m`（`E:/a/b`）：`javac` 的 `@argfile` 会把反斜杠当转义符，用 `cygpath -w` 会读不到源文件。

## 9. 已知限制

- **UI 是薄壳。** app/ 的 P0–P7 已通过 `M1Runtime` + `M1GroupStore` 读内核，但页面上还没有 M2 的开关（限速、通知档、tracker）；内核侧只提供 API 与事件。
- **真机只跑了 android-desktop 一条链路。** `m1-device.sh --quick` 用手机 + 桌面 JVM 对端验过（含 `:app` 编译）；android-android、跨网、Wi-Fi 抖动仍未验（K1/K2 的结论是 loopback/Native 层面的）。
- **重启只恢复做种，不续传下载。** 上次在做种的条目（已完成、数据在盘上）重启后自动续种；下到一半的条目重启后是休眠，要用户再点激活，从已完成的片继续。没有断点续传的续传数据（`resume_data`），激活时靠片上校验重算。
- **冷启动重连有两个口径边界。** ① 只对 `start()` 时从 `state.json` 恢复的群生效，刚建 / 刚加入的群不重连（链路本来就是加入流程建的）；② 只记「本机拨出去并绑定成功」的端点，入向链路拿到的对端端口是临时端口，落盘没用 —— 单方面只被连过的设备重启后，只能靠 mDNS 或手动地址找回对端。
- **完成推送的 200 ms 兜底是真兜底，不是精度声明。** `torrent_finished_alert` 由 libtorrent 在自己的 session tick 里发，实测常被 200 ms 的兜底巡查抢先（`download_completed source=sweep`）。要更准只能等 libtorrent 的 tick 变快，不靠内核调参。
- **队友数有三个口径边界。** ① 只在「部分」计数：本机 100%（或没挂进会话）立刻回 0；② `peer_info` 只有端点没有成员身份，所以靠「成员的 BT 端点（`peer.announce` 自报）↔ peer 列表端点」配对：本机拨出去的连接能按 host:port 精确配上，对端拨进来的连接只剩临时端口，只能按 host 退化成逐条分配 —— 同一台机器上跑多个成员（`127.0.0.1` 多个端口）时理论上可能配错人，局域网一台设备一个 IP 的常态下没问题；③ 它数的是 BT 连接实况，不是成员表，所以「成员表里有 3 人但只有 1 人连上」会显示 1，这是产品规格 §306 要的观测口径。
- **撞车断言的证伪边界。** `harness/reconnect-race.sh` 在 loopback 上跑：TCP 是全双工且 EOF 必达，对称竞态本身能收敛，所以「永不 attach」这一症状要真机切断网络（半开连接）才复现。夹具因此把判据做成「撞车兜底必须走到（`race_settled ≥ 1`）+ 撞完 60 s 不抖」，并用 `-Dhuchuan.mutants=no_link_fallback` 反向臂证明它有证伪力；真机上的半开连接路径仍未自动化覆盖。
- **跨网只有「拿到端点」这一段，没有可达性那一档。** tracker 给的是「对端在公网上看起来的源地址 + 它自报的消息层端口」：家宽/蜂窝后面那个端口没做映射就打不通（UPnP / NAT-PMP / PCP、STUN 打洞都在 `docs/design/nat-traversal.md` 的分层里，内核没实现）。所以本轮的收口是「mDNS 找不到时还有一份跨网的地址可以试」，不是「跨网一定能连上」。
- **tracker 的候选只喂给冷启动重连。** 候选并进 `GroupRuntime.candidates()`，实际拨号的是 `PeerReconnector` —— 它按已定的口径只对 `start()` 时从 `state.json` 恢复的群工作（§9 上一条）。运行中新建的群拿到 tracker peers 后不会主动拨号；「链接断了立刻按 tracker 重新拨」要等下一轮把拨号器从重连器里拆出来。
- **tracker 的 passkey 连续性有上限。** worker 只认「上一把」的哈希（`worker/README.md`「先到先得」），内核在断线跨整点时按最近 6 小时逐个补证明；超过 6 小时没注册、又没有人替这个群轮换过，就只能一直注册失败（`tracker_register_failed`）。这是服务端口径的代价，不是客户端能绕的。
- **`app/` / `daemon/` 还没接 tracker 配置。** 内核侧 `trackerBaseUrl` / `setTrackerBaseUrl` 已就绪，宿主读配置接线本条不在本轮（边界：只改 `core/`、`harness/`、`docs/core.md`）。
- **评论的 seq 空洞只标记不补齐**：`pending` 池按 prev 链挂起（512 条/作者、10 min），没有做「按游标回补」的端到端用例。
- **限速的验收边界**：单文件限速（`setUploadLimitKbps`）只接到 `TorrentHandle`，验收脚本没压测它的数值，真机脚本用 `throttle=1:2048` 间接验过一次；会话级（P7 全局）限速本轮在 SelfTest 里用 loopback 真流量钉住了「限速后确实慢下来（8 MiB / 1024 KB/s ≥ 5 s）」与「私网对端也在限速范围内」，但仍是回环，**跨网（非私网对端走 global class 的那条路）没有实测**；两个类同时限额时单条连接的瞬时速率会抖动（实测 127 KB/s–3.8 MB/s），看均值不看瞬时。
- **补同步路径没有自动化覆盖**：常规两进程流程里文件靠 file.add 广播到达，`join.ok` 的 files 快照与 `file.list` 只在「加入时的清单为空」或「重连补差」时触发。快照路径用 `-Dm1.shareFirst=1`（A 先共享再等加入）手工跑过；`file.list` 的线上路径还没有用例，条目签名只在 `SelfTest` 里钉了编码与验签。
- **`app/tools/peer/DesktopPeer.java` 的时延还是旧口径**（本机墙钟 − 对端 `ts`），跨设备会出负值；内核已给 `M1Kernel.latencyMillis`，桌面工具接上即可（`app/` 不在本轮改动范围）。
- **群规模 32 人未做硬校验**（协议 §12 的风险）。
- **JmDNS 在 Android 需要 MulticastLock + CHANGE_WIFI_MULTICAST_STATE**，本轮未接。
- **成员平台 / 做种数是自报值。** `peer.announce` 的 `plat` / `seeds` 不进签名域、收端不验、不参与任何准入或传输判定，只用于 P6 显示。离线成员在被删除前一直显示重启前那一刻的值，重启后、重新握手前显示 `unknown` / 0。
- **未读数按「可见 op」算。** 游标只记「上次读到哪条 op」，不存每条 op 的已读标记：墓碑到达后该条（及被墓碑指向的那条）从可见集合消失，未读数会跟着变小，这是刻意的口径。同一台设备上的两个群各自维护游标，没有全局未读。
- **退群不删用户数据。** `removeGroup` 只从会话与本地库里摘掉群，磁盘上已下载的文件保持原样；群不在本地库里，重启时也就没有它的做种集合可恢复，所以退群不会去动 `downloads/`。
- **`app/` 与 `daemon/` 还没接 G1/G3/G4。** core 侧接口与落盘都已就绪（§3.1 有签名），UI 上这三块空白要等下一轮接线。

## 10. 下一步

1. 接 UI：P2/P3/P4/P5 换成 `M1Kernel` + `GroupRuntime`，把 `InMemoryGroupStore` 降级成空实现；顺便按 §3.1 接上 G1（退群入口）、G3（成员平台/做种数）、G4（P2 未读角标 + P4 打开即清零）；P6 挂 `KernelEvents.peersChanged` 刷新在线状态（§3.3）。
2. Android 侧：自编 arm64 .so 换掉 `compileOnly` 的桌面制品，加 MulticastLock，`keySeed` 进 Keystore。
3. 跨网接线：`daemon/`、`app/` 把 `trackerBaseUrl` 从配置（各自已有的设置落盘处）读出来交给 `M1Kernel`，P7 给一个「跨网发现」开关；然后把拨号器从 `PeerReconnector` 里拆出来，让运行中的群也能按 tracker 的 peers 立刻重拨（本轮只喂候选表）。
4. M2 剩余：换码换钥 group.rotate、删除（log.del/file.del）、magnet 路径、可达性分层（`docs/design/nat-traversal.md` §8 的阶梯）。
5. 协议修订：把 §7 剩下没写死的地方（第 1 条的 hello 两阶段、第 5 条的多群 hello）写进协议，否则第三方实现没法互通；§7 的 2/3/4 已按 §10 实现完，protocol.md §13 的第 2/3/4 行可以结案（该表归设计 agent 维护，本轮未改）。

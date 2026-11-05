# core/ · M1 内核（无 UI）

- 版本：2026-11-06 · 内核 agent · 对应协议 v0（docs/design/protocol.md，唯一事实源）
- 状态：**M1 全流程在桌面 JVM 上用两个独立进程跑通**（建群 → 后 8 位加入 → 32 MiB 共享/下载/SHA 一致 → 评论 3 s 内入库 → 全程 dht_nodes=0）
- 不在本文件范围：UI 实现细节（app/ 已通过 `M1Runtime` + `M1GroupStore` 接内核，没有 runtime 时回退 `InMemoryGroupStore`）、tracker 跨网（M2）、Android .so
- M1.1 收敛（对应 protocol.md §13 的第 2/3/4 条）：join.ok 用独立签名 domain 且验不过即断开、file.list 每条 entry 原样带 file.add 的原 sig 并按 addedBy 验签、公钥随 join.ok.members 与 member.name 分发；评论时延改本机时钟口径、单帧发送失败不再断整条连接

## 1. 怎么跑

```bash
bash scripts/m1-headless.sh
```

一条命令做四件事，最后一行打印 `PASS` / `FAIL`，退出码 0 / 1：

1. `./gradlew :core:jar :core:m1Deps :core:m1Native`（构建内核 + 拉依赖 + 解出 `libtorrent4j.dll`）
2. `javac` 编译 harness（`harness/src/main/java`）
3. 跑字节层自检 `SelfTest`（60 条：B32、HKDF（RFC 5869 向量）、邀请码、P-256 签名、join.ok/file.list/member.name 的签名口径、signBytes、JSON、帧）
4. 起 A、B 两个 JVM 进程跑 M1 全流程，然后只用 grep 复核日志

产物：`artifacts/m1/log-A.log`、`log-B.log`、`selftest.log`、`a-data/`、`b-data/`（含 `state.json`、`identity.txt`、`downloads/`）。`artifacts/` 不进版本库。

一次完整跑（Gradle 增量 + javac + 自检 + 两进程 + 断言）本机实测 **22 秒**；25 条 CHECK 全 PASS（自检 60 条另计）。

真机链路另跑：`bash scripts/m1-device.sh --quick`（手机 + 桌面 JVM 对端，把 `:app` 也编一遍），本轮 19 条 CHECK 全 PASS。harness 故意写成纯 Java、由 `javac` 直接编译（不进 `settings.gradle.kts`，避免动到 app 的构建图）。

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
| `OpLog.kt` | 评论日志：验签、opId 去重、prev 链、Lamport、乱序 pending、作者游标 | §6 |
| `GroupRuntime.kt` | 一个群的运行时：成员表、文件清单、评论、连接、BT 任务、持久化快照；对外就是 GroupRuntime API | §7 §11 |
| `BtSession.kt` | libtorrent4j 私有会话：建 v1 私有种、加种/做种、swig `connect_peer`、单文件限速、dht_nodes | §4 §8 |
| `Discovery.kt` | JmDNS `_huchuan._tcp` 注册/浏览；手动 `IP:port` 解析 | §3.1 §3.3 §3.4 |
| `PeerLink.kt` | 一条消息层连接的全部语义：hello 绑定 → join → sync → 广播 → ping/bye/err | §2.3 §5 §7 §8 |
| `PeerServer.kt` | 消息层监听（端口顺延）、TLS 握手、每条连接一个线程 | §5 |
| `KernelEvents.kt` | 宿主事件出口 + 加入状态 + join 限速器 | §8 |
| `M1Kernel.kt` | 门面：生命周期、建群/加入、公钥登记、持久化、后台巡查（心跳/补连/完成判定） | §11 |

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

// P3/P4
val entry = group.addFile(File(...))        // 建 v1 私有种 → 做种 → 广播 file.add
group.startDownload(entry.fileId)            // 加种 + connect_peer
group.progress(entry.fileId)                 // 0.0–1.0
group.isComplete(entry.fileId)               // 落盘并校验通过
group.savedFile(entry.fileId)                // 下载后的文件
group.setUploadLimitKbps(entry.fileId, 512)  // 单文件限速，单位 KB/s（×1024 字节/秒），0 = 不限
group.connectSeeders(entry.fileId)            // 把已知做种端点接上（swig connect_peer）
val op = group.comment(entry.fileId, "这条能收到吗？", null) // parent 非空即回复
group.files(); group.members(); group.visibleOps(fileId)

// 展示用端到端时延（毫秒）：减法两边都在本机时钟上，跨设备时钟不同步也不会出负值
val latencyMs = kernel.latencyMillis(op.author, op.ts, System.currentTimeMillis())

kernel.close()
```

接口按 Kotlin 写，但**没有用 suspend、没有默认参数**，Java 可以直接 new / 调用（harness 就是纯 Java）。

- `connectSeeders` 的去重键是 **`fileId@dev:port`**，不是 `dev:port`：`connect_peer` 是「每个 torrent 一份」的连接，只按 `dev:port` 去重会让第二个文件永远接不上做种者（一文件一 torrent）。该文件已有活 peer 时不重连；没有活连接时按 `CONNECT_RETRY_MS`（5 s）重试；`stopFile` 会清掉这个文件的计时，否则休眠完再激活会被重试间隔卡住。
- `latencyMillis` 只给展示用：评论顺序固定按 `(lamport, author, seq)`（协议 §6），`ts` 和时延都不参与排序。

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
| `huchuan-monitor` | 每 2 s：dht 心跳、补连做种者、完成判定；每 30 s ping | 所有群的轻量巡查 |
| 调用方线程 | `createGroup`/`addFile`/`comment` 同步执行 | `joinByTail8` 阻塞 |

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

0x01 hello ✓ · 0x02 join.req ✓ · 0x03 join.ok ✓ · 0x10 sync.req ✓ · 0x11 file.list ✓ · 0x12 file.add ✓ · 0x20 log.sync ✓ · 0x21 log.ops ✓ · 0x22 log.op ✓ · 0x23 member.name ✓ · 0x30 peer.announce ✓ · 0x40/0x41 ping·pong ✓ · 0x42 bye ✓ · 0x7F err ✓

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
| join 限速 | 计数单位是**校验失败次数**（滑动窗口，成功不计数）：5 次/10 min/源 IP、30 次/h/群 | `JoinRateLimiter` |
| 帧限速 | 60 帧/s/连接，每秒重置；超了回 `E_RATE` 并断开 | `PeerLink.MAX_FRAMES_PER_SECOND` |
| connect_peer 去重键 | `fileId@dev:port`；有活 peer 不重连，否则每 `CONNECT_RETRY_MS = 5 s` 重试 | `GroupRuntime.connectSeeders` |
| keySeed 落盘 | `secretBox.seal(keySeed)` + `keySeedProt`；方案认不出或打不开就跳过该群，不回退明文 | `SecretBox.kt`、`M1Kernel.unsealKeySeed` |

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

## 8. 退化点（实现里真实存在，不是设想）

1. **mDNS 不可用 → 手动 IP:port。** JmDNS 3.6.2 在本机两进程实测可用（`join_candidates lan=1`，加入走 `source=lan`）。若运行环境没有组播（虚拟网卡、被防火墙拦、Android 没拿 MulticastLock），浏览结果为空，`joinByTail8` 自动退到调用方给的手动候选；日志 `join_candidates lan=0` 且 `join_via source=manual`。脚本对 `lan>=1` 是**硬断言**：`lan=0` 直接 `CHECK FAIL mdns.discovered_lan_candidate` 并打 `DEGRADED`，不允许静默降级到手动 IP:port（手动候选只用于兜底联通性，不替 mDNS 背书的发现层）。
2. **端口占用顺延。** 消息层 47100→47101…47199→系统分配；BT 51413→51413+99→系统分配。两个进程同一台机器时 A 拿默认口、B 顺延（日志里 `msg_port=47101 bt_port=51513` 就是 B）。
3. **候选连接顺序：lan 优先、manual 兜底。** §3.4 的 `manual(3) > lan(2)` 是「同一个 endpoint 去重时记谁」的优先级；两者 key 不同时不构成同键冲突，这里按 M1「mDNS 是主路径」（§3.1）先试 lan。
4. **Windows/MSYS 路径。** 脚本对 java/javac 一律用 `cygpath -m`（`E:/a/b`）：`javac` 的 `@argfile` 会把反斜杠当转义符，用 `cygpath -w` 会读不到源文件。

## 9. 已知限制

- **UI 是薄壳。** app/ 的 P0–P7 已通过 `M1Runtime` + `M1GroupStore` 读内核，但页面上还没有 M2 的开关（限速、通知档、tracker）；内核侧只提供 API 与事件。
- **真机只跑了 android-desktop 一条链路。** `m1-device.sh --quick` 用手机 + 桌面 JVM 对端验过（含 `:app` 编译）；android-android、跨网、Wi-Fi 抖动仍未验（K1/K2 的结论是 loopback/Native 层面的）。
- **单文件、无断点续传。** 重启后 BT 任务不恢复（群数据与评论会从 `state.json` 恢复）。
- **无 tracker、无跨网。** M1 只有 mDNS + 手动。
- **评论的 seq 空洞只标记不补齐**：`pending` 池按 prev 链挂起（512 条/作者、10 min），没有做「按游标回补」的端到端用例。
- **限速只做到接口**：`setUploadLimitKbps` 已接到 `TorrentHandle`，单位是 KB/s（见 §6 口径表），但验收脚本没压测限速数值；真机脚本用 `throttle=1:2048` 间接验过一次。
- **补同步路径没有自动化覆盖**：常规两进程流程里文件靠 file.add 广播到达，`join.ok` 的 files 快照与 `file.list` 只在「加入时的清单为空」或「重连补差」时触发。快照路径用 `-Dm1.shareFirst=1`（A 先共享再等加入）手工跑过；`file.list` 的线上路径还没有用例，条目签名只在 `SelfTest` 里钉了编码与验签。
- **`app/tools/peer/DesktopPeer.java` 的时延还是旧口径**（本机墙钟 − 对端 `ts`），跨设备会出负值；内核已给 `M1Kernel.latencyMillis`，桌面工具接上即可（`app/` 不在本轮改动范围）。
- **群规模 32 人未做硬校验**（协议 §12 的风险）。
- **JmDNS 在 Android 需要 MulticastLock + CHANGE_WIFI_MULTICAST_STATE**，本轮未接。

## 10. 下一步

1. 接 UI：P2/P3/P4/P5 换成 `M1Kernel` + `GroupRuntime`，把 `InMemoryGroupStore` 降级成空实现。
2. Android 侧：自编 arm64 .so 换掉 `compileOnly` 的桌面制品，加 MulticastLock，`keySeed` 进 Keystore。
3. M2：tracker（groupHash20 + passkey）、换码换钥 group.rotate、删除（log.del/file.del）、magnet 路径。
4. 协议修订：把 §7 剩下没写死的地方（第 1 条的 hello 两阶段、第 5 条的多群 hello）写进协议，否则第三方实现没法互通；§7 的 2/3/4 已按 §10 实现完，protocol.md §13 的第 2/3/4 行可以结案（该表归设计 agent 维护，本轮未改）。

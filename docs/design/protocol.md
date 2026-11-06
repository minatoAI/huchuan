# 互传 · 群组与消息协议 v0

- 版本 hv1 / rev 2 · 2026-11-06 · 执行层设计 · 唯一事实源：本文件（rev 2 补四条裁决：hello 两阶段、join.ok 签名、file.list 条目签名、公钥分发；新增 §13 实现偏差登记）
- 前提：AGENTS.md §9（libtorrent4j 2.1.0-39、v1 私有种子、双 SessionManager、per-torrent 0x380000）；K1 spike 实测（v1 起传 500 ms 档、swig connect_peer 可用、dht_nodes 恒 0）
- 适用：Android 8.0+（minSdk 26）与 JDK 17 Windows daemon，同一份 Kotlin/JVM 内核；局域网优先，跨网是 M2
- 记号：B32 = Crockford Base32（大写、无填充、无连字符）；hex = 小写十六进制；整数一律无符号大端；JSON 禁浮点；字符串 UTF-8 + NFC；时间为 Unix 毫秒 u64。MUST 为硬约束，本文没写的不要自创。

## 0. 速查

| 项 | 值 |
|---|---|
| 身份密钥 | ECDSA P-256 + SHA-256；公钥 64 B 裸值（无 0x04）；签名 64 B r‖s |
| deviceId | B32(SHA-256("huchuan/dev1/v1"‖pub64)[0..10])，16 字符 |
| 邀请码 | 16 字符 Crockford，显示 4-4-4-4；后 8 位秘密，前 8 位派生校验 |
| kJoin / kGrp | HKDF-SHA256(秘密5B, 校验5B, "huchuan/join1/v1", 32) ／ HKDF-SHA256(keySeed32B, codeBytes10B, "huchuan/group1/v1", 32) |
| 消息层 | 独立 TCP + TLS1.2+，默认 47100，ALPN huchuan/1，帧 u32len‖u8ver‖u8type‖JSON，≤ 65536 B |

## 1. 身份

### 1.1 算法：P-256（否决 Ed25519）

| 判据 | P-256 | Ed25519 | 结论 |
|---|---|---|---|
| minSdk 26 可用性 | JCA + Conscrypt 全程可用 | JCA 实现需 JDK 15+，Android 侧要 API 33 级 Conscrypt | 只有 P-256 在 Android 8–12 零依赖可用 |
| 依赖 | 0（java.security） | BouncyCastle（+8 MB）或自实现（需自证） | 不给内核加密码库 |
| 线格式 | r‖s 固定 64 B（禁 DER；DER 变长是互操作事故源头） | 固定 64 B | 打平 |
| 换算法条件 | — | minSdk 升到 33 且 API 26–28 退出支持面 | 届时只换 1.2 的 signBytes 实现 |

- 确定性签名：Ed25519 免随机数依赖是优点，P-256 需 SecureRandom（安卓实现可用），不构成选型理由。私钥：Android Keystore（EC P-256，PURPOSE_SIGN，不可导出）；Windows daemon 用 PKCS12（0600）。协议只规定公钥与签名字节。
- 签名者 MUST 输出低-S（s > n/2 时取 n−s）；验签者 MUST 接受高-S 但先归一，MUST 拒绝 r=0、s=0、r≥n、s≥n。不同用途用不同 domain 前缀，签在 SHA-256 摘要上。

### 1.2 deviceId 与签名字节串

| 项 | 定义 |
|---|---|
| 密钥 | 首次启动生成 P-256 密钥对，永久保存，跟随安装生命周期 |
| 公钥编码 | 64 B 裸值 x‖y（各 32 B 大端，不含 0x04） |
| deviceId | B32(SHA-256(UTF8("huchuan/dev1/v1") ‖ pub64)[0..10]) → 16 字符；由公钥唯一决定，任何人可重算，改名/换群/换 IP 都不变 |
| 多设备 | 一台设备一个身份；同一人两台机器是两个成员 |

signBytes(domain, fields) = UTF8(domain) ‖ 0x00 ‖ 逐个 field 写入 (u16be(长度) ‖ 原始字节)。字符串取 UTF-8+NFC；二进制与 hex 先转字节；整数固定 8 字节无符号大端（长度前缀恒 0x0008）；空值只写长度 0，禁用 "null" 字面量。

| domain | 用途 | 字段顺序（必须一致） |
|---|---|---|
| huchuan/hello1/v1 | 通道绑定（签名） | dev, gid, 本端 nonce, 对端 nonce, chanId |
| huchuan/join1/v1 | 加入请求（HMAC） | dev, pub, nonce, ts |
| huchuan/joinok1/v1 | join.ok 应答（签名，§10） | dev, gid, keySeed, fp, nonce, ts |
| huchuan/name1/v1 | 用户名记录 | dev, name, nameSeq, ts |
| huchuan/file1/v1 | 文件广播 | fileId, name, size, pieceSize, addedBy, addedAt |
| huchuan/op1/v1 | 评论 op | opId, fileId, author, seq, lamport, ts, kind, parent, body, prev |
| huchuan/peer1/v1 | BT 端点广播 | dev, gid, btPort, ts |
| huchuan/proof1/v1 · trk1/v1 | 群归属证明 · tracker passkey（HMAC） | gid, chanId ／ gid, 当前小时整数 |

chanId = SHA-256(u16be(len)‖clientCertDER ‖ u16be(len)‖serverCertDER)，两端各按角色顺序计算，值必须相同。
B32 位序：把字节串视为大端比特串，从高位起每 5 bit 一组查表转字符，末组不足 5 bit 时右补 0，长度 = ceil(bits/5)；解码反向，并要求末尾补位恒为 0（16 字符码 = 80 bit 恰好无损；opId 128 bit → 26 字符，fp 40 bit → 8 字符）。

### 1.3 用户名与签名的关系

- 用户名**不参与任何 ID 计算**，不进邀请码、deviceId、文件键。它是自声明的可变属性，靠签名记录传播。
- 签名证明「这台设备主张这个名字」，**不证明名字唯一、不证明名字真实**；只接受 nameSeq 更大的记录（相等按 ts、记录哈希取大，保证收敛）。重名显示「用户名·deviceId 前 4 位」，不做全局仲裁，改名 ≤ 1 次/分钟。

pub 是这条记录的第二个必需字段（64 B 裸值，128 hex），取值与 §10 的 join.ok.members 相同，作用是让收到转发 op 的设备不必先与作者建连也能验签（§7、§13-4）。pub 不进入 name1 签名域：它本身就是验签公钥，自证无意义；接收方先算 deviceId(pub) 与 dev 比对，不等即丢弃，再用该 pub 验 sig。

~~~
{"t":"member.name","dev":"7Q2F9C4M1XZ8KD3V","pub":"9c4e…（128 hex）","name":"老板的笔记本","nameSeq":3,"ts":1762381200123,"sig":"9f3c…（128 hex）"}
~~~

## 2. 群组与邀请码

### 2.1 码格式与规范化

| 项 | 定义 |
|---|---|
| 长度 / 字符集 | 16 字符 = 80 bit = 10 字节（每字符 5 bit，无填充位）；字符集 0123456789ABCDEFGHJKMNPQRSTVWXYZ（无 I L O U） |
| 规范化 | 转大写 → 删连字符与空白 → O 换 0、I/L 换 1；含 U 或非法字符直接判「邀请码不对」 |
| 输入 | P5 只收后 8 位（40 bit），也接受粘贴完整 16 位；两条路径同一实现：由后 8 位重算前 8 位再比对 |

分块：前 8 字符 checkBytes（40 bit）= SHA-256("huchuan/chk1/v1"‖secretBytes)[0..5]，抄错即判错、离线可验、不承担保密；后 8 字符 secretBytes（40 bit）= 建群时真随机，唯一秘密，用户只输这一段。

**强度必须写清（§12-1 已裁决）**：码面 80 bit，真实熵 40 bit。两条强制补偿：① 任何码派生值 MUST NOT 出现在 mDNS/TXT/tracker（否则同网段可离线爆破 2^40）；② 加入只能在线完成并受限速约束（§8）。升级路径保留：要 80 bit 真实熵时 P5 改收全 16 位，协议不变。

### 2.2 HKDF 派生（参数写死）

| 名称 | 公式 | 长度 | 用途 |
|---|---|---|---|
| codeBytes | B32decode16(规范化码) | 10 B | 派生盐 |
| kJoin | HKDF-SHA256(ikm=secretBytes 5 B, salt=checkBytes 5 B, info=UTF8("huchuan/join1/v1"), L=32) | 32 B | 加入门禁 HMAC 密钥 |
| keySeed | 建群时真随机 | 32 B | 组密钥种子，仅经 join.ok 下发 |
| kGrp | HKDF-SHA256(ikm=keySeed, salt=codeBytes, info=UTF8("huchuan/group1/v1"), L=32) | 32 B | hello.proof、tracker passkey、轮换失效判据 |
| fp | B32(SHA-256(kGrp)[0..5]) | 8 字符 | 群指纹，P6 显示、口头核对 |
| groupHash20 | SHA-256(UTF8("huchuan/gh1/v1")‖codeBytes)[0..20] | 20 B | M2 tracker 的 info_hash 槽位 |

- HKDF 按 RFC 5869：Extract(salt, ikm) → PRK，Expand(PRK, info, L)，L=32 即一轮。keySeed 只走 TLS 通道内的 join.ok 明文，不落 mDNS、不落 tracker、不进日志。
- groupId 与码解耦（建群时 10 B 随机）：M2 换码后群标识不变，成员表与本地库不用迁移；代价是 groupId 不能在入群前验证，由 join.ok 里的 fp 承担这条。

### 2.3 加入时校验什么、谁来验

**执行者：被连接的那个在线成员**（每个成员都能验，无群主、无服务端）。

| 顺序 | 校验项 | 失败响应 |
|---|---|---|
| 1 | TLS 建连；双方 hello 签名有效；chanId 一致 | E_SIG，断开 |
| 2 | join.req 的 ts 在 ±300 s 内 | E_CLOCK（回带本地 ts） |
| 3 | nonce 未出现在该设备 LRU（256 条）中 | 静默丢弃 |
| 4 | HMAC(kJoin, signBytes("huchuan/join1/v1",[dev,pub,nonce,ts])) 常量时间比对 | E_JOIN_CODE |
| 5 / 6 | 未触发限速（§8）；群未关闭加入（TXT r=0） | E_RATE ／ E_JOIN_CLOSED |
| 7 | 通过：发 join.ok（keySeed、gid、群名、epoch、成员表、文件快照、评论游标，带 §10 的 sig），并向其他在线成员广播新成员 | — |

第 4 条的固有性质：知道这 8 个字符的人必然能通过。协议不区分「抄来的」与「被授权的」，这是无服务端邀请制的定义性代价，靠轮换止损。

### 2.4 泄露后的轮换（M2 实现，字段先留）

| 字段 | 位置 | M1 | M2 |
|---|---|---|---|
| epoch | hello / join.ok / group.rotate | 恒 0 | 每次轮换 +1 |
| keySeedPrev | group.rotate | 不出现 | 供离线成员验证新旧衔接 |
| codeNew / keySeedNew | group.rotate（旧 kGrp AEAD 包裹） | 不出现 | 换码换钥，旧码即刻失效 |
| prevFp | group.rotate | 不出现 | 旧指纹，供 UI 提示 |

## 3. 成员发现

### 3.1 mDNS（局域网，M1 主路径）

| 项 | 值 |
|---|---|
| 服务类型 | _huchuan._tcp（DNS-SD 标签限 15 字符，取 7） |
| 实例名 | hv1-<gHandle>；gHandle = 建群时 8 字符随机，**不是**码派生（避免离线爆破） |
| SRV / 生命周期 | port = 本机消息层 TCP 端口，A/AAAA 由系统附带；注册 TTL 120 s、60 s 刷新，退群或关机即注销 |

| TXT key | 示例 | M1 是否发 | 说明 |
|---|---|---|---|
| v | 1 | 发 | 协议主版本 |
| gid | 7Q2F9C4M1XZ8KD3V | 发 | groupId |
| bt | 51413 | 发 | 本机私有会话 BT 监听端口 |
| r | 1 | 发 | 1=可加入，0=关闭准入 |
| nm | 老板的群 | **不发**（默认关） | 群名 ≤ 48 B；转义按 RFC 6763；M2 才给开关 |
| m | 3 | **不发**（默认关） | 在线成员数；M2 才给开关 |
| fp | K2M9QXDP | **不发**（默认关） | 群指纹前 8 字符；M2 口头核对时手动开 |

M1 只发 v、gid、bt、r。nm、m、fp 默认关闭：同一网段任何设备（含未装本软件的人）都能读到 TXT，「老板的群 / 3 人在线」直接暴露私有群的存在与规模，与产品承诺冲突。**MUST NOT** 广播任何码派生值（checkBytes、kJoin、kGrp、groupHash20）；fp 若被打开是例外，它由随机 keySeed 派生，不能从 40 bit 码穷举。B 加入不需要群名，逐个候选试 join.req 命中即停（§3.4）。Android 用 NsdManager（CHANGE_WIFI_MULTICAST_STATE + MulticastLock），Windows daemon 用 JmDNS；只要求 TXT 编码一致。

### 3.2 自带 tracker（跨网，M2）

| 项 | 定义 |
|---|---|
| 地址 | https://<tracker>/announce（BEP 3 语义，自有实现） |
| info_hash | groupHash20（群级「伪 swarm」，成员在此登记消息层端口）；文件 swarm 另用真实 fileId，端口用 BT 端口 |
| passkey | hex(HMAC-SHA256(kGrp, UTF8("huchuan/trk1/v1")‖gid‖当前小时整数))，服务端只存哈希 |
| 其余参数 | compact=1、numwant=32、left=0、event=started/stopped/completed/none、ip 留空由服务端取源地址 |
| 应答 / 禁止 | d8:intervali600e5:peers<compact>6:peers6<compact>e ／ 禁止公开 tracker（会泄露私有 infohash 与成员 IP） |

### 3.3 手动 IP:port（兜底，M1 可用）

| 项 | 定义 |
|---|---|
| 语法 | (IPv4 ／ "[" IPv6 ["%" 区域] "]" ／ 主机名) ":" 端口 |
| 端口 | 必填 1–65535，是**消息层**端口；BT 端口由 peer.announce 交换 |
| 例 | 合法：192.168.1.5:47100 ／ [fe80::1%wlan0]:47100 ／ nas.local:47100；非法：192.168.1.5（缺端口）、http://x:1（带 scheme）、1.2.3.4:0 |
| 处理 | 去首尾空白、全角冒号转半角；不做 DNS 预解析；按 groupId 存本地，≤ 32 条 |

### 3.4 三种来源合并去重

| 规则 | 定义 |
|---|---|
| 候选结构 | (ip, port, src, deviceId?, btPort?, lastOk, failCount) |
| 去重键 | 已知 deviceId 按 deviceId；否则按规范化 ip:port |
| 优先级 | manual(3) > lan(2) > tracker(1)；同键留高优先级，src 记成位集 |
| 多网卡 / 失效 | 同 deviceId 允许多 endpoint 并存，按 lastOk 降序试、成功即锁定；连续 3 次失败且 > 60 s 则 5 min 退避，mDNS TTL 过期即移除 |
| 上限 | 每群 128 个候选，超出淘汰 failCount 最高者；任何来源都不产生「按 infohash 自动连」 |
| 多群同网段 | 不猜：B 逐个候选发 join.req，命中即停。失败计数与限速按 (源 IP × 目标 host) 各自独立记，试错 A 群不会锁死真正的目标 host，也不会消耗目标群的配额。候选上限 128，最坏试 128 次握手（每次 ≈ 1 个 TLS 往返），局域网通常 1–2 次即命中 |

## 4. 文件与种子

| 项 | 定义 |
|---|---|
| 模型 / 版本 | 一文件一 torrent（单文件限速落到 setUploadLimit 的前提）；v1-only（TorrentBuilder.V1_ONLY，K1 实测 v1/v2 起传同分布） |
| private | true（BEP 27） |
| metainfo 断言 | 无 trackers、无 nodes、无 url-list；建种后重新解析再断言（K1 做法） |
| per-torrent flags | DISABLE_DHT(0x80000) │ DISABLE_LSD(0x100000) │ DISABLE_PEX(0x200000) = **0x380000** |
| 加种 / 加 peer | 低层 add_torrent_params 精确设 flags；magnet 加种由 ut_metadata 向已连 peer 取元数据；手动加 peer 走 swig connect_peer（高层 API 无此能力，K1 已证） |
| 主键 | fileId = v1 infohash 小写 hex 40 字符；同群同 fileId 即同一文件，重复广播幂等 |

pieceSize 规则：S ≤ 64 MiB → 64 KiB；S ≤ 512 MiB → 256 KiB；S ≤ 4 GiB → 1 MiB；S ≤ 16 GiB → 2 MiB；更大 → 4 MiB。

修正：S / pieceSize > 8192 时 pieceSize 翻倍直到 ≤ 8192 片；pieceSize 必须 2 的幂且 ≥ 16 KiB。M1 magnet（不加 tr）：magnet:?xt=urn:btih:<fileId40hex>&dn=<文件名 percent-encode>&xl=<size>；M2 追加 &tr=https%3A%2F%2F<tracker>%2Fannounce%2F<passkey>。

## 5. 消息层

| 项 | 定义 |
|---|---|
| 承载 | 独立 TCP，**不塞进 BT 通道**（BT 扩展位被 PEX/DHT 占着，私有种子下行为不可控） |
| 加密 | TLS 1.2 起（Android 8 无 1.3），优先 1.3；套件白名单 TLS_AES_128_GCM_SHA256、TLS_CHACHA20_POLY1305_SHA256、TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256 |
| 证书 / 端口 | 每安装自签 EC P-256，CN=deviceId，10 年，两端不校验证书链（身份由 hello 签名绑定，chanId 含两端证书摘要）；ALPN huchuan/1，默认 47100，占用顺延 47101–47199，实际端口写进 mDNS SRV、join.ok、手动输入 |
| 连接 | 双向；同群同 deviceId 只留一条（deviceId 字典序小者主动连，避免双连）；空闲 30 s 发 ping，120 s 无帧即断；重连指数退避 1/2/4/8/16/30 s |

帧：u32be(len) ‖ u8(ver=1) ‖ u8(type) ‖ body(UTF-8 JSON)，len = 2 + bodyLen，**单帧上限 65536 B**（body ≤ 65533）。单字段上限：字符串 4096 B、文件名 255 B、内联 .torrent 47 KiB。未知 type MUST 静默忽略不断开；未知字段 MUST 忽略；必需字段缺失 → E_PROTO。

| 版本与兼容 | 规则 |
|---|---|
| 版本位 | 帧头 u8 ver（当前 1）+ hello.proto "hv1" + hello.rev 次版本 |
| 主版本不符 / 次版本升级 | 不符 → hello 阶段 E_VERSION 并断开；升级只允许加可选字段、加消息类型、加 caps，旧实现按「未知即忽略」继续工作 |
| caps | hello.caps（M1: files,log,peer；M2 加 rotate,team,del）；缺 cap 的一方不得发该类消息 |
| 破坏性变更 | 主版本 +1、proto 改 hv2、mDNS 服务名另起；字段编号与名称一旦发布不复用 |

### 5.1 连接建立：hello 两阶段

hello 的签名域含双方的 nonce（§1.2），单帧做不到。签名方发出首帧时还没看到对端 nonce，而对端要等收到首帧才知道自己的 nonce 已被引用。若改成只签本端 nonce，签名就不再绑定这一条通道的两端，chanId 这条防线要等第二帧才成立。所以 hello 固定两帧，两条都是 0x01：

| 阶段 | 帧内容 | 收到后 |
|---|---|---|
| 1 交换 | hello，`sig=""`，`proof` 可为空，`nonce` = 本端 16 B 随机 | 记下 dev、pub、nonce，先校验 dev == deviceId(pub)（§1.2），不等回 E_SIG 断开 |
| 2 绑定 | hello，`sig`=signBytes("huchuan/hello1/v1",[dev,gid,本端 nonce,对端 nonce,chanId])，`proof`=HMAC(kGrp,…) | 验 sig，失败回 E_SIG 断开；有 kGrp 时再验 proof；两项都过，连接才算 bound |

- 第二帧 MUST 复用第一帧的 dev、gid、nonce。换 dev 回 E_SIG（一条连接上的 deviceId 不可变）；换 nonce 会让签名覆盖的本端 nonce 与对端记录不一致，回 E_PROTO。
- 绑定前不收业务帧：sync.req、file.add、file.list、log.* 一律按 §8 回 E_NOT_MEMBER，不排队等绑定，绑定完成后双方各自补发 sync.req（§7）。join.req 是例外，但只在 hello 已给出 dev/pub 之后处理，且它的 dev MUST 等于 hello 的 dev，不等回 E_SIG。
- `sig=""` 只属于阶段 1。已绑定后再收到空 sig 的 hello 回 E_PROTO 而不是 E_SIG，这是版本不一致，不是伪造。
- 双方对称，与谁主动连无关：主动方在 TLS 建连后立即发阶段 1，被动方在 TLS 握手完成后同样立即发。两侧 chanId 相同（§1.2），两条 hello 的签名字节互不相同。

## 6. 评论日志

| 字段 | 类型 | 说明 |
|---|---|---|
| opId | 26 字符 B32(16 B) | SHA-256(authorPub64 ‖ u32(seq))[0..16]，该作者的永久主键 |
| fileId / author / seq | 40 hex / 16 字符 / u32 | 挂在哪个文件；谁写的；该作者日志连续序号，从 1 起逐条 +1 不跳号 |
| lamport | u64 | 逻辑时钟：收到任意外来 op 时 lamport = max(本地, 对方) + 1 |
| ts | u64 | 墙钟毫秒，**只用于展示**，不参与排序 |
| kind | u8 | 0=新增 1=回复 2=墓碑（M1 只发 0/1；M1 收到 2 MUST 接受并隐藏） |
| parent | 26 字符或 "" | 回复目标 opId，kind=1 必填 |
| body | 字符串 ≤ 4096 B | 正文，UTF-8 + NFC |
| prev | 26 字符或 "" | 该作者 seq−1 那条的 opId（哈希链），seq=1 为空 |
| sig | 128 hex | ECDSA over signBytes("huchuan/op1/v1", 上表顺序) |

~~~
{"t":"log.op","op":{"opId":"K7F2M9QXDP4T8VNB2C5JXW3Y","fileId":"8b3f0c0e1d2a4b6c8d0e2f4a6b8c0d1e2f3a4b5c","author":"7Q2F9C4M1XZ8KD3V","seq":12,"lamport":47,"ts":1762381455001,"kind":1,"parent":"P4T8VNB2C5JXW3YK7F2M9QXD","body":"这个版本能跑","prev":"M9QXDP4T8VNB2C5JXW3YK7F2","sig":"c1a8…（128 hex）"}}
~~~

| 机制 | 规则 |
|---|---|
| 排序 / 去重 | 按 (lamport, author 字典序, seq) 升序，全序且两端一致；opId 唯一，重复收到直接丢弃，不重排序、不重写 |
| 乱序 | prev 非空且本地无该 opId → 入 pending 池（512 条/作者，10 min 过期），链接上再上屏 |
| 验签 / 因果 | 必须验签，失败即丢；同设备 3 次/分钟断开。seq 空洞不拒收，标记该设备「日志有洞」，下次按游标补齐 |
| 墓碑 | kind=2、body 空、parent 指向被删 opId；本地不物理删除，只隐藏并继续参与同步（防旧副本复活） |
| 编辑 / 删除 | 评论不可编辑；M1 不可删（删是 M2 的 log.del） |
| 同步 | log.sync 带每作者游标 [{a,up}]，应答按序返回缺页，每页 ≤ 200 op 且 ≤ 60 KiB，带 more |

## 7. 文件清单广播与补同步

| 场景 | 消息 | 说明 |
|---|---|---|
| 在线推送 | file.add 广播给所有已连接成员 | 幂等：同 fileId 只更新元信息，不产生第二条 |
| 上线补差 | 建连且 hello 通过后主动方发 sync.req | fdigest = B32(SHA-256(按 fileId 升序拼接 fileId 串)[0..8]) |
| 摘要不同 / 相同 | 不同 → 回 file.list（32 条/页，带 more），全量下发按 fileId 合并，M1 每群上限 256 条；相同 → 跳过 file.list 直接回 log.ops |
| 评论补差 / 断线重连 | 同一 sync.req 的 cursors，回 log.ops 直到 more=false；断开重连无特殊处理 | 双向都会发 sync.req；每个成员都是全量副本，不存在「服务器补数据」角色 |
| 不做 | 差异增量、按文件拉取、删除同步（M1） | 文件少时全量清单成本低于增量协议的实现风险 |

file.list 的每条 entry MUST 原样携带该文件 file.add 的 `sig`，不重签也不改字段。重签会把「谁加的」这条证据换成本次转发者的声明，等于清单里的 addedBy 变成自证。接收方按 addedBy 从成员记录取 64 B 公钥（来源只有 hello、join.ok.members、member.name 三条，§10），用 `huchuan/file1/v1` 与 §1.2 的字段顺序验签。公钥 MUST NOT 取自条目自身。验不过或公钥未知，丢弃该条、不入库、不计入 fdigest，同批其余条目照收，不回 E_SIG（对端可能只是还没升级的旧实现）。

## 8. 准入与隐私

| 面 | 规则 |
|---|---|
| settings_pack | enable_dht=false、enable_lsd=false、enable_upnp=false、enable_natpmp=false、enable_ip_notifier=false、dht_bootstrap_nodes=""；私有会话独立 SessionManager |
| 种子 / per-torrent | private=1、metainfo 无 trackers/nodes/url-list（libtorrent 自动禁 PEX/DHT，第二道保险）；0x380000 显式再设一次（PEX 无会话级开关） |
| ip_filter | 只放行 127/8、10/8、172.16/12、192.168/16、169.254/16、::1/128、fc00::/7、fe80::/10；M2 跨网改为已入群设备白名单 |
| 谁能连进来（BT） | 只有 infohash 在本地已知集合内的握手被接受；不做「按 infohash 自动连」 |
| 谁能连进来（消息层） | hello 通过后，非成员只允许 hello/join.req/ping/err，其余一律 E_NOT_MEMBER，不解析业务字段 |
| 成员准入 | gid 在本地群列表内且 deviceId 在该群成员表内；新成员只能经 §2.3 七步 |
| 重放与时钟 | hello nonce 16 B + chanId 绑定（跨通道重放签名必失败）；join.req 的 nonce + ts ±300 s + 应答方 LRU 256；ts 只用于防重放与展示，**排序一律用 Lamport**，偏移超 ±300 s 回 E_CLOCK（带本地 ts）并重试一次 |
| 限速 / 脱敏 | join.req 失败 5 次/10 min/源 IP、30 次/h/群；其余帧 60 帧/s/连接。日志不打 keySeed/kJoin/kGrp，邀请码只打前 4 字符 |

## 9. 消息类型总表

| type | 名称 | 方向 | 阶段 | 作用 |
|---|---|---|---|---|
| 0x01 | hello | 双向，建连首帧 | M1 | 身份 + 通道绑定 + 群归属证明 |
| 0x02 | join.req | 新成员 → 成员 | M1 | 用邀请码申请加入 |
| 0x03 | join.ok | 成员 → 新成员 | M1 | 下发 keySeed、groupId、群快照 |
| 0x10 | sync.req | 双向 | M1 | 文件摘要 + 评论游标 |
| 0x11 | file.list | 应答 | M1 | 文件清单分页 |
| 0x12 | file.add | 广播 | M1 | 新文件 / 元信息更新 |
| 0x20 | log.sync | 请求 | M1 | 拉评论缺页 |
| 0x21 | log.ops | 应答 | M1 | 评论分页结果 |
| 0x22 | log.op | 广播 | M1 | 新评论推送 |
| 0x23 | member.name | 广播 | M1 | 用户名记录 |
| 0x30 | peer.announce | 广播 | M1 | 交换 BT 端点 |
| 0x40/0x41 | ping·pong | 双向 | M1 | 保活与应答 |
| 0x42 | bye | 双向 | M1 | 主动断开（带原因） |
| 0x7F | err | 双向 | M1 | 错误回报 |
| 0x50 | group.rotate | 广播 | M2 | 换码换钥（§2.4） |
| 0x51 | log.del | 广播 | M2 | 评论墓碑 |
| 0x52 | file.del | 广播 | M2 | 文件移出群 |
| 0x53 | team.part | 广播 | M2 | 组队片分配 |
| 0x60 | tracker.hint | 应答 | M2 | 跨网候选下发（M1 直连 tracker，无此消息） |

分层：M1 = 上表 15 个 M1 类型，覆盖建群、加入、发包、下载、评论全闭环。M2 = 5 个 M2 类型 + caps 扩展，覆盖轮换、删除、组队、跨网 tracker。M3 = 公开社区，私有层零变更，只加公开 SessionManager 与公开种子视图。

## 10. 字段与示例

通用字段（未特别说明即含）：gid(16) ‖ dev(16) ‖ nonce(32 hex, 16 B) ‖ ts(u64)；带签名者另有 sig(128 hex)。

**0x01 hello**（建连后双方各发两帧，先 `sig=""` 后带 `sig`，见 §5.1）：proto="hv1" ‖ rev=u8(1) ‖ caps=[strings] ‖ proof=64hex（HMAC(kGrp, signBytes("huchuan/proof1/v1",[gid,chanId]))，非成员为空）‖ gid/dev/pub/nonce/ts 通用 ‖ sig=signBytes("huchuan/hello1/v1",[dev,gid,本端 nonce,对端 nonce,chanId])。未入群时 gid 为 ""。

~~~
{"t":"hello","proto":"hv1","rev":1,"gid":"7Q2F9C4M1XZ8KD3V","dev":"7Q2F9C4M1XZ8KD3V","pub":"9c4e1b7a…（128 hex，64 B 裸值，无 0x04）","nonce":"3f9a1c…（32 hex）","caps":["files","log","peer"],"ts":1762381200000,"proof":"7d1e…（64 hex）","sig":"00b9…（128 hex）"}
~~~

**0x02 join.req**：gid/dev/pub/nonce/ts 通用（gid 取自应答方 hello）‖ mac=64hex（HMAC(kJoin, signBytes("huchuan/join1/v1",[dev,pub,nonce,ts]))）。例：{"t":"join.req","gid":"7Q2F9C4M1XZ8KD3V","dev":"K2M9QXDP4T8VNB2C","pub":"c7d2f09b…（128 hex，64 B 裸值）","nonce":"9b2e44…","ts":1762381210000,"mac":"5a0c…"}

**0x03 join.ok**：gname(≤48 B) ‖ epoch(u32，M1 恒 0) ‖ keySeed(64hex) ‖ fp(8 字符) ‖ members([{dev,name,nameSeq,pub}]) ‖ files(快照，>32 条置空) ‖ cursors([{a,up}]) ‖ gid/dev/nonce/ts 通用（nonce 回显 join.req 的 nonce）‖ sig=signBytes("huchuan/joinok1/v1",[dev,gid,keySeed,fp,nonce,ts])。

接收方 MUST 用 hello 阶段登记的应答方公钥验这条 sig，验不过就断开，不装群。keySeed 是群密钥根，这条签名把 keySeed 的下发者钉在 dev 上，字段里的 nonce 则把这次下发钉死在这一条通道的这一轮加入上：同一份 join.ok 搬到别的通道或别的 join.req 上必然验不过。

members 每条带 `pub`（64 B 裸值，128 hex，与 §1.2 公钥编码、本节 hello 的 pub 同一口径）：接收方 MUST 先算 deviceId(pub) 与该条的 dev 比对，相等才登记公钥，不等即丢该条、不登记（§1.2、§1.3）。登记进来的公钥就是其后验 join.ok 快照与 file.list 条目签名的依据（§7）。

~~~
{"t":"join.ok","gid":"7Q2F9C4M1XZ8KD3V","dev":"7Q2F9C4M1XZ8KD3V","nonce":"0d7f…","ts":1762381210400,"gname":"老板的群","epoch":0,"keySeed":"4e21…（64 hex）","fp":"K2M9QXDP","members":[{"dev":"7Q2F9C4M1XZ8KD3V","pub":"9c4e…（128 hex）","name":"老板的笔记本","nameSeq":2}],"files":[],"cursors":[{"a":"7Q2F9C4M1XZ8KD3V","up":41}],"sig":"93ab…（128 hex）"}
~~~

**0x12 file.add**：entry={fileId, name, size, pieceSize, addedBy, addedAt, magnet, torrent(可选 ≤47 KiB)} ‖ sig=signBytes("huchuan/file1/v1",[fileId,name,size,pieceSize,addedBy,addedAt])。

~~~
{"t":"file.add","entry":{"fileId":"8b3f0c0e1d2a4b6c8d0e2f4a6b8c0d1e2f3a4b5c","name":"发布会素材.zip","size":734003200,"pieceSize":262144,"addedBy":"7Q2F9C4M1XZ8KD3V","addedAt":1762381300000,"magnet":"magnet:?xt=urn:btih:8b3f0c0e1d2a4b6c8d0e2f4a6b8c0d1e2f3a4b5c&xl=734003200","torrent":"ZDg6…（base64）"},"sig":"e77a…（128 hex）"}
~~~

**0x10 sync.req**：fdigest(8 字符) ‖ cursors ‖ nonce。例：{"t":"sync.req","gid":"7Q2F9C4M1XZ8KD3V","fdigest":"M3Y7Q2F9","cursors":[{"a":"7Q2F9C4M1XZ8KD3V","up":41}],"nonce":"0c1d2e3f"}
**0x11 file.list**：entries(≤32，每条 entry 结构同 file.add.entry 且 MUST 带该文件 file.add 的原 `sig`，见 §7) ‖ more(bool) ‖ nonce。例：{"t":"file.list","entries":[…],"more":false,"nonce":"0c1d2e3f"}
**0x20 log.sync**：cursors ‖ limit(u16 ≤200) ‖ nonce。例：{"t":"log.sync","gid":"7Q2F9C4M1XZ8KD3V","cursors":[{"a":"7Q2F9C4M1XZ8KD3V","up":41}],"limit":200,"nonce":"aa01"}
**0x21 log.ops**：ops(≤200) ‖ more ‖ nonce。例：{"t":"log.ops","ops":[{…op…}],"more":false,"nonce":"aa01"}
**0x22 log.op**：{"t":"log.op","op":{…}}，op 字段见 §6。
**0x23 member.name**：{"t":"member.name","dev":…,"pub":…(128 hex)、"name":…,"nameSeq":3,"ts":…,"sig":…}，见 §1.3。
**0x30 peer.announce**：gid ‖ dev ‖ btPort(u16) ‖ btHost(可选，缺省用 TLS 对端地址) ‖ plat(可选) ‖ seeds(可选) ‖ ts ‖ sig。例：{"t":"peer.announce","gid":"7Q2F9C4M1XZ8KD3V","dev":"7Q2F9C4M1XZ8KD3V","btPort":51413,"plat":"windows","seeds":2,"ts":1762381200500,"sig":"1c2d…"}

    两个新增的可选字段（口径与 btHost 相同：不进 peer1 签名域，缺省或认不出都不拒绝整帧）：
    - plat：发送方平台，取值只有 android / windows / unknown；缺省或认不出的值一律按 unknown 处理。
    - seeds：发送方自报的「本群当前做种文件数」（u32，≥0，缺省 0）。收端只用于显示成员做种数，不据此改任何准入或传输判定。
    两者都在本机做种集合变化时重新广播一次（共享新文件、下载完成转做种、休眠摘掉做种、建连后的首帧），不需要额外的心跳周期。
**0x40/41 ping·pong**：{"t":"ping","ts":1762381200000}；**0x42 bye**：{"t":"bye","code":"idle"}；**0x7F err**：{"t":"err","code":"E_RATE","msg":"join too frequent","ref":"aa01"}

| err code | 触发 | | err code | 触发 |
|---|---|---|---|---|
| E_VERSION | proto/ver 不匹配 | | E_JOIN_CODE | invite MAC 不匹配 |
| E_PROTO | 必需字段缺失、类型错、JSON 非法 | | E_JOIN_CLOSED | 群关闭加入（TXT r=0） |
| E_SIG | 签名或 proof 校验失败 | | E_RATE | 触发限速 |
| E_NOT_MEMBER | 非成员发了业务消息 | | E_CLOCK | ts 超出 ±300 s |
| E_TOO_LARGE | 单帧或单字段超限 | | E_UNSUPPORTED | 收到 caps 未声明的类型 |
| E_INTERNAL | 对端内部错误 | | — | — |

## 11. 完整时序（A 建群 → B 加入 → A 发文件 → B 下载 → B 评论 → A 看到）

1. A「新建群组」：生成 groupId(10 B 随机)、gHandle(8 字符随机)、keySeed(32 B)、8 字符 secret → 补算 8 字符 check → 得 16 字符码，P6 显示 4-4-4-4。
2. A 起消息层 TCP+TLS 监听 47100（占用顺延）、起私有 SessionManager（DHT/LSD/UPnP/NAT-PMP 全关）监听 0.0.0.0:51413。
3. A 注册 mDNS：_huchuan._tcp / hv1-<gHandle> / SRV 47100 / TXT 只发 v=1,gid,bt=51413,r=1（不发群名与人数）。
4. B 在 P5 只输后 8 位；本机补算前 8 位，校验位对不上即「邀请码不对」，得到 codeBytes 与 kJoin。
5. B 浏览 _huchuan._tcp 取候选，按 §3.4 排序，向 A 发起 TLS。
6. 双向 hello 走两阶段（§5.1）：先各发一帧 `sig=""` 的 hello 交换 nonce，B 的 gid 与 proof 为空，A 带 gid 与 proof=HMAC(kGrp, gid‖chanId)；各再发一帧带 sig 的 hello，双方验签并核对 chanId，连接 bound。
7. B 发 join.req；A 按 §2.3 七步校验，第 4 步常量时间比对。
8. A 发 join.ok（keySeed、gid、gname、fp、成员表、游标），并把 B 写入成员表、广播给其他在线成员。
9. B 用 keySeed + codeBytes 派生 kGrp，算 fp 与 P6 指纹核对；此后 B 的 hello 带 proof，连接常驻。
10. 双向 sync.req：B 的 fdigest 为空 → A 回空 file.list 与空 log.ops；两端收敛。
11. A 在 P3「添加文件」选 700 MiB 的 zip：按 §4 取 pieceSize=256 KiB，建 v1 私有种子并重解析断言 private=true、无 trackers/nodes/url-list；随后 add_torrent_params 加种（flags=0x380000，save_path 为应用私有目录），recheck 通过即「做种中」。
12. A 广播 file.add（fileId=infohash、magnet、≤47 KiB 时内联 .torrent）；B 写库，P3 出现新行，未读 +1。
13. B 进 P4 打开「激活」：add_magnet 建任务（同样 0x380000），并广播 peer.announce 换端点。
14. A 回 peer.announce（btPort=51413）；B 用 TLS 对端地址 + 该端口 swig connect_peer；若 magnet 拿不到元数据，改用 file.add 内联的 .torrent 建任务，两条路径都必须支持。
15. 传输中 B 显示「下载中 n%」；两端 30 s ping 保活，掉线按候选表重连；到 100% 转「做种中」，不再需要 A 的字节，但群连接保持。
16. B 写评论：kind=0、seq=1、lamport=本地+1、prev=""，签名得 op 与 opId；广播 log.op。
17. A 收到：验签 → 查重 → prev 为空直接接收 → 按 (lamport,author,seq) 插入；A 的 P2 未读 +1。
18. B 回复：kind=1、parent=上一条 opId、seq=2、prev=第一条 opId、lamport=max(本地, 已见)+1。A 若当时离线，上线连 B 发 sync.req 即可用 log.ops 补齐；任一方重启则从本地库恢复 lamport（取已知 op 的 max）与各作者游标，重连即增量补齐。

## 12. 待裁决（不影响 M1 动工）

| # | 问题 | 本协议默认 | 影响面 |
|---|---|---|---|
| 1 | 邀请码真实熵：8 位可输（40 bit + 在线限速）还是必须全 16 位（80 bit） | **L1 裁决**：M1 接受 40 bit + 在线限速，保留全 16 位升级路径 | 只改 P5 输入框与文案，协议字段不变 |
| 2 | mDNS 向同网段广播群名与成员数（不持码者可见） | **已裁决**：默认不广播群名与人数（§3.1），开关留 M2 | 已落到 §3.1，P7 开关排 M2 |
| 3 | 群规模上限 | 32 人 | 超 64 人要把 §6 同步改成版本向量差集 |
| 4 | 自带 tracker 部署位置 | M2 再定 | 影响 §3.2 的 URL |
| 5 | 文件名长度上限 | 255 B | 与 Windows 路径长度限制联动 |

## 13. 实现偏差登记

本节逐条记录 `core/` 的 M1 代码与本文件的差异，来源是 docs/core.md §7 的自报，全部回代码核过。每条给证据、目标态与修复窗口，编号与 docs/core.md §7 一致。下表 `文件:行` 的路径前缀统一为 `core/src/main/kotlin/app/huchuan/core/m1/`。

| # | 项 | M1 现状（证据） | 目标态 | 何时修 |
|---|---|---|---|---|
| 1 | hello 两阶段 | 已按两帧实现：`sendHello(signed)` 分空 sig 与带 sig 两种（PeerLink.kt:144-179），收到空 sig 先补发带 sig 帧（PeerLink.kt:210-216、247-250） | §5.1，代码无需改 | 已收敛，本次仅落文档 |
| 2 | join.ok 验签 | 已收敛：domain 就是 `huchuan/joinok1/v1`（Crypto.kt:196），发端按 [dev,gid,keySeed,fp,nonce,ts] 签（PeerLink.kt:436-448）；收端依次核 dev 等于本通道对端、应答方公钥已登记、`DeviceIdentity.verify(…, JOIN_OK, …)`、nonce 回显本轮 join.req，任一不过即记 `join_ok_rejected` + 回 E_SIG + 断开、不装群（PeerLink.kt:483-508） | §10，代码即目标 | 已收敛（M1.1），本次仅落文档 |
| 3 | file.list 条目签名 | 已收敛：`FileEntry.sig` 是条目的第 9 个字段，随条目一起序列化（Protocol.kt:119-121、132、143、166），file.add 入库的就是签名原件（GroupRuntime.kt:428），转发也不再重签（GroupRuntime.kt:457）；file.list 用 `it.toJson()` 原样下发、按 fileId 排序 32 条一页（PeerLink.kt:604-622）。收端逐条按 addedBy 的公钥验：no_sig / no_pub / bad_sig 一律丢该条，不入库、不计入 fdigest（GroupRuntime.kt:282-301）；file.list 与 join.ok 快照共用这一个入口（PeerLink.kt:655-661、M1Kernel.kt:232-233） | §7，代码即目标 | 已收敛（M1.1），本次仅落文档 |
| 4 | 公钥分发只有 hello 一条路径 | 已收敛：三条路径都在，且每条都先算 deviceId(pub)==dev —— hello（PeerLink.kt:224-243）、join.ok.members 带 pub（发端 PeerLink.kt:422-430，收端先校验再 `registerPub` M1Kernel.kt:215-231）、member.name 带 pub（GroupRuntime.kt:356-377，转发时也带 pub）。未登记作者的 op 仍丢并记 `op_unknown_author`（GroupRuntime.kt:489），这是通道外的公钥缺失，不是分发缺口 | §10 三条记录都带 pub，代码即目标 | 已收敛（M1.1），本次仅落文档 |
| 5 | hello 只带一个 gid | `singleGroupOrNull()`，多群设备只声明其中一个群（PeerLink.kt:146、M1Kernel.kt:155） | 按 gid 分别建连，或 hello 带 gid 列表 | M2，多群同网段时才暴露 |
| 6 | keySeed 明文落盘 | 默认 `PlaintextSecretBox`，state.json 明文写（M1Kernel.kt:30-34、395-414） | Android Keystore ／ Windows PKCS12 | 上真机前（M1.1 Android 适配） |
| 7 | ip_filter 走 swig best-effort | `session_params.setIp_filter` 启动前塞入，失败退回「私有种子 + DHT/PEX/LSD 全关」（BtSession.kt:234-250） | §8 的跨网白名单 | M2 跨网前必须复核；M1 不改 |
| 8 | kind 按 8 字节整数签名 | `SignBytes.u8` 直接走 u64（Crypto.kt:176-177），op 用 u8 编码 kind（Protocol.kt:183） | 与 §1.2「整数固定 8 字节」一致，现状即目标 | 无需修；若要收紧成真 u8，需 domain 升版 |
| 9 | magnet 加种未实现 | `startDownload` 要求 entry.torrent 非空，否则记 `download_no_torrent` 返回 false（GroupRuntime.kt:131-136）；BtSession 只有 `addDownload(torrentBytes)`（BtSession.kt:120-140），没有 add_magnet | §11 第 13-14 步两条路径都支持 | M2，需 ut_metadata 先取到元数据才能 connect_peer |

不在本表的三类差异（docs/core.md §8 的退化点）：mDNS 不可用时退手动 IP:port、端口占用顺延、候选顺序 lan 优先。这三条与协议不冲突，不进本节。

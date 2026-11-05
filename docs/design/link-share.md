# 互传 · 链接分享 + 系统分享入口 + 分享管理（执行层设计）

- 版本 lk2 / 2026-11-06 · 执行层设计 · 字段定义以 `protocol.md` 为准，传输与部署决策以本文件为准
- 上位约束：AGENTS.md §4（一页一功能、元素 ≤5、必有入口出口、无说明性文案）、§9（技术基线）
- 输入（只读）：旧项目 `app/src/core/share.ts`、`app/src/worker/{index,registry,presence}.ts`、`docs/19`、`docs/50`、`docs/55`、`docs/72`、`docs/74`；本仓 `docs/design/protocol.md`、`docs/design/product-spec.md`
- 实测口径：链接参与（WebRTC DataChannel）7–10 MB/s，约为 WiFi 天花板的 28–40%（旧 `docs/66`）；群内私有 BT 25.4 MB/s
- L0 已拍板（2026-11-06）：信令复用旧项目的 Cloudflare Worker + Durable Objects，与 BT tracker 同一套部署；STUN 用 Cloudflare 公共 STUN；TURN 不做，标为「对称 NAT 兜底，M3 再议」；其余九项拍板见 §12

## 0. 结论

| 要什么 | 怎么给 |
|---|---|
| 系统分享菜单里有「互传」 | 一个透明 `ShareInActivity`，声明 ACTION_SEND + ACTION_SEND_MULTIPLE + `*/*`；收到即前台拷贝落地，不申请任何存储权限 |
| 「用其他应用打开」也能选到我们 | **不做**（§2.2：全类型 VIEW 污染打开方式菜单，语义也不符） |
| 应用内建链接分享，终态是二维码；点「分享链接」发到 QQ/微信 | P4「…」→「分享链接」→ P8，QR 只装 `https://<域名>/#s=<26 字符>`（约 60 字符，QR 版本 3–4）；「分享链接」是一条文本的 ACTION_SEND，Windows 无系统分享菜单时落为「复制链接」 |
| 接收方浏览器直接打开，不装 App | 静态网页 W1，三元素：文件名／进度／失败时重试 |
| 链接能看能撤销 | P7「分享管理」→ P9；撤销走签名请求，分享者本地当场断流 |

一句话技术结论：**分享者（安卓）跑 webrtc-sdk，浏览器跑原生 RTCPeerConnection，中间是那台已经要部署的 Worker（/announce 与 /signal 两条路径）；文件字节永不经过服务器；不部署 Worker 时只有跨网发现与链接分享不可用，局域网一切照常。**

## 1. 与既有基线的接缝

| 既有事实 | 本设计的用法 |
|---|---|
| `protocol.md` §1–§1.2（ECDSA P-256 身份、deviceId 派生、Crockford B32、domain 前缀签名）、§3.2（自带 tracker）、§4（一文件一 torrent、v1、piece 哈希） | token、offer 与撤销签名沿用同一套钥匙与记号，不新起身份体系；链接分享与 tracker 共用同一台 Worker，用 passkey 与配额隔离；分片校验借 fileId／pieceSize（§9） |
| 旧 `docs/74` 决定二：链接参与才走 WebRTC；决定四：不部署、不购买、不接第三方 TURN | 本文只服务链接这一格；TURN 记为 M3 再议，直连失败如实说（§4.5） |
| `product-spec.md` §4：元素 ≤5、机器能判的事不让用户点 | P8 / P9 / W1 全按此裁剪（§3） |

## 2. 系统分享入口（Android）

### 2.1 声明

~~~~xml
<!-- 先只声明这两个；不加 VIEW，不加存储权限。若验收发现有应用发 type=null 的 SEND，
     再补一个只有 action + category、不含 <data> 的 filter -->
<activity android:name=".share.ShareInActivity"
          android:exported="true"
          android:label="@string/app_name"
          android:noHistory="true"
          android:theme="@style/Theme.Huchuan.Transparent">
  <intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="*/*" />
  </intent-filter>
  <intent-filter>
    <action android:name="android.intent.action.SEND_MULTIPLE" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="*/*" />
  </intent-filter>
</activity>
~~~~

- `exported="true"` 必需（targetSdk 31+ 不写装不上）；`noHistory` 保证返回不会回到这个空壳页。
- `mimeType="*/*"` 是让人能分享**任意类型**的写法：更窄的声明（`image/*`、`application/*`）会漏掉 zip 与未知类型，用户会碰到"菜单里没有互传"。带类型的 intent 由它接住；type 为 null 的 SEND 只认不带 `<data>` 的 filter（上注）。类型过滤仍放到代码里判（0 字节、>2 GiB、超过 10 个，各一条明确拒绝）。
- 多文件走 `Intent.EXTRA_STREAM`（ArrayList<Uri>），API 33+ 同时兼容 `ClipData`；数量与 mime 以 `ClipData` 为准，某些 ROM 只填其一。
- **不申请** `READ_EXTERNAL_STORAGE`，**绝不申请** `MANAGE_EXTERNAL_STORAGE`：发送方带 `FLAG_GRANT_READ_URI_PERMISSION`，我们只需要 `contentResolver.openInputStream`。前者会被商店质疑，后者基本过不了审。也不需要 `<queries>`。

### 2.2 不做 ACTION_VIEW（"用其他应用打开"）

| 判据 | 结论 |
|---|---|
| 技术上能不能 | 能：加 VIEW + DEFAULT + `*/*` 就会被列进打开方式 |
| 代价 | ①全类型抢占：注册 ACTION_VIEW 会让「互传」出现在**所有**文件的「打开方式」候选里，还会挤进默认查看器的竞争；②语义错配：用户点开期望看到内容，却进了分享流程——**这是语义错误，不是功能缺失**；③授权模型不同：VIEW 的 Uri 常是 `file://` 或无授权 provider，API 24+ 大面积 `FileUriExposedException` |
| 结论 | M2 不做。真要做，最小代价是只声明 `application/*` 并单开一轮真机验收 |

### 2.3 收到文件之后：0 个选择

不选群、不选"链接还是群"。系统分享的语义是"把这个文件交给别人"，而群分享要求接收方已在同一个群里；文件管理器给不出群上下文。链接是唯一在两种情况下都成立的答案。

1. `ShareInActivity` 收 Intent → 收集 Uri（1–10 个；0 个、0 字节、>2 GiB、>10 个各一句话拒绝并退出）
2. **前台**流式拷贝到 `filesDir/share-in/<uuid>/`（Uri 授权只在这个 Activity 活着时有效，见 §2.4）
3. 生成 token（128 bit，客户端侧生成）→ 落本机记录 → `POST /api/share` 注册
4. 直接进 P8，状态行先「导入中 i/n」，完成后变「可分享」；退出：系统返回 → P2。这条分享与群无关，只住在 P9。冷启动时外部 intent 先存成 pendingAction → P0 照常自检 → 通过后直达 P8（首次启动仍先过 P1 用户名）

### 2.4 一条硬约束：URI 授权必须当场消费

`FLAG_GRANT_READ_URI_PERMISSION` 的授权挂在接收的 Activity（及其 task）上，随其销毁失效；`takePersistableUriPermission` 对系统分享常见的 provider 不保证成功。而链接分享要求分享者**持续在线数小时**，不可能靠一次性授权活着。因此：**收到即拷贝，拷贝完成才建链接**。代价是磁盘双份（原件＋应用私有副本）与一次拷贝耗时，验收要分别量 100 MB 与 1 GB。对照组：群内文件本来就是本机文件，零拷贝。

## 3. 页面

### P8 链接分享（终态是二维码）

| 元素 | 为什么必须留 |
|---|---|
| 二维码 | 主动能载体；只编码 `https://<域名>/#s=<26 字符>`，约 60 字符 |
| 「分享链接」 | 不经扫码的出口：Android 拉起系统分享菜单发到 QQ/微信；Windows 上这个按钮是「复制链接」直接进剪贴板。**二维码两端都留**（手机扫一下最省事，成本极低） |
| 状态行 | 唯一回答"现在能不能取、还有多久失效、正在发给谁"的位置 |

- 入口：P4「…」→「分享链接」；系统分享导入完成后自动进入；P9 点行 → 回到该链接的二维码页。出口：系统返回 → 进入它的那一页（P4 / P2 / P9）
- 状态：`导入中 1/3` · `可分享` · `7 天后失效` · `正在发送 42%` · `已失效` · `分享服务未配置` 加 `重试`；撤销**不在这页**（撤销是 P9 的动作，这页只出示凭据）；**首次创建链接时弹一次性 toast**「对方可见你的网络地址」，之后不再出现，不占元素预算

### P9 分享管理

| 元素 | 为什么必须留 |
|---|---|
| 分享列表（文件名／创建时间／被访问 n 次） | 主动能载体；一条有效链接一行 |
| 行内「撤销」 | 中断滥用的唯一动作；列表项内部操作，不计元素 |

- 入口：P7 一行「分享管理」；出口：系统返回 → P7，点行 → P8
- 列表只放仍然有效的分享：撤销或过期即移出列表，这同时删掉了"已撤销／已过期"两个 chip。撤销是不可逆破坏动作，允许一次确认；同机有效分享上限 20 条

### W1 网页接收页（浏览器，不计入 P 序列）

| 元素 | 为什么必须留 |
|---|---|
| 文件名 + 大小（一行两段；清单时带 1/3） | 确认取的是哪个文件 |
| 进度／状态（一行） | 唯一能看出在动还是卡住的地方 |
| 主按钮（**仅失败态出现**） | 失败后唯一出路，同时兜住浏览器对下载手势的要求 |

- 入口：浏览器打开链接；出口：关页面（浏览器自带，页面里不放返回首页的链接）
- 状态：`正在连接` · `{p}% {速度}` · `已保存` · `对方暂时离线` · `分享已撤销` · `分享已过期` · `无法直连` 加 `重试`；不装任何东西（无扩展、无 App、无账号），连上后**自动开始**不让用户点（§4.7）；页面必须 HTTPS（签名校验用 `crypto.subtle`）

## 4. 技术路径

### 4.1 为什么只能是 WebRTC DataChannel

| 方案 | 现状 | 否决理由 |
|---|---|---|
| 私有 BT（libtorrent4j） | App 内已用，25.4 MB/s | 浏览器没有 BT 客户端。WebTorrent 式浏览器节点只讲 WebRTC，与 TCP/µTP 节点不互通（旧 `docs/19` §9），libtorrent4j 无法给浏览器供片 |
| WebSocket／HTTP 经服务器转发；WebTransport | 到处能连 | 字节过服务器：带宽自己付、隐私承诺作废、DO 按消息计费会烧穿免费额度。WebTransport 同样要一台 relay |
| **WebRTC DataChannel** | 旧项目已跑通 7–10 MB/s | 唯一同时满足「0 安装」「服务器不经手字节」「安卓有官方实现」的路径 |

### 4.2 安卓侧 WebRTC：两个候选

| | 甲 `io.github.webrtc-sdk:android:150.7871.01` | 乙 应用内 WebView 跑同一张网页 |
|---|---|---|
| 体积 | arm64 `.so` 约 12 MB（`abiFilters` 只出 arm64） | 0 |
| 旧项目验证 | 已用，7–10 MB/s；`getStats()` 能取候选对、丢包、RTT | 未验证，拿不到 ICE 统计 |
| 代价 | APK +12 MB，需锁版本 | 要前台服务保活 WebView（通知栏常驻），字节多过一次 JS 桥，系统 WebView 版本不可控 |

**选甲。** 乙留给 Windows 壳：那里 WebView2 与本地 HTTP/WS 控制面本来就有（架构 A），零成本复用。

二维码只做**编码**（接收方用系统相机或浏览器扫，我们不实现扫码）：甲 `com.google.zxing:core:3.5.3`（Apache-2.0，约 480 KB，只出 BitMatrix 自己 Canvas 画）；乙 `com.github.alexzhirkevich:qrose`（Compose 原生，约 200 KB，与 Compose 版本耦合）。**选甲**：省 200 KB 换一个 UI 框架耦合，不划算。

### 4.3 信令：与 BT tracker 共用同一套 Worker（L0 已定）

不新起服务，不做自建方案：复用旧项目的 Cloudflare Workers + Durable Objects，与 BT tracker 是**同一次部署、同一套密钥与配额**，靠路径分开。

| 路径 | 归谁 | 作用 |
|---|---|---|
| `/announce` | 跨网发现（M2 tracker） | 按 passkey 登记消息层与 BT 端点，语义同 `protocol.md` §3.2 |
| `/signal` | 链接分享 | 分享者与访客的 WebSocket，只转发 SDP／ICE 与控制帧 |
| `/api/share*` | 链接分享 | 分享注册表，只存元信息，不存字节 |

- 密钥与配额共用一套：tracker passkey 仍由 kGrp 派生（`protocol.md` §3.2），限速表按 deviceId 建，但**必须按路径分桶**——一条路径被刷时另一条立刻跟着降级，否则一个刷 /signal 的人会把 /announce 的配额吃光。
- DO 分两个：注册表（SQLite，一条分享一行）与在线扇出（deviceId → WebSocket）。**必须开 WebSocket Hibernation**，否则分享者挂一整天的那条连接会按 duration 全天计费（旧 `docs/11`）。
- 信令永远不碰文件字节（旧 `worker/index.ts` 注释：DO 每条消息计一次请求，数据走这里会烧穿额度）。

### 4.4 手动交换 SDP 能不能替代服务器：不能

机制上能——分享者 gather 完 ICE 后把整段 SDP 塞进链接，访客把 answer 复制回来粘一次；数字上不行——链接从约 60 字符涨到 1.6–3.4 KB（host 候选 600–900 B，加 srflx 1.2–2.5 KB，base64url 再涨三分之一），二维码从版本 3–4（29–33 模块）涨到版本 30 以上（137×137 模块起），手机对不上焦、截图不可读；交互从"扫一次"变成"扫一次＋网页复制 answer＋回分享者粘贴一次"。它把"一页一功能"变成"两页两次粘贴"，不能当主路径。中间档（分享者在局域网起本地 HTTP 服务、同 WiFi 打开即取、0 台服务器；代价是链接只在同一 WiFi 成立、明文 HTTP、多开一个监听端口）同样 M2 不做，记此备查。

### 4.5 STUN / TURN

| 项 | 结论 |
|---|---|
| STUN | 用 Cloudflare 公共 STUN `stun:stun.cloudflare.com:3478`（旧项目同值）。只要任一端在 NAT 后就必需；没有它只剩 host/mDNS 候选，仅同网段可用。打洞成立的前提是至少一侧为锥形或端口受限型 NAT，且两侧都先向对方发包（旧 `docs/10`） |
| STUN 救不了 | 对称 NAT（每个目标换外部端口）、运营商 CGNAT（手机蜂窝几乎全在 CGNAT 后）、企业防火墙只放行 443、AP 客户端隔离。Google 千万级通话样本里中继占 17.7% |
| TURN | **不做**，标为「对称 NAT 兜底，M3 再议」。理由是成本（旧 `docs/74` 决定四：不部署、不购买、不接第三方） |
| 后果 | 「手机蜂窝分享 → 家宽访客」这一格可能直接失败。界面必须如实说「无法直连」并给「重试」，不许停在"正在连接"骗人 |

## 5. 时序

**建链（分享者 A／访客 B）**

1. A 在 P4「…」→「分享链接」：取本地文件（群文件零拷贝，系统分享来的用导入副本），生成 token（128 bit）与 expiresAt。
2. A 发 `POST /api/share`：{token, deviceId, pub, files[], ttlSec}。服务端校验 deviceId 等于 B32(SHA-256("huchuan/dev1/v1"‖pub)[0..10])，只存 H(token)，回 createdAt / expiresAt。
3. A 进 P8 出二维码；同时连 `/ws/signal`（带 dev／ts／nonce／sig，签名域 `huchuan/ws1/v1`）。
4. B 扫码或点链接 → W1 从 fragment 取 token → `GET /api/share/{token}` 拿到 {status, files[], pub, deviceId, expiresAt}；A 不在线就直接显示「对方暂时离线」，不再尝试建链。
5. B 连 `/ws/signal?ep=…`（匿名，ep 为页面内随机 16 字符）→ 发 `share-join{token, epPub}`；服务端按 H(token) 找到记录，转给 A：`share-incoming{shareId, visitorId, epPub}`。
6. A 校验它是自己未撤销的分享、当前访客数 < 1，否则回 `err{busy}`；建 RTCPeerConnection（一个 STUN）→ createDataChannel("huchuan/1", ordered) → gathering 完成后发 `offer{sdp, sig}`，sig = signBytes("huchuan/share1/v1",[token, dtlsFp, expiresAt])，dtlsFp 取自 SDP 的 a=fingerprint。
7. B 验签（pub 推出的 deviceId 必须等于记录里的 deviceId，再验 sig 与 token）→ setRemoteDescription → answer → DTLS 握手 → 通道打开。任一步 15 s 超时即 `err{connect-failed}`。

**传输**

8. A 发控制帧 {"t":"meta","files":[…]}，随后按清单顺序推第 i 个文件；二进制帧 = u32be(index) ‖ payload，块 16 KiB（对齐旧项目 CHUNK_SIZE）；背压为 `bufferedAmount > 8 MiB` 暂停发送。
9. B 每 256 KiB 回 `ack{upTo}`；收完一个文件即落盘并发 `done{i}`，A 记一次"被访问"，进入下一项；全部完成发 `bye{code:"done"}` 并关通道。
10. 失败出口穷举：连不上／传输中断／浏览器拒绝保存 → W1 出现「重试」；A 侧同一访客 90 s 内无取件请求则收回连接（旧项目真机验证过的窗口）。

**撤销与过期（P9）**

11. P9 行内「撤销」→ 一次确认 → A 本地关掉该分享所有通道、标记撤销、从 P9 移出。
12. A 发 `DELETE /api/share/{token}`，body 带 {deviceId, ts, nonce, sig}（签名域 `huchuan/revoke1/v1`）。必须签名，否则任何拿到链接的人都能替你撤销。
13. 服务端标 revoked（**不删记录**：删除只能表达"没找到"，访客有权知道是撤销还是过期），并踢掉该 share 上的访客 WS。已在传的访客由第 11 步断流：**分享者是唯一字节源，撤销不需要靠长连接踢人**，服务端踢 WS 只防重连。重开链接 → 「分享已撤销」。
14. expiresAt 到点：服务端查询时判 expired，A 下次进 P9 移除该行；已在传的不打断（有效期管"能不能开始"，不管"要不要掐断"）。

## 6. 字段与协议

| 项 | 定义 |
|---|---|
| token | 26 字符 Crockford B32（128 bit），**客户端生成**；链接 `https://<域名>/#s=<token>` |
| 放 fragment 而不是查询串 | token 就是凭据：查询串会进服务器访问日志、跟 Referer 发给第三方、被代理原样缓存；fragment 不随请求发出（旧 `docs/19` §7） |
| 服务端存量 | 只存 H = SHA-256("huchuan/tok1/v1"‖token)，库泄露也取不了件 |
| 分享记录 | {h, deviceId, pub(128 hex), files[{name ≤255 B, size, mime}], createdAt, expiresAt, state: active／revoked, visitCount} |
| `POST /api/share` | 入 {token, deviceId, pub, files, ttlSec}；出 {expiresAt}。同机有效分享 ≥20 回 429 |
| `GET /api/share/{token}` | 出 {status: available／offline／revoked／expired, files[], pub, deviceId, expiresAt}。四态只在 Worker 边界合成一次，别处不再判 |
| `DELETE /api/share/{token}` | 签名请求（第 12 步），回 {ok, revokedAt} |
| WS 分享者 | `/ws/signal?dev&ts&nonce&sig`，sig = signBytes("huchuan/ws1/v1",[dev,nonce,ts])；服务端用该设备任一有效分享的 pub 验签。补上旧项目"WS 只认查询参数里的 deviceId"的洞（旧 `docs/72` 需求 4） |
| WS 访客 | `/ws/signal?ep=<16 字符>`，匿名，一页一连接，断开即回收 |
| 信令消息 | share-join{token,epPub} · share-incoming{shareId,visitorId,epPub} · offer{sdp,sig} · answer{sdp} · ice{candidate} · bye{code} · err{code}；**非 trickle**（候选随 SDP 一起发：消息少一半、失败模式少一半，代价是建链多等 1–2 s）。err code：busy（已有访客）· gone（撤销或过期）· connect-failed · rate · too-large |
| 数据通道 | 名 `huchuan/1`，ordered；文本帧走控制 JSON，二进制帧 u32be(index) ‖ payload，块 16 KiB；背压 8 MiB，确认间隔 256 KiB |

## 7. 安全

| 面 | 做法 |
|---|---|
| token 熵与有效期 | 128 bit 随机，穷举不可行，真实风险是"链接被转发"而不是被猜。**有效期 7 天**（L0 定）：链接只在分享者在线时真的能取件，24 h 对"发给家人、隔天再点"太短；撤销随时可用，泄漏窗口由分享者在线时长本身兜底。过期不续期、不自动重生 |
| 撤销为什么能立刻生效 | 分享者是唯一字节源：关本地通道，字节当场停；服务端标 revoked 只负责"后来者打不开"与"踢掉重连"，不需要维持踢人长连接。撤销请求必须签名，否则拿到链接的人能替你撤销 |
| 被转发滥用 | 7 天上限 ＋ 同时 1 个访客 ＋ P9 里"被访问 n 次"是唯一异常信号 ＋ 服务端对同一 H 的 share-join 限速 3 次/分钟。不做取件码或密码：多一个凭据就多一套状态，老板没要求 |
| 二维码泄露边界 | 二维码 = 链接 = 凭据，等于把钥匙贴在墙上：截图、拍照、转发都不可挡，撤销是唯一止血手段。fragment 不进服务器日志，但会进浏览器历史与截图 |
| 分享者地址暴露 | ICE 交换让访客能看到分享者的公网地址（内网地址已被浏览器换成 mDNS 名）。这是点对点固有属性（旧 `docs/19` §9），不是缺陷。告知方式是**首次创建链接时的一次性 toast**「对方可见你的网络地址」（知情同意，不是装饰），并同时写进对外说明 |
| 信令服务器能作恶到什么程度 | 能不下发 offer（拒绝服务），能看"谁在何时取件"；**不能**换成自己的端点：offer 里的 DTLS 指纹由设备私钥签名，访客用注册表里的公钥验（第 7 步）。旧项目只验"公钥推出的 deviceId 对得上"，而转发时 from 由服务端填，服务端能冒充分享者发 offer——签名指纹补的就是这个洞 |
| 内容完整性与日志 | 优先用 v1 piece 哈希（同一文件已在群里做种时直接借 fileId／pieceSize）；没有种子的按"总字节数 ＋ 每 16 KiB 块索引连续"校验，与旧项目同级（旧项目没有内容哈希，见 `docs/19` §9）。日志不打 token 全文（只打前 4 字符）、不打 SDP、不打访客地址 |

## 8. 分享管理

- 入口：P7 一行「分享管理」→ P9。P7 因此从 4 个元素变成 5 个，仍在预算内。
- 列表字段：文件名（清单时「N 个文件 · 共 X」）／创建时间／被访问 n 次。不显示状态 chip，因为列表里只存在仍然有效的分享。
- 撤销：行内「撤销」→ 一次确认 → 第 11–13 步 → 行立即消失；过期：下次进 P9 时清掉该行，expiresAt 以服务端记录为准。
- 「撤销后二维码页立刻失效」的准确含义：P9 移出该行后 P8 无从再进入；已发出去的那张二维码仍是那张图，扫它只会得到「分享已撤销」——失效发生在服务端登记与数据通道，不在图片上
- 数据从哪来：本机记录是事实源（离线也能看）；`GET /api/shares?deviceId=` 只用于把"服务端已撤销"的条目对齐回来。没有账号，所以列表天生只显示这台设备建的分享。

## 9. 和私有 BT 的关系

**链接场景为什么不能直接用 BT。** 浏览器的传输能力只有四条：HTTP(S)、WebSocket、WebRTC、WebTransport。BT 是跑在 TCP/µTP 上的私有协议，浏览器里没有实现；WebTorrent 给浏览器补的是"用 WebRTC 当 peer 协议"的另一套实现，与 TCP/µTP 的 peer 不互通（旧 `docs/19` §9 已记）。我们的私有种子还额外关了 DHT/PEX/LSD，发现只能靠自带 tracker，浏览器更没资格参与。所以这一格不存在"用 BT 省钱"的选项——问题从来不是"要不要用 BT"，而是"浏览器能不能当 peer"，答案是不能。

| 能复用（省一次） | 不能复用（各来一次） |
|---|---|
| 磁盘上同一份文件：不复制第二份，共用同一读取器 | 连接：BT 是 TCP/µTP，WebRTC 是 DTLS/SCTP，两条完全不同的栈 |
| 分块与校验：直接借现成的 fileId／pieceSize／piece 哈希，不重新分块、不重新哈希 | 上传路径：一次上传只服务一个接收方 |
| 元数据：fileId、大小、mime 不再计算 | peer 发现：一条靠 mDNS/tracker，一条靠信令服务器 |

"省一次上传"要说得准确：**不会出现两份上传**。BT 的字节发给要下载的原生端，WebRTC 的字节发给浏览器，是两个不同的接收方，各发一次是物理必然；真正省掉的是建种、读盘、哈希这三件事。另：系统分享导入的新文件**不顺手建种进群**——链接分享不需要 torrent，顺手建种是多一个隐式副作用、多一份磁盘状态，老板没要求。

## 10. 基础设施、额度与降级

### 10.1 一套部署

| 件 | 形态 | 月成本 |
|---|---|---|
| 域名 ＋ 静态网页 | 自有域名（或 `*.pages.dev` 子域）＋ Cloudflare Pages 上的一张 HTML 与一个 JS | 0（或几十元/年） |
| Worker + DO | **同一套**：`/announce`（跨网发现）＋ `/signal` 与 `/api/share*`（链接分享） | 0（免费层） |
| STUN | Cloudflare 公共 STUN；TURN 不做（M3 再议） | 0 |

文件字节 0 流量：服务器只搬 SDP 与几十字节的控制帧，链接分享不产生带宽账单。

### 10.2 免费额度的现实约束

| 路径 | 折算口径 | 一个人的额度能吃多少 |
|---|---|---|
| Workers 请求 | 免费 10 万请求/天（旧 `docs/19` §9 口径：Worker 与 DO 各 10 万；部署前复核一次） | 全站共同上限 |
| tracker announce | **10 min 一次**（`protocol.md` §3.2 的 interval=600，不改）→ 144 次/设备/天 | 全天在线约 694 台设备即吃满；按每天在线 8 h（48 次）折算约 2,083 台 |
| 链接信令 | 一次取件 8–12 条 WS 消息，入站按 20:1 折算约 2–3 请求，加 2 次 HTTPS 查询 | 约 5 请求/次取件 → 单独吃满要 2 万次/天 |
| DO duration | 开着 Hibernation 时空闲不计；不开则分享者那条常驻连接整天按 duration 计费 | 不开就一定会超 |

混算示例：1,000 台设备在线 8 h（各 48 次 announce ＝ 48,000 请求）＋ 2,000 次取件（10,000 请求）＝ 5.8 万，**占额度 58%**。1,000 台在线 8 h 就已过半，所以降频与分片不是"接近上限才用"，而是 M2 上线前就要有的开关。

额度接近上限时按顺序用三条（都不改客户端）：

1. **降频**：服务端下发的 announce interval 10 → 30 min，announce 量立刻降到三分之一（客户端只跟随服务端下发的值，不用改客户端）。
2. **分片**：在线扇出 DO 按 deviceId 前缀分成 N 个实例（注册表 DO 不动），请求数不变但单实例的限制与延迟下降。
3. **收紧**：链接有效期 7 天 → 24 h，同一链接 share-join 限速降到 1 次/分钟。

### 10.3 不部署 Worker 时（硬约束）

只这两件事不可用：**跨网发现**（`/announce`）与**链接分享**（`/signal` 与 `/api/share*`）。局域网 mDNS、手动 IP:port、私有 BT 传输、建群／入群／评论／组队全都不受影响。

UI 上怎么体现——**不弹任何错误弹窗**：

| 位置 | 显示 |
|---|---|
| P0 自检 | 走既有的「仅局域网可用」：一行警告、约 1s 后自动进入，不新增错误态 |
| P7 网络状态（只读一行）；P4「…」 | 状态行写「仅局域网」；「…」里没有「分享链接」这一项（构建期没配 base URL 就不出现） |
| P7 | 没有「分享管理」这一行 |
| 系统分享进来 | 一句 toast「分享服务未配置」后退出，不建链接、不留半截状态 |

## 11. 风险

| # | 风险 | 处置 |
|---|---|---|
| 1 | **蜂窝分享大概率打洞失败**：手机在 CGNAT 后、没有 TURN，别人可能一次都取不到 | 如实显示「无法直连」；对称 NAT 兜底列 M3（§12-2） |
| 2 | 7–10 MB/s 是局域网数字；跨网受上行带宽限制，1 GB 按 5 MB/s 要 3.5 分钟，浏览器页面必须活着 | W1 明示进度与速度；失败给「重试」；软上限 1 GiB |
| 3 | 系统分享要落盘拷贝（双份磁盘＋导入耗时），浏览器落盘又只能靠内存 Blob（`showSaveFilePicker` 只有 Chromium 桌面有） | 超 2 GiB 直接拒绝；单文件 1 GiB 硬上限，超过提示改用 App；流式落盘（Service Worker）排 M3 |
| 4 | 分享者进程被杀或切后台锁屏 → 链接变 offline，用户以为链接坏了 | 四态明确区分 offline 与 revoked；M2 不加前台服务，如实测断得频繁再开"分享期间常驻通知"，那是一次新的 L0 决策 |
| 5 | 额度被单条路径刷穿，连带 tracker 一起挂；信令服务器还看得到元数据（谁在何时取件） | 限速按路径分桶＋降频/分片（§10.2）；元数据这点写进对外说明 |

## 12. L0 已拍板（2026-11-06）

| # | 决策 | 拍板结果 | 落到哪 |
|---|---|---|---|
| 1 | 链接有效期 | **7 天**。24 h 对"发给家人、隔天再点"太短；撤销随时可用，泄漏窗口由分享者在线时长本身兜底 | §7、§10.2 收紧档、P8 状态示例 |
| 2 | 对称 NAT 兜底（TURN） | 维持 M3 再议，前期如实失败 | §4.5、§11-1 |
| 3 | 同时访客数 | 1 | §5-6、§7 |
| 4 | 浏览器单文件上限 | 1 GiB | §3 W1、§11-3 |
| 5 | 撤销后 P9 那行 | 直接消失 | §3 P9、§8 |
| 6 | P8 的网络地址提示 | 不加常驻文案；**首次创建链接弹一次性 toast**「对方可见你的网络地址」，之后不再出现，并写进对外说明（知情同意，不是装饰） | §3 P8、§7 |
| 7 | ACTION_VIEW | 不做，只做 ACTION_SEND：注册它会让互传出现在所有文件的「打开方式」候选里，用户期望看到内容却进了分享流程，还会挤进默认查看器竞争——语义错误，不是功能缺失 | §2.2 |
| 8 | 系统分享多选 | 合成一条链接，上限 10 | §2.3、§3 W1 |
| 9 | Windows 的按钮 | 二维码与「复制链接」两个都留 | §3 P8 |

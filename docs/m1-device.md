# M1 真机验收（Android ↔ 桌面）

一条命令把 M1 全流程在真机上跑完并留证：

```bash
bash scripts/m1-device.sh            # 全流程 + 崩溃压力（3 × 128 MiB，第 2 轮中途取消一次）
bash scripts/m1-device.sh --quick    # 只跑功能闭环，跳过压力
bash scripts/m1-device.sh --rounds 2 --mib 64
```

最后一行是 `PASS` / `FAIL`，退出码 0 / 1。证据全部落在 `artifacts/m1-device/`。

## 1. 链路

| 链路 | 组成 | 现状 |
| --- | --- | --- |
| `android-desktop` | Mi MIX 2S（Android 13 / API 33）+ 桌面 JVM 对端（同一台 Windows） | 本轮验收链路 |
| `android-android` | 两台安卓真机 | 未实现：需要第二个 UI 驱动；第二台安卓在线时脚本会提示但仍走 android-desktop |

脚本自己判定：`adb devices` 里在线设备 ≥ 1 就用第一台（可用 `M1_SERIAL` 指定），
并在输出第三行打印 `chain : android-desktop`。设备离线不会报错退出，只是换个链路。

桌面端跑的是同一份 `core` 内核（`app/tools/peer/DesktopPeer.java`，脚本用 javac 现场编译），
所以这一条链路同时证明「Android 内核 ↔ Windows 内核」互通。

## 2. 手机侧做了什么

### 2.1 自编 arm64 .so 进包

- `app/build.gradle.kts` 的 `prepareM1Natives` 把 `artifacts/build-libtorrent/run5-sde1/libtorrent4j.so`
  同步到 `app/build/m1-jniLibs/arm64-v8a/`，再作为 `jniLibs` 源目录打进 APK；
  `ndk.abiFilters = arm64-v8a` 保证不混入别的 ABI。
- 用 `org.libtorrent4j:libtorrent4j`（纯 Java 绑定，jar 里没有 .so），**不用** `libtorrent4j-android-arm64`
  ——官方 .so 在 Android 13 会 Scudo abort（AGENTS.md §9）。
- 脚本第一步就断言 APK 里 `lib/arm64-v8a/libtorrent4j.so` 的 SHA-256 等于自编产物（`apk.selfbuilt_arm64_so`）。
- `packaging.jniLibs.keepDebugSymbols` 让 AGP 不要再 strip 这个 .so。

### 2.2 权限与 mDNS

`AndroidManifest.xml` 申请：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、
`CHANGE_WIFI_MULTICAST_STATE`。targetSdk 仍是 36。

`M1Runtime` 在进程启动时拿 `WifiManager.MulticastLock`（`setReferenceCounted(false)`，进程期间一直持有），
没有这把锁 Android 会把组播包直接丢掉，jmdns 收不到任何东西。内核日志里
`app_kernel_ready ... multicast_lock=true` 就是这一步的证据。

### 2.3 持久化与 keySeed

- 全部状态在应用私有目录 `/data/user/0/app.huchuan/files/m1/`：`state.json`、`identity.txt`、`bt/`、
  `share/`（做种副本）、`downloads/`。
- `keySeed` 不再明文落盘：`M1Kernel` 增加 `SecretBox` 出口，Android 侧用 `KeystoreSecretBox`
  （AndroidKeyStore 里的 AES-256-GCM，密钥不可导出）包裹后再写 `state.json`，
  字段旁边写 `keySeedProt=keystore-aes-gcm-1`。读回时方案对不上、或密钥失效（恢复出厂/刷机），
  该群直接跳过并记 `state_key_unseal_failed`，绝不退回明文。
- 等效兜底：Keystore 不可用时用 `IdentitySecretBox`——包裹密钥由 `identity.txt` 的 PKCS8 经
  HKDF-SHA256 派生，不额外落盘密钥材料。两者都只把密文写进 state.json。
- 桌面 harness 仍用 `PlaintextSecretBox`（与旧 `state.json` 兼容），这是刻意的：桌面没有 Keystore。

### 2.4 选文件：SAF → 私有目录

libtorrent 只认文件系统路径，`content://` 给不了它。P3「添加文件」走
`ActivityResultContracts.OpenDocument` → `FileImporter.copyTo()` 整份拷进 `files/m1/share/` →
`GroupRuntime.addFile(File)` 建私有 v1 种子并广播 `file.add`。原文件删掉不影响做种。

### 2.5 界面接内核

`InMemoryGroupStore` 换成了 `M1GroupStore`，全部数字来自内核实时状态：

| 页面 | 数据来源 |
| --- | --- |
| P2 首页 | `kernel.groups()`：做种数 = 各成员共享的文件数，组队人数 = 下载中的最大队伍，未读 = `GroupRuntime.unreadCount()`（G4 读游标现算，0 时不出角标） |
| P3 群组 | 文件清单 + 状态（做种中 / 下载中 / 休眠）+ 进度 + 做种者数；进入即 `markGroupRead` |
| P4 文件详情 | 激活/休眠 = 真的 `add_torrent` / `remove_torrent`；进度、上下行速率来自 BT 会话；评论来自签名 op 日志；打开即 `markFileRead` |
| P5 新群组 | 只收后 8 位 → `kernel.joinByTail8`（mDNS 发现 + 手动 IP:port 兜底） |
| P6 成员 | `members()` + 消息层连接表判在线；「做种 n」与平台直接取 `MemberRecord.seedingCount` / `MemberRecord.platform`（G3） |

**文件状态只有一个来源**：`GroupRuntime.localRole()`（内核的 `SeedingLedger`）。P3 的 chip 与 P4 的
激活开关都读它，`M1GroupStore` 里不再有「进程内已完成 + 盘上有文件 → 当作做种中」这类二次推测。
以前那句 `role == DOWNLOADING && onDisk -> SEEDING` 是 `SeedingLedger` 覆盖下载完成之前的临时兜底，
内核补上以后它只会让「下完但内核还没对账」的文件提早显示成做种中（两套口径），已删除。
代价是**下完到内核对账完之间最多一个巡查节拍（2 s）**里，P3 会显示「下载中 100%」，
之后转「做种中」；这是内核状态的真实时延，不是界面丢状态。

**刷新靠内核事件，不轮询**：`M1Runtime.attachUiRefresh` 把 `KernelEvents`（`op_ingested`、
`peer_announce`、`download_progress`、`file_add`、`file_completed` …）压成一次「重读数据」投到主线程；
每 2 s 的 `heartbeat` 不算事件，界面不靠它当节拍。旧实现是界面侧 700 ms 定时器，已删。

**线程纪律**：所有会碰网络的内核动作（建群注册 mDNS、加入、共享、激活、发评论）都必须离开界面线程。
这条不是风格问题——真机上界面线程写 socket 会抛 `NetworkOnMainThreadException`，
内核的 `sendQuietly` 会因此把整条对端连接关掉（本轮踩过一次：评论发不出去，对端也掉线）。

### 2.6 adb 调试入口（界面上没有开关）

| extra | 作用 |
| --- | --- |
| `huchuan.debug.network=limited\|failed\|offline` | 强制 P0 自检结果 |
| `huchuan.debug.hold=boot\|checking\|result` | 停在 P0 某阶段供截图 |
| `huchuan.debug.manual=ip:port[,ip:port]` | 加入时的手动候选（mDNS 不通时兜底） |

### 2.7 做种计数与重启续种（脚本第 11 步）

两条都在真机上验，不靠单测结论：

**(a) 下载完成 → 转做种（原来的「做种 0」缺陷）**

对端下载完本机共享的 32 MiB 后进入做种集合，重新自报一次 `peer.announce.seeds`。脚本从两端各取一次：

- `applog` 里 `peer_announce dev=<对端>` 的 `seeds=` 末次值 = 共享数 + 1（首次是它加入时的值，写进 detail 对照）；
- P6 上对端那一行同样是「做种 共享数+1」——行与行按 ui-dump 的 `center=y` 配对，
  不依赖成员顺序，也不会把「做种 0」错配到上一个成员身上。

quick 模式下对端没共享压力文件，所以这两条就是「0 → 1」；全流程（有 N 个压力文件）下是「N → N+1」。

**(b) 杀掉进程 → 冷启动续种**

`am force-stop` 是真的把进程杀掉：不跑收尾、不补写一次 `state.json`，重启续种只能靠上一份。
重启用同一条 `am start`（同样的 `huchuan.debug.manual`）冷启动，然后断言：

| 观察点 | 判据 |
| --- | --- |
| 进程真的没了 | `pidof app.huchuan` 从有到无（`restart.process_killed`） |
| 内核日志 | 最后一条 `seed_resume_done resumed=n skipped=0`（`n` ≥ 1），且 `seed_resumed` 带着本机共享的 `file_id` |
| P3 状态 | 进群后 chip 仍是「做种中」（状态读 `localRole`，不是按盘上有没有文件推测） |
| P4 开关 | 激活开关是开（ui-dump XML 里 Switch 节点 `checked="true"`，`--texts` 看不到这个属性） |
| P6 做种数 | 本机那一行的做种数与重启前一致（quick 模式下就是 1） |

冷启动后消息层不会自动重连对端（现有实现只在「加入」时建链，重连属于后续工作），
所以这两条断言只看本机：续种是纯本地判定（条目在、torrent 在、文件在、大小对得上）。
代价是 P6 上对端的平台与做种数在重启后回到「未知 / 0」——脚本因此把 P6 平台与对端做种数
放在重启之前取，重启后只验本机那一行。

## 3. 桌面对端

`app/tools/peer/DesktopPeer.java`（脚本现场 `javac`，不进 APK）：

```
DesktopPeer host data=<dir> work=<dir> [name=..] [group_name=..] [msg_port=47100] [bt_port=51413]
               [share=a,b,c] [echo=<正文>] [echo_delay_ms=..] [hold=秒]
DesktopPeer join data=<dir> work=<dir> tail8=XXXXXXXX [manual=ip:port] [download=0|1]
```

host 的固定顺序：建群 → 打印 `PEER ready tail8=.. msg_port=..` → 共享压力文件 → 等对端加入 →
等对端共享的文件 → 下载并打印 SHA-256 → 发评论 → 等对端回评论 →（`echo=` 给了就延迟补一条评论）
→ 一直做种到脚本收工。输出每行 `PEER <时间> <事件> k=v`，脚本只 grep 文本。

`echo` / `echo_delay_ms` 是给未读角标用的：手机回完评论会退回 P2，它不在群组页上的时候
必须有人发一条新评论，P2 的角标才有机会出现。延迟从「收到手机那条评论」开始算（脚本默认 25 s，
`M1_ECHO_DELAY_MS` 可改），发出后打印 `PEER echo_comment_sent body=.. delay_ms=..`。

## 4. 断言清单

| 断言 | 判定依据 |
| --- | --- |
| `apk.selfbuilt_arm64_so` | APK 内 .so 的 SHA-256 == 自编产物 |
| `desktop.invite_code_16` | 建群方邀请码 16 位 Crockford Base32 |
| `kernel.started` / `kernel.keystore_keyseed` / `kernel.multicast_lock` | logcat `huchuan/m1` |
| `join.tail8_only` | 加入方只拿到后 8 位（`join_candidates tail8=`） |
| `share.saf_copied` / `share.seeding` / `share.copy_matches_payload` | SAF 拷贝字节数、`file_seeding`、拉回私有目录副本比对 SHA-256 |
| `desktop.downloaded_32MiB` / `sha256.equal_android_desktop` | 对端 `PEER download_done bytes= sha256=` |
| `comment.pc_to_android` | 手机 P4 界面上出现 `from-pc-1`（ui-dump） |
| `comment.android_to_pc` | 对端 `PEER op_received body=from-mix-1` |
| `unread.badge_on_p2` | 手机退回 P2 后，桌面端补发的 `from-pc-2` 到达 → P2 群行出现「{n} 条新消息」，n ≥ 1（ui-dump 取文案，`p2-unread` 截图） |
| `unread.marked_on_group_open` | 点群行进 P3 → logcat `ui_mark_read scope=group ... cleared=1`（进群前那 1 条被清掉） |
| `unread.marked_on_file_open` | 点文件行进 P4 → `ui_mark_read scope=file ... file_id=<该文件>` |
| `unread.badge_cleared` | 回 P2 后界面上没有「条新消息」，且 `ui_unread ... unread=0`（`p2-read` 截图） |
| `p6.member_platform` | P6 上出现 `Android` 与 `Windows`（来自 `peer.announce.plat`） |
| `p6.seeding_counts` | P6 每个成员行都有「做种 n」（来自 `MemberRecord.seedingCount`） |
| `p6.peer_seeding_after_download` | 对端（`PEER_NAME`，默认「桌面电脑」）那一行 = 「做种 共享数+1」：它在给本机做种，不能停在共享数（quick 模式下就是 0 → 1）。行与行按 ui-dump 的 `center=y` 配对，不看成员顺序 |
| `peer_announce.seeds_after_download` | 对端自报的 `peer.announce.seeds` 末次 = 共享数+1（首次是它加入时的值，写进 detail 对照） |
| `p6.self_seeding_row` | 本机那一行的做种数 ≥ 1（重启前的基线，后面拿它比对） |
| `restart.process_killed` | `am force-stop` 前后 `pidof app.huchuan` 从有到无（确实杀掉进程，不是切后台） |
| `restart.seed_resume_done` | 冷启动后 logcat 的最后一条 `seed_resume_done` 是 `resumed≥1 skipped=0`（每轮启动都会打，0 条也是证据） |
| `restart.seed_resumed_logged` | 冷启动后 `seed_resumed` 里带着本机共享的那个 `file_id` |
| `restart.p3_chip_seeding` | 冷启动进群后 P3 上仍有「做种中」（状态读 `localRole`，不靠盘上文件推测） |
| `restart.p4_switch_on` | 冷启动进 P4，激活开关是开（ui-dump XML 里 Switch 节点 `checked="true"`；`--texts` 只吐 text/desc，看不到这个属性） |
| `restart.self_seeding_kept` | 冷启动后 P6 本机那一行的做种数与重启前一致（quick 模式下就是 1） |
| `join.mdns_discovered` | `join_candidates lan=` ≥ 1（mDNS 找到候选；端点与手动候选重复时协议记 manual） |
| `desktop.shared_stress_files` | 对端把压力文件真的共享出去了（否则第 10 步才发现 P3 没文件） |
| `stress.roundN.sha256` | 每轮下载完成后 `run-as` 拉回手机上的文件，与桌面源文件比对 SHA-256 |
| `stress.cancel_hibernate` / `cancel_stops_transfer` / `resume_after_cancel` | 第 2 轮 ≥25% 时休眠：`file_stopped` 之后没有新的 `download_progress`，重新激活后能续传完成 |
| `dht_nodes=0` | logcat 里所有 `dht_nodes=` 采样都是 0 |
| `crash_buffer.app_crash_0` | crash 缓冲区里没有 `app.huchuan` 的崩溃（缓冲区是全局的，uiautomator 的崩溃单列） |
| `no_scudo_abort` | 全量 logcat 里没有 `Scudo` / `Fatal signal` / `Abort message` |
| `no_anr` | 没有 `ANR in app.huchuan` |

## 5. 产物

```
artifacts/m1-device/
  screenshots/     关键界面截图（P1、P5 输入、P3、SAF、P4 评论、P2 角标前后、每轮完成、
                   成员页 final-members（做种计数）、 restart-p2 / restart-p3 / restart-p4 /
                   restart-members（force-stop 冷启动一路的证据））
  ui-dumps/        失败现场的界面层级
  logs/app-logcat.log        只含 huchuan/m1 事件
  logs/full-logcat.log       全量（Scudo/ANR 断言用）
  logs/crash-buffer.log      crash 缓冲区
  logs/desktop-peer.log      对端完整日志
  pulled/          从手机私有目录拉回的文件（做种副本 + 每轮下载结果）
  payload/         32 MiB 素材 + N × 压力素材（缓存，不重复生成）
```

## 6. 已知问题

1. **uiautomator 自己会崩**：MIUI 上 devkit 的 `ui-dump` 偶发 `NullPointerException`
   （`AccessibilityNodeInfoDumper`），崩的是 uiautomator 进程，不是本应用。
   所以 crash 缓冲区的总行数通常不为 0；脚本按「崩溃进程是不是 app.huchuan」判定，
   并把外来崩溃如实报成 WARN。界面操作全部带 3 次重试。
2. **文本输入走 adb**：devkit 没有 `input text` 这个动词，而边界规定不改 `tools/`，
   所以「输入后 8 位」「输入评论」两处直接用 `adb shell input text`（ASCII）。
   其余真机操作（安装 / 启动 / 点击 / 等待 / 截图 / ui-dump / logcat）全部走 devkit。
3. **mDNS 能发现，但日志里常显示 `join_via=manual`**：手机侧 `join_candidates lan=1 addr=192.168.31.218:47100`
   说明 mDNS 找到的是对的地址；同一 endpoint 又有手动候选时，按协议 §3.4「manual > lan」记成 manual。
   脚本因此单独断言 `join.mdns_discovered`（看 `lan=`），不拿 `join_via` 当唯一依据。
   本机同时有 Wi-Fi（192.168.31.218）和 VPN（172.30.236.122/32）两张网卡，
   `LanDiscovery` 会优先绑「私有 IPv4 且不是 /32 点对点」的那张，并把解析到的每个地址都出成候选。
4. **全局限速没接内核**：P7 的上下行限速只存设置；M1 内核只实现了单文件限速（协议 §4）。
5. **通知档只存不通知**：M2 才有通知通道。
6. **「做种 n」对「下完转做种」的设备偏低** —— 已修（2026-11-06）。缺陷现象、修法与现场证据见 §7-4；
   本文件对应的断言是 `p6.peer_seeding_after_download` 与 `peer_announce.seeds_after_download`。
   留这条是为了保住编号：core 的 `Seeding.kt` 注释按「§6-6」指过来。
7. **ip_filter 只放行私网**：M1 是局域网产品，跨网要按 `docs/design/nat-traversal.md`
   改成「私网 + 消息层确认的群成员公网地址」，属于 M2。

## 7. 上真机才暴露、已修掉的内核缺陷

1. **`connect_peer` 去重键漏了 fileId**（`GroupRuntime.connectSeeders`）：原来按 `dev:port` 去重，
   一文件一 torrent 的情况下，同一个做种者共享的第二个文件永远接不上（进度卡 0%）。
   改成 `fileId@dev:port`，并在「没有活 BT 连接」时按 5s 重试——libtorrent 断线不会自己重连。
2. **单文件限速单位错 8 倍**（`BtSession.setUploadLimit`）：原来 `kbps*1024/8`（按 kbit/s 算），
   而 P4 的输入框标的是 KB/s。改成 `kbps*1024`。压测里用 `throttle=1:2048` 卡 2 MB/s 验证过。
3. **界面线程碰 socket 会连带掉线**：P4 发评论原来在界面线程里走 `broadcast`，
   Android 抛 `NetworkOnMainThreadException`，内核 `sendQuietly` 捕获后把整条对端连接关掉
   （现象：评论发不出去 + 对端"掉线"）。现在建群 / 加入 / 共享 / 激活 / 评论全部 `Dispatchers.IO`。
4. **下载完成不进做种集合，对端自报 `seeds` 停在 0**（`SeedingLedger` 之前）：做种集合是一个只在
   `addFile` 里加的 `localSeeds`，`markCompleted` 不碰它，于是下完并被本机用来加速的桌面电脑
   自报 `seeds=0`，P6 上写「做种 0」（`final-members` 截图就是那个状态，日志里对端那一行
   `peer_announce ... seeds=0`）。现在「本机在做种的文件」只有 `SeedingLedger` 一份来源：
   进集合的入口是共享成功 / 下载完成 / 重启续种，出集合是休眠 / 文件缺失 / 退群，
   计数只有 `count()` 一处，`peer.announce.seeds`、P6 的做种数、P3 的 chip 全读它。
   验收断言：对端那一行与自报值都必须是「共享数 + 1」。

## 8. 压力测试怎么卡住「中途取消」

真机 Wi-Fi 跑满能到 14 MB/s，128 MiB 十几秒就完了，点不到「休眠」。
桌面端因此支持 `throttle=<索引>:<KB/s>`，默认给第 2 个共享文件限速 2 MB/s（`M1_THROTTLE_KBPS` 可改）：
128 MiB 约 64s，脚本等到 25% 再点休眠，窗口足够宽。取消后脚本断言
「`file_stopped` 之后没有任何新的 `download_progress`」，再点激活断点续传并比对 SHA-256。

## 9. 待 OPPO 回归（API 37）

OPPO PKJ110 已撤出调试环境。设备回来后按这张清单逐条补：

| # | 项 | 判据 |
| --- | --- | --- |
| 1 | 安装与冷启动 | `pm clear` + 安装后 60s 内进 P1，无 crash |
| 2 | 运行时权限差异 | ColorOS 的 `pm grant` 会抛 SecurityException，确认只用 normal 权限、无需运行时授权 |
| 3 | `ACCESS_LOCAL_NETWORK`（API 37 新权限） | targetSdk 升 37 后，局域网访问是否被拦；需在 manifest 补声明并实测 |
| 4 | 组播锁 | API 37 上 MulticastLock 行为是否变化（`multicast_lock=true` 且 mDNS 能互相发现） |
| 5 | 1440×3168 布局 | 七页截图无溢出、无遮挡，SAF 抽屉可点 |
| 6 | 内核 | `libtorrent4j.so` 在 API 37 上加载、做种、下载、取消各一轮无 Scudo |
| 7 | 文件系统 | 私有目录路径仍是 `/data/user/0/app.huchuan/...`，`run-as` 可拉取 |
| 8 | 稳定性 | 3 × 128 MiB 压力 + 一次取消，crash 缓冲区无本应用崩溃 |

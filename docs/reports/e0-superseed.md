# E0 · super-seeding（BEP 16）能不能吃掉那 14% 的重复上传

- 版本：2026-11-06 · 执行层工程 agent · 前置：`docs/reports/e0-team.md`（E0 基线 D = 1.1394 / T_all = 301.7 s）
- 一句话结论：**能力可用（per-torrent flag，非会话级布尔），但 T_all 只改善 3.1%（< 5%）→ 维持现状，不默认开启。**
  重复上传确实被消干净了（D 1.0904 → 1.0002），可省下的那 66 MB 上行没有换成时间：做种者被片级串行化拖出约 5% 的空转。
- 装置：`harness/e0-superseed.sh`（两臂编排）＋ `harness/e0-team.sh`（E0 本体，本轮只加了一个 `superseed` 开关）
- 原始读数：`artifacts/e0-superseed/readings.txt`、每轮完整输出 `artifacts/e0-superseed/round-<r>-<arm>.log`、能力探针 `artifacts/e0-superseed/probe.log`

## 1. 能力确认：libtorrent4j 2.1.0-39 到底有没有 super-seeding

分三条查，互不替代。结论是**有，但只在 per-torrent flag 上，没有会话级布尔**。

### ① settings_pack 的布尔表里没有 super_seeding（64 个全查过）

```
$ cd core/build/m1-libs && javap -classpath libtorrent4j-2.1.0-39.jar \
    'org.libtorrent4j.swig.settings_pack$bool_types' | grep -i super
（无输出，退出码 1）
```

运行时把这张表整表列一遍（`harness/src/main/java/app/huchuan/harness/SuperSeedProbe.java`）：

```
PROBE settings bool_count=64 super_seeding=absent
```

所以「在 settings_pack 里开 super_seeding」这条路**不存在**，不用再找了。根因也核过：libtorrent 2.x 的
settings_pack 里只剩一个已废弃的 `strict_super_seeding`
（`tools/build-libtorrent-android/build3/src/libtorrent4j/swig/deps/libtorrent/include/libtorrent/settings_pack.hpp:542`
`strict_super_seeding TORRENT_DEPRECATED_ENUM`），真正的开关搬到了 per-torrent flag 上。

### ② per-torrent flag 存在，且能进 `add_torrent_params`

```
$ javap -classpath libtorrent4j-2.1.0-39.jar org.libtorrent4j.TorrentFlags | grep -i super
  public static final org.libtorrent4j.swig.torrent_flags_t SUPER_SEEDING;
$ javap -classpath libtorrent4j-2.1.0-39.jar org.libtorrent4j.TorrentHandle | grep -i 'Flags'
  public org.libtorrent4j.swig.torrent_flags_t getFlags();
  public void setFlags(org.libtorrent4j.swig.torrent_flags_t, org.libtorrent4j.swig.torrent_flags_t);
  public void setFlags(org.libtorrent4j.swig.torrent_flags_t);
  public void unsetFlags(org.libtorrent4j.swig.torrent_flags_t);
$ javap -classpath libtorrent4j-2.1.0-39.jar org.libtorrent4j.swig.add_torrent_params | grep -i flag
  public void setFlags(org.libtorrent4j.swig.torrent_flags_t);
  public org.libtorrent4j.swig.torrent_flags_t getFlags();
```

jar 里的源码注释写明了语义（`tools/build-libtorrent-android/build3/src/libtorrent4j/src/main/java/org/libtorrent4j/TorrentFlags.java:111-113`）：

> sets the torrent into super seeding mode. If the torrent is not a seed, this flag has no effect.
> It has the same effect as calling `torrent_handle::super_seeding(true)` on the torrent handle immediately after adding it.

位值是 **0x100**（第 8 位）：

```
PROBE flags SUPER_SEEDING=int 256 hex=0x100 symbol=org.libtorrent4j.TorrentFlags.SUPER_SEEDING
```

### ③ 真起会话把 flag 设进去，再从 `torrent::flags()` 读回来

前两条只证明「jar 里有符号」。是不是「这份 native 库把这个特性编译进来了」要看读回：
libtorrent 源码里这一位位于 `#ifndef TORRENT_DISABLE_SUPERSEEDING` 之内，编译掉的库里读回来恒为 0。

```
$ grep -n -A3 'if (m_super_seeding)' .../libtorrent/src/torrent.cpp   # torrent::flags()
        ret |= torrent_flags::super_seeding;                        ← 在 #ifndef TORRENT_DISABLE_SUPERSEEDING 之内
$ grep -n 'TORRENT_DISABLE_SUPERSEEDING' .../libtorrent/CMakeLists.txt
763:	DESCRIPTION "Enables super seeding feature" DISABLED TORRENT_DISABLE_SUPERSEEDING   ← 默认编译进去
```

Windows 上实际加载的那份 `.dll`（`bash harness/e0-superseed.sh --probe-only`，完整输出 `artifacts/e0-superseed/probe.log`）：

```
PROBE libtorrent version=2.1.0.0 revision=24a3adf35d3e689bde700371337aad61b160bafa libtorrent4j=2.1.0-39 java=17.0.20.1
PROBE seed finished=true file_id=9117327c1e91e2c1d642dfc149c09efad5bb2861 size=4194304
PROBE readback add_time_flag_on=true              ← 加种时带 SUPER_SEEDING，校验完读回 true
PROBE readback toggle off=false on_again=true     ← 中途关掉读回 false，再开回来读回 true
RESULT=PASS
```

正式两臂里也每轮读一次（`M1 S superseed_readback`）：4 轮里 on 臂两轮都是 `value=true`、off 臂两轮都是
`value=false`，且 `reassert=n/a`（一次都没走到「校验后重设」那条兜底 —— 说明加种时带上的 flag 活过了
`torrent::files_checked()` 里那条「不是 seed 就把 super-seeding 关掉」的分支）。

**能力结论：可用。** 会话级布尔没有；per-torrent flag 有，这份 native 库确实编译了它，而且能中途开关。

## 2. 装置与开关

| 文件 | 改了什么 |
|---|---|
| `harness/e0-team.sh` | 加三个环境变量：`E0_SUPERSEED=0\|1`（透传给 S）、`E0_ART`（日志落哪）、`E0_KEEP=0`（跑完删掉 1.5 GiB 载荷）。装置口径一个字没动 |
| `harness/e0-superseed.sh` | 新的两臂编排：能力探针 → `off, on, on, off` 交替跑 → 汇总中位数与判定 |
| `harness/…/E0Team.java` | S 读 `superseed=0\|1` → 透传给 `group.addFile`；多打 `M1 S superseed` / `superseed_readback`；`team_metric` 行多 `superseed` 与 `super_readback` 两个字段 |
| `harness/…/SuperSeedProbe.java` | 能力探针（flags / settings / readback 三条证据，约 20 s） |

core 侧只加开关和读回口，没有改任何既有行为：

| 位置 | 加了什么 |
|---|---|
| `BtSession.addSeed(seeded, savePath, superSeeding)` | 加种时把 `torrent_flags::super_seeding` 按位或进 `add_torrent_params.flags`（与 `PRIVATE_FLAGS` 同一个字段）；两参重载保留原行为 |
| `BtSession.superSeeding(fileId)` / `setSuperSeeding(fileId, on)` | 读回 `torrent::flags()` 那一位 / 运行中开关 |
| `GroupRuntime.addFile(file, superSeeding)` | 透传，并在 `file_seeding` 事件里多打 `super_seeding` |
| `GroupRuntime.superSeeding(fileId)` / `setSuperSeeding(fileId, on)` | 读回口（harness 和将来的默认策略都用它，不信自报开关） |

两臂之间唯一的差别就是这一个 flag：文件大小、片大小（256 KiB × 2048 片）、做种者上行 2 MiB/s、
两个下载者同时开始、同一套「每成员一次拨号」互连逻辑，全部相同。轮次顺序 `off, on, on, off`
（每臂在时间上对称，抵消机器状态漂移），4 轮全程 12:28:37 → 12:49:01，平均 5.1 分钟一轮。

## 3. 两臂读数（512 MiB，每臂 2 轮）

| 臂 | 轮 | D | T_all | S.payload_uploaded | 每成员 numPeers | super_readback |
|---|---|---|---|---|---|---|
| off | r1 | 1.1246 | 297.9 s | 603,747,250 | 2 / 2 | false |
| off | r2 | 1.0562 | 279.5 s | 567,021,228 | 2 / 2 | false |
| on | r1 | 1.0005 | 278.8 s | 537,133,056 | 2 / 2 | true |
| on | r2 | 1.0000 | 280.9 s | 536,870,912 | 2 / 2 | true |
| **off 中位数** | | **1.0904** | **288.7 s** | | | |
| **on 中位数** | | **1.0002** | **279.9 s** | | | |

```
T_all 改善 = 3.1%   D 改善 = 8.3%
VERDICT: HOLD —— 改善 < 5%，维持现状
INVALID_ROUNDS: 0        （4 轮全部 valid=true，每成员 numPeers 都是 2/2，没有作废轮）
```

原始行（每轮 `team_metric`，`F=536870912`）：

```
off r1  team_metric D=1.1246 T_all=297.9s peers=2/2 uploaded=603747250 valid=true superseed=0 super_readback=false
on  r1  team_metric D=1.0005 T_all=278.8s peers=2/2 uploaded=537133056 valid=true superseed=1 super_readback=true
on  r2  team_metric D=1.0000 T_all=280.9s peers=2/2 uploaded=536870912 valid=true superseed=1 super_readback=true
off r2  team_metric D=1.0562 T_all=279.5s peers=2/2 uploaded=567021228 valid=true superseed=0 super_readback=false
```

### 3.1 重复上传被消干净了：D = 1.0000

| 臂 | S 多发出去的字节（− F） |
|---|---|
| off r1 | +66,876,338 B（+12.5%） |
| off r2 | +30,150,316 B（+5.6%） |
| on r1 | +262,144 B（+0.05%，恰好一片） |
| on r2 | **+0 B（一个字都没多发）** |

下载侧同样干净：on 臂两个下载者的 `payload_downloaded` 都正好 536,870,912（= F），
off 臂是 536,936,448（C1，多 64 KiB）与 539,456,434（C2，多 2.6 MB）。

### 3.2 但省下的不是时间，是「重复」

三方上行合计（S + C1 + C2）：

| 臂 | S | C1 上传 | C2 上传 | 合计 | 相对 2F |
|---|---|---|---|---|---|
| off r1 | 603,747,250 | 228,458,496 | 243,924,992 | 1,076,130,738 | +0.22% |
| off r2 | 567,021,228 | 271,794,176 | 239,271,936 | 1,078,087,340 | +0.40% |
| on r1 | 537,133,056 | 281,280,512 | 255,328,256 | 1,073,741,824 | **正好 2F** |
| on r2 | 536,870,912 | 278,396,928 | 258,211,840 | 1,073,479,680 | −0.02% |

也就是说：**super-seeding 把约 65 MB 的上行从「做种者→下载者」挪到了「下载者→下载者」，全网总上行几乎没变**
（本来也已经是理论下界 2F 附近）。瓶颈在做种者，所以这部分本该换成时间 —— 理论节省
66.9 MB ÷ 2 MiB/s ≈ 31.9 s。

实测只省了 8.9 s（288.7 → 279.9）。差在哪，用「做种者占空比」看得最清楚：

| 臂 | S 满速上传所需时间（uploaded ÷ 2 MiB/s） | T_all | 占空比 |
|---|---|---|---|
| off r1 | 287.9 s | 297.9 s | 96.6% |
| off r2 | 270.4 s | 279.5 s | 96.7% |
| on r1 | 256.1 s | 278.8 s | 91.9% |
| on r2 | 256.0 s | 280.9 s | 91.1% |

off 臂的 2 MiB/s 管道基本是打满的（96.6%），super-seeding 反而把它压到 91–92%：
做种者对每个对端一次只挂**一个**待传播片，要等对端回报「这片我有了」才换下一片
（`peer_connection.cpp:2020-2033`：`if we're super-seeding and the peer just told us that it completed the piece we're super-seeding to it, change the super-seeding piece for this peer`），
两个对端各自那一小段往返时间里做种者**空转**。省下 11% 的字节、空转多出 5%，净下来就是那 3.1%。
（占空比是从 `payload_uploaded ÷ 2 MiB/s ÷ T_all` 算出来的实测值；上面那句机制是源码口径的解释，没有逐帧验。）

### 3.3 副作用：开 super-seeding 后做种者在下游眼里不再是 seed

同一时刻（C1 到 90%）C1 看到的对端列表：

| 臂 | 对端 :53413（做种者）progressPpm | flags | `kernel.teammates()` 读数 |
|---|---|---|---|
| off r1 | 1,000,000 | 0x122433（`peer_info::seed` = 0x400 **置位**） | 1 |
| on r1 | 458,984 | 0x124033（0x400 **清零**） | 2 |

super-seeding 的实现会让做种者对外报一份「残缺 bitfield」（这正是它把下载者赶去互相要片的手段），
于是 `BtSession.peerSnapshot` 里 `seed` 的判据（`peer_info::seed` 位或 `progressPpm == 1_000_000`）
两条都不成立，**做种者被算成了一个队友**：四轮里 off 臂 `teammates_max=1`、on 臂 `teammates_max=2`。
如果哪天要默认开它，先得把「队友数」的口径改掉（不能靠对端自报完成度来排除做种者）。

## 4. 判定

按派单口径（改善 ≥ 10% 建议默认开；< 5% 维持现状）：

| 判据 | 读数 | 结论 |
|---|---|---|
| T_all 改善 | **3.1%**（288.7 → 279.9 s） | **< 5% → 维持现状** |
| D（做种者上行） | 1.0904 → 1.0002（−8.3%） | 重复确实消干净了，但没换到时间 |
| 能力 | per-torrent flag 可用，会话级布尔没有 | 想开就能开，代价见 §3.2 / §3.3 |

**结论：维持现状。** 不做显式分片（E0 已经判过），也**不默认开 super-seeding**。
它是个「零协议改动、能把做种者上行压到 1.00F」的干净开关，但本装置量到的 T_all 改善低于门槛，
而且会污染队友数口径 —— 现在没有理由把它放进默认路径。

留一句给 L1 的备选：如果哪天判据从「T_all」换成「做种者上行流量」（移动流量/套餐），
这个开关是净赚 8.3% 的，前提是先修 §3.3 的队友数口径。这条不在本轮判定里。

## 5. 风险与未决

1. **n = 2 的中位数不作数。** 基线自己两轮就差 6.2%（297.9 vs 279.5），比要测的 3.1% 还大；
   两臂区间（off 279.5–297.9 / on 278.8–280.9）是重叠的。所以「改善 < 5%」是稳的，
   但「super-seeding 会让 T_all 变慢」同样没有证据。要 3 轮以上才有统计意义（`--rounds 3`）。
2. **队友数口径被污染**（§3.3）：要做「默认开启」的话，这是比 3% 更硬的阻塞项。
3. **小文件上它是负收益**：16 MiB 冒烟里 off 12.7 s / D=1.3896、on 20.2 s / D=1.0039 —— 慢 59%。
   片级串行化在冷启动占比大的时候吃亏。真要开默认，得配一个文件大小/duration 下限。
4. **只测了 loopback + 2 个下载者**。组里 ≥3 个下载者时 super-seeding 的理论收益比 2 个时大
   （同一片只要给一个对端，其余靠接力），但本装置验不到；跨网（做种者与下载者不在同一台机器）会有
   RTT 与磁盘 IO 差异，同样没验。这两项都在 E0 装置口径之外，不当结论用。
5. **磁盘**：本装置每轮 **1.5 GiB**（做种者 512 MiB + 两个下载者各 512 MiB），峰值就是装置口径本身；
   脚本每轮结束都删（每轮日志里 `disk_after=1MiB`），跑完 `artifacts/e0-superseed/` 共 469 KB（只有日志）。
   工作区里另有约 1.26 GiB 是别的 agent 早先留下的 `artifacts/`（m1-device 554 MB / build-libtorrent 452 MB /
   m1 131 MB / m1-windows 87 MB），不是本装置产生的、没有动 —— 所以那段时间整盘占用会高于 1.5 GiB。

## 6. 复现

```bash
bash harness/e0-superseed.sh --probe-only   # 能力探针（约 1 分钟，出 flags/settings/readback 三条）
bash harness/e0-superseed.sh                # 两臂各 2 轮（512 MiB，约 21 分钟）
bash harness/e0-superseed.sh --rounds 3     # 每臂 3 轮
bash harness/e0-superseed.sh --file-mb 16   # 冒烟（D 与 F 无关，判定口径不变）
bash harness/e0-team.sh                     # E0 基线本体；默认 superseed=0，行为与本轮之前一致
```

判据与产物都在脚本里：`team_metric` 行带 `superseed` 与 `super_readback`，`readings.txt` 是读数表，
末行 `VERDICT: ENABLE | HOLD | GRAY` 是按 §4 的阈值算出来的。

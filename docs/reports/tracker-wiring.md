# 跨网发现：Android 侧 tracker 接线（M1.6）

2026-11-06 · OPPO PKJ110 `3B1F5HEADK1TXSF0`（Android 17 / API 37）· 分支 `wt/tracker-wiring`（未合并）

范围：`app/`、`docs/`、`artifacts/tracker-wiring/`。未改 `core/` `daemon/` `windows/` `tools/` `scripts/` `docs/design/` `AGENTS.md` `docs/STATE.md`。

## 1. 结论

1. **设置项两项，都落 SharedPreferences**（`data/data/app.huchuan/shared_prefs/huchuan.xml`）：
   - `tracker_base_url`：默认已部署的 Worker `https://huchuan-relay.autoai233.workers.dev`（只写在 `AppPrefs` 一处，内核仍不写死任何线上地址）；
   - `tracker_discovery`：默认 `true`。
   关掉开关时交给内核的是**空串**，不是「有地址但不启用」——内核口径是「空串 = 整个功能关闭」。
2. **启动顺序**：`M1Runtime.start()` 的内核线程上先 `applyTrackerBaseUrl()`（`kernel.setTrackerBaseUrl(...)`），再 `kernel.start()`。P0 权限门没通过时根本不走这一步（`HuchuanApplication` 推迟、`NetworkScreen` 拿到权限后调用）。真机上 `local_network_permission state=granted` 那行确实排在 `kernel_started` 之前。
3. **P7 多一行「跨网发现」**：复用「不限」那套 `Row + toggleable + Switch`（整行可点，脚本按文案点即可）。关掉写盘并记 `tracker_setting enabled=off apply=next_start`；设置与运行中的内核不一致时，开关下面补一行「下次启动生效」。
4. **日志**：`kernel_started … tracker=on|off`、`tracker_base=…`（内核侧本来就打）；宿主补 `tracker_config enabled=on|off base=… configured_base=…`、`app_kernel_ready … tracker=on|off`、`tracker_setting …`。关闭时内核照旧打 `tracker_disabled reason=base_url_empty`。
5. **真机两轮验收一次跑完 47 条断言全过**：`bash artifacts/tracker-wiring/verify-tracker-wiring.sh` → `check pass=47 fail=0`。开启态有注册尝试（本机 `*.workers.dev` 被 DNS 污染，4 s 超时失败，符合预期）、局域网 mDNS 入群与 8 MiB 传输照常；关闭态整轮 **0 条** tracker 网络事件，`kernel_started tracker=off`，P0 与 mDNS 都不受影响。

## 2. 改动点

| 文件 | 改动 |
| --- | --- |
| `app/src/main/kotlin/app/huchuan/kernel/AppPrefs.kt` | 新增 `trackerBaseUrl`（默认 `DEFAULT_TRACKER_BASE`）、`trackerDiscoveryEnabled`（默认 `true`）、`trackerBaseUrlForKernel()`（开关关掉返回空串）；两项都落 SharedPreferences |
| `app/src/main/kotlin/app/huchuan/kernel/M1Runtime.kt` | `start()` 里在 `kernel.start()` **之前**调 `applyTrackerBaseUrl()`；新增 `setTrackerDiscoveryEnabled()`（落盘 + 日志）、`trackerRestartNeeded`（设置与运行中内核是否不一致）；`app_kernel_ready` 多一个 `tracker=` 字段（on / off） |
| `app/src/main/kotlin/app/huchuan/HuchuanApp.kt` | `HuchuanState` 新增 `trackerAvailable` / `trackerDiscoveryEnabled` / `trackerRestartNeeded` / `setTrackerDiscoveryEnabled()`（走 IO 线程） |
| `app/src/main/kotlin/app/huchuan/ui/SettingsDrawer.kt` | P7 在「不限」下面加「跨网发现」开关行（与「不限」同一个组件），改完未重启时显示「下次启动生效」 |
| `app/src/main/res/values/strings.xml` | `p7_tracker` = `跨网发现`，`p7_tracker_restart` = `下次启动生效` |
| `docs/core.md` | 四处「app/ 还没接 tracker」的过期表述按代码现状改掉（§3.5 的接口口径没动） |
| `docs/reports/tracker-wiring.md` | 本文件 |
| `artifacts/tracker-wiring/verify-tracker-wiring.sh` | 可重跑的验收脚本（`--phase a` / `--phase b` / 不带参数全跑）。按本条边界放在 artifacts 下，**没有**进 `scripts/` |

## 3. 口径与取舍

1. **只能冷启动生效，这一点是内核的约束不是宿主的偷懒。** 内核在 `start()` 里按基址建 `TrackerService`，起来之后 `setTrackerBaseUrl` 会被忽略并记 `tracker_config_ignored`（`core/…/M1Kernel.kt:269`）—— 运行中的 announcer 手里握着「服务端记着的那把 passkey」，中途换基址会打断这条连续性。所以 P7 的开关改的是**下一次冷启动**，界面上明确写「下次启动生效」，不假装已生效（`M1Runtime.trackerRestartNeeded` 只在设置与运行中内核的基址不一致时才为真）。
2. **地址不在 UI 上编辑。** 产品口径是「一个页面一个功能、一个功能一个启动/关闭入口」，所以 P7 只多一行开关；地址是设置项（`tracker_base_url`，默认已部署地址），改地址这轮走落盘值（调试可 `adb shell run-as app.huchuan` 改 prefs）。要做「地址可编辑」得和「重启生效」的交互一起设计，不在本单。
3. **关闭 = 空串 = 零请求。** 宿主传给内核的是空串，内核在 `startTracker()` 里第一条分支就打 `tracker_disabled reason=base_url_empty` 并返回，连 `TrackerService` 都不建（`core/…/M1Kernel.kt:284-289`）。真机证据是「整轮 0 条 tracker 网络事件 + 0 条 `tracker_config_ignored`」，不是「我没看见请求」。
4. **关闭只关跨网那一档。** 局域网 mDNS、手动 `IP:port`、P0 权限门都不经过这条开关：关闭态冷启动后仍然 `reconnect_lan_scan entries=2`，并且桌面对端侧 `peer_bound` 从 0 变 1（手机按 mDNS 重新连上了对端）。
5. **本机域名污染的现状**（与 `docs/core.md` §3.5 的「不用线上 Worker 做测试」一致）：`*.workers.dev` 解析到 Facebook 的地址段（本轮实测 `157.240.6.35`、`31.13.94.7`；前几轮还有 `173.252.x` / `104.244.x` / `122.248.x` / `69.197.x`），连接 4 s 超时，日志是 `tracker_register_failed`，reason 形如 `Failed_to_connect_to_huchuan-relay.autoai233.workers.dev/157.240.6.35:443` 或 `failed_to_connect_to_…/31.13.94.7_(port_443)_from_/192.168.31.57_(port_38056)_after_4000ms`，passkey 只留前 4 字符。这条链路只影响「跨网」那一档，不影响局域网；失败按内核既定口径 30 s 退避重试，不抛不弹窗。

## 4. 验收

### 4.1 怎么跑

```bash
bash artifacts/tracker-wiring/verify-tracker-wiring.sh          # (a)(b)(c) 全跑，本机实测约 5 分钟
bash artifacts/tracker-wiring/verify-tracker-wiring.sh --phase a
bash artifacts/tracker-wiring/verify-tracker-wiring.sh --phase b
```

前置：OPPO `3B1F5HEADK1TXSF0` 在线且与桌面同网段；本机 JDK 17 + Android SDK；`libtorrent4j.dll` 已构建。
脚本自己做四件事：增量构建 APK 与桌面对端（`app/tools/peer/DesktopPeer.java`）、起一个桌面 host 当「局域网另一台设备」、卸载重装（把本地网络权限复位到未授权）、逐条断言并留证。

两个被脚本吃掉的设备坑（手工复验时会遇到）：

- ColorOS 对全新安装会插「安装增强防护」引导页，`adb install` 一直挂着；脚本检测到 `InstallGuideActivity` 就自动点「更多 → 开始深度扫描 → 继续安装」。
- 这台 OPPO 的 SAF 选择器是 ColorOS 文件管理器（`com.oplus.filemanager.picker.PickerActivity`），不是 AOSP Files：没有「显示根目录 / 下载 / 可用空间」，而「最近」按媒体库索引、`.bin` 根本不在里面。脚本走搜索框定位文件，并按 ui-dump 的 bounds 点结果行（该行 `clickable=false`）。

### 4.2 断言清单（47 条，0 失败）

完整清单在 `artifacts/tracker-wiring/summary.txt`，按验收项归组：

| 验收项 | 断言 | 实测值 |
| --- | --- | --- |
| (a) 权限门 | 首次启动弹授权框 → 允许 → `granted`；`local_network_permission state=granted` 行在 `kernel_started` 之前；`p0_result status=pass` | `source=prompt`，两行顺序成立 |
| (a) 基址下发 | `tracker_config enabled=on base=…`、`kernel_started … tracker=on tracker_base=…`、`tracker_service_started base=…` | 三处都是 `https://huchuan-relay.autoai233.workers.dev` |
| (a) 默认值 | P7 开关默认开（prefs 里没写过这一项）、地址默认用已部署值 | `tracker_discovery raw=空`、`tracker_base_url 未写` |
| (a) 注册尝试 | `tracker_started gid=…` + 注册（失败接受） | `tracker_register_failed`（DNS 污染，见 §3.5） |
| (a) 不崩 | 进程在、crash buffer 干净、没有 `tracker_config_ignored` | pid 在；0 条 crash |
| (a) 局域网入群 | P5 只输后 8 位 → `group_joined`，`join_candidates lan>=1` | `lan=2`，`join_via source=lan addr=192.168.31.218:47100` |
| (a) 局域网传输 | P3 SAF 共享 8 MiB → 桌面 `download_done`，两边 sha256 相同 | `8388608 B`，`fbe2fd99a38df4b3…` 一致 |
| (b) 开关落盘 | 点「跨网发现」→ `tracker_setting enabled=off`，prefs `tracker_discovery=false`，界面出现「下次启动生效」 | 全中 |
| (b) 关闭态冷启动 | `tracker_config enabled=off base=-`（同时 `configured_base` 仍是已部署地址）、`tracker_disabled reason=base_url_empty`、`kernel_started … tracker=off`、`app_kernel_ready … tracker=off` | 全中 |
| (b) 零请求 | 整轮 tracker 网络事件（service_started / started / register / announce / stopped）合计 = 0 | **0 条** |
| (c) 关闭态 P0 | `p0_result status=pass` | pass |
| (c) 关闭态 mDNS | `reconnect_lan_scan entries>=1`；重启后链路回到对端（`event=peer_bound`，phase-b 的日志流是重启后才开始抓的，不可能是上一次的残留） | `entries=2`；`peer_bound dev=FFC9VFRXST0YEY4N role=out` |
| 静态 | `applyTrackerBaseUrl()` 行号 < `kernel.start()` 行号；`DEFAULT_TRACKER_BASE` 字面量；`KEY_TRACKER_DISCOVERY, true`；P7 里引用了 `R.string.p7_tracker` | 第 166 / 167 行 |

### 4.3 关键日志（tag `huchuan/m1`）

开启态（`artifacts/tracker-wiring/logs/phase-a.log`）：

```text
event=local_network_permission state=required sdk=37 granted=false permanent=false
event=local_network_permission state=request sdk=37 auto=true
event=local_network_permission state=granted sdk=37 source=prompt
event=p0_result status=pass local_network=granted
event=tracker_config enabled=on base=https://huchuan-relay.autoai233.workers.dev configured_base=https://huchuan-relay.autoai233.workers.dev
event=tracker_service_started base=https://huchuan-relay.autoai233.workers.dev interval_hint=600
event=kernel_started … mdns=on … tracker=on tracker_base=https://huchuan-relay.autoai233.workers.dev
event=app_kernel_ready … keyseed_box=keystore-aes-gcm-1 multicast_lock=true tracker=on
event=join_candidates lan=2 total=2 tail8=7V28F05A addrs=192.168.31.218:47100,192.168.31.39:47100
event=tracker_started gid=R5Y0GV4ZGX616THV base=https://huchuan-relay.autoai233.workers.dev info_hash=1e93718a6dcade2ec300d3de85aa508528665e90
event=group_joined gid=R5Y0GV4ZGX616THV name=验收群 fp_ok=true members=2 member_pubs=2
event=join_via source=lan addr=192.168.31.218:47100
event=tracker_register_failed gid=R5Y0GV4ZGX616THV reason=Failed_to_connect_to_huchuan-relay.autoai233.workers.dev/157.240.6.35:443 passkey_masked=c69c****
event=share_import group_id=R5Y0GV4ZGX616THV name=tracker-payload-8MiB.bin bytes=8388608
event=file_seeding group_id=R5Y0GV4ZGX616THV file_id=4e7083049eb1c9d74df0287d5abfc487fc76b391 size=8388608 piece_size=65536
```

桌面对端（`artifacts/tracker-wiring/logs/peer.log`）：

```text
PEER … download_done file_id=4e70830… bytes=8388608 sha256=fbe2fd99a38df4b39386d60d8a450353502880f3cf17689a6866e27f9c377cc0
```

关闭态（`logs/phase-b-toggle.log` 点开关那一拍，`logs/phase-b.log` 冷启动后）：

```text
event=tracker_setting enabled=off apply=next_start base=https://huchuan-relay.autoai233.workers.dev
event=tracker_config enabled=off base=- configured_base=https://huchuan-relay.autoai233.workers.dev
event=tracker_disabled reason=base_url_empty
event=kernel_started … restored_groups=1 reconnect=on … tracker=off
event=app_kernel_ready … tracker=off
event=peer_bound dev=FFC9VFRXST0YEY4N gid=R5Y0GV4ZGX616THV host=192.168.31.218 role=out
event=reconnect_lan_scan entries=2 addrs=2
event=p0_result status=pass local_network=granted
```

关闭态整轮 `grep -cE 'event=tracker_(service_started|started|stopped|register|announce)'` = **0**。

### 4.4 证据文件

```text
artifacts/tracker-wiring/
  verify-tracker-wiring.sh     # 可重跑脚本（--phase a|b|all）
  summary.txt                  # 本轮 47 条断言（PASS/FAIL + 实测值）
  logs/phase-a.log             # 开启态整段内核日志
  logs/phase-b-toggle.log      # 点开关那一拍
  logs/phase-b.log             # 关闭态冷启动整段日志
  logs/peer.log                # 桌面对端日志（含 download_done / peer_bound）
  shots/                       # 9 张截图：授权框、P3 入群、SAF 搜索、P3 已共享、P7 开启/关闭/下次启动生效、重启后 P3/P7
```

截图（P7 开关与「下次启动生效」）：`shots/20261106-182755-837-a-p7-tracker-on.png`、`shots/20261106-182824-422-b-p7-after-off.png`、`shots/20261106-182850-778-b-p7-off-state.png`。

## 5. 已知限制与未决

1. **`daemon/` 与 Windows 壳还没接**：桌面对端仍是「不给基址 = 只有 mDNS + 手动」。这是本条边界外（只改 `app/`）。
2. **开关改的是下一次冷启动**（§3.1）。要让它在运行中立即生效，得内核支持「换基址」并保住 passkey 连续性，那是 `core/` 的活。
3. **tracker 的 peers 仍只喂冷启动重连**：运行中的群拿到 tracker peers 不会立刻重拨（`docs/core.md` §9 已记），本轮没有改动这一点。
4. **地址没有 UI 编辑入口**（§3.2）。默认值能覆盖当前唯一部署的 Worker；换地址要改 prefs 或等下一轮。
5. **本机网络无法验证「注册成功」**：域名被污染，只能证明「发起了注册尝试、以连接超时收场」。注册/announce 的成功路径由内核自检（`SelfTest` + 本机 MockTracker）覆盖，不在本单。

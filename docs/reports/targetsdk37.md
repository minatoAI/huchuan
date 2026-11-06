# targetSdk 37 + Android 17 本地网络权限

2026-11-06 · OPPO PKJ110 `3B1F5HEADK1TXSF0`（Android 17 / API 37）· Mi MIX 2S `5b94673a`（Android 13 / API 33）
范围：`app/`、`scripts/verify-targetsdk37.sh`、本文件。未改 `core/` `daemon/` `windows/` `tools/` `docs/design/` `AGENTS.md` `docs/STATE.md`。

## 1. 结论

1. `targetSdk` 36 → 37；`AndroidManifest` 声明 `android.permission.ACCESS_LOCAL_NETWORK`；P0 自检新增「无权限」态、系统授权弹窗、授权后自动复检、拒绝后重试→应用设置页。
2. 四条验收在真机上全部通过：`bash scripts/verify-targetsdk37.sh --phase all` → `check pass=24 fail=0`（一次跑完，含 2 条 APK 静态断言）；分次跑是 a 8/8、bc 12/12、d 8/8（每次都重跑那 2 条静态断言）。
3. **一个必须先修的时序问题**：内核在 `Application.onCreate` 里起来就开 mDNS；targetSdk 37 上未授权时起，发现层会带着错误状态活到进程结束，用户之后点「允许」也不会恢复（实测：授权后 join，`lan` 候选 0 条，报 `E_JOIN_CODE`）。改成「没授权先不起内核、P0 拿到权限那一刻再 `start()`」后，首次启动授权 → `lan=2` → `join_via=lan`。
4. 三条设备侧硬限制，验收脚本已自动绕开，手工复验时会遇到：
   - ColorOS 对**全新安装**插「安装增强防护」引导页，`adb install` 会一直挂着（不是本项目的 bug）。
   - OPPO 的 shell 没有 `CLEAR_APP_USER_DATA` / `REVOKE_RUNTIME_PERMISSIONS`：`pm clear` / `pm revoke` 都抛 `SecurityException`。「清权限」只能卸载重装，或走 设置→应用→权限管理。
   - OPPO 上 logcat 缓冲区在高负载下几十秒就被刷掉，用 `logcat -d` 做断言会随机假阴性；脚本改成「起一个 logcat 流写文件 + 断言读文件」。

## 2. 权限名核实依据

不凭印象，三处独立证据：

| 来源 | 命令 | 结果 |
| --- | --- | --- |
| SDK 常量 | `javap -constants -cp android.jar 'android.Manifest$permission'` | `public static final java.lang.String ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";` |
| 引入版本 | `grep ACCESS_LOCAL_NETWORK data/api-versions.xml` | `ACCESS_LOCAL_NETWORK" since="37.0"/>` |
| 真机权限库 | `adb -s 3B1F5HEADK1TXSF0 shell pm list permissions -g -d -f` | `+ permission:android.permission.ACCESS_LOCAL_NETWORK` / `protectionLevel:dangerous` |
| 平台文档 | developer.android.com/privacy-and-security/local-network-permission | targetSdk ≥ 37 强制；Path B：清单声明 + 运行时请求 |

`android.jar` 取自 `E:/agentHome/android-sdk/platforms/android-37.0/`（`build.prop`: `ro.build.version.sdk=37`，`release=17`）。

补充事实：

- `protectionLevel` 是 `dangerous`，即标准运行时权限，用 `checkSelfPermission` / `requestPermissions` 一套 API；系统弹窗标题是「允许"互传"访问附近的设备?」，属「附近的设备」（`NEARBY_DEVICES`）权限组。注意：这台 OPPO 上 `pm list permissions` 把该权限归到 `android.permission-group.UNDEFINED`，只有弹窗文案显示为附近的设备——两者不矛盾，但**不要**用 `pm list permissions` 的组名去判断它是不是运行时权限。
- Mi MIX 2S（API 33）的 `pm list permissions` 里没有这个权限，系统不认识——声明它无害，应用侧也只在 API ≥ 37 请求。
- `Build.VERSION_CODES.CINNAMON_BUN = 37`（javap 自同一个 android.jar 核实）。

## 3. 改动点

| 文件 | 改动 |
| --- | --- |
| `app/build.gradle.kts` | `targetSdk = 36` → `37` |
| `app/src/main/AndroidManifest.xml` | 新增 `<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />`，注释写明名字来源 |
| `app/src/main/kotlin/app/huchuan/kernel/LocalNetworkPermission.kt`（新） | 权限名常量（直接引 `Manifest.permission.ACCESS_LOCAL_NETWORK`）、`enforced`（API ≥ 37）、`isGranted`、`shouldShowRationale`、`openAppSettings`、结构化日志 |
| `app/src/main/kotlin/app/huchuan/ui/NetworkScreen.kt` | P0 新增 `NO_PERMISSION` 态：先卡权限再探网络；未授权 → 显示「本地网络未授权」+ 只给「重试」并自动拉起系统授权框；授权后自动复检；拒绝后重试先再请求，系统不再弹窗则跳应用详情页；`ON_RESUME` 检测授权状态变化后自动复检 |
| `app/src/main/kotlin/app/huchuan/HuchuanApplication.kt` | API 37 且未授权时**推迟** `M1Runtime.start()`，由 P0 拿到权限时再起（见 §5.1） |
| `app/src/main/res/values/strings.xml` | `p0_no_permission` = `本地网络未授权`（spec §3 P0 状态表原文） |
| `scripts/verify-targetsdk37.sh`（新） | 四条验收的可重跑脚本，`--phase a\|bc\|d\|all` |

P0 状态机（新增的那一条）：

```text
启动中 → 正在检测网络 → [权限未授权] → 本地网络未授权（停留，自动拉起系统授权框）
                                     ├─ 用户允许 → 自动复检 → 可互传 → 进入
                                     └─ 用户拒绝 → 本地网络未授权 + 仅「重试」
                                          重试：还能弹 → 再拉系统框
                                                系统不再弹 → ACTION_APPLICATION_DETAILS_SETTINGS
```

日志事件（tag `huchuan/m1`，与内核同一前缀，脚本直接 grep）：

```text
event=local_network_permission state=required|request|granted|denied|revoked|settings_requested sdk=37 [source=prompt|resume] [permanent=..]
event=p0_result status=no_permission|pass|degraded|failed|offline local_network=granted|not_required
event=app_kernel_deferred reason=local_network_denied
```

## 4. 四条验收的实测证据

复跑：`bash scripts/verify-targetsdk37.sh --phase a` / `--phase bc` / `--phase d`，或 `--phase all` 一次跑完。
一次性全跑（`--phase all`）实测 24 条断言 0 失败、耗时约 5 分钟：

```text
$ bash scripts/verify-targetsdk37.sh --phase all
...
=== 汇总 ===
check pass=24 fail=0
PASS
```

证据目录 `artifacts/targetsdk37/`（`run-a.log` `run-bc.log` `run-d.log` `run-all.log`、`logs/*.log`、`summary-*.txt`），截图在 `artifacts/screenshots/<serial>/`。
下面四条分别引用分次运行的日志（每条断言都能在对应 `run-*.log` 里找到原文）。

### a) 首次启动请求权限 → 授权 → mDNS 发现桌面端

```text
$ bash scripts/verify-targetsdk37.sh --phase a
INFO  peer tail8=WNP1J9VP pid=18740
  安装被 ColorOS「安装增强防护」拦住，自动点掉（第 2 次探测）
  已点「继续安装」
CHECK PASS a.initial_denied (        android.permission.ACCESS_LOCAL_NETWORK: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED])
CHECK PASS a.prompt_on_first_launch (GrantPermissionsActivity)
CHECK PASS a.granted_by_prompt (source=prompt)
CHECK PASS a.p0_pass_after_grant
CHECK PASS a.mdns_lan_candidates (lan=2 addrs=192.168.31.39:47100,192.168.31.218:47100)
CHECK PASS a.joined_via_lan (join_via=lan addr=192.168.31.218:47100)
check pass=8 fail=0
PASS
```

对应 logcat（`artifacts/targetsdk37/logs/oppo-a.log`）：

```text
16:59:13.362 event=app_kernel_deferred reason=local_network_denied
16:59:15.104 event=local_network_permission state=required sdk=37 granted=false permanent=false
16:59:15.104 event=p0_result status=no_permission
16:59:15.104 event=local_network_permission state=request sdk=37 auto=true
16:59:20.671 event=local_network_permission state=granted sdk=37 source=prompt
16:59:22.096 event=p0_result status=pass local_network=granted
16:59:22.198 event=kernel_started ... mdns=on ...
16:59:39.941 event=join_candidates lan=2 total=2 tail8=WNP1J9VP addrs=192.168.31.39:47100,192.168.31.218:47100
16:59:40.174 event=group_joined gid=4WZ86TBZ5HX870T5 name=验收群 fp=JPM2E1N6 fp_ok=true members=2
16:59:40.187 event=join_via source=lan addr=192.168.31.218:47100
```

截图：`artifacts/screenshots/3B1F5HEADK1TXSF0/20261106-165913-439-a-permission-dialog-*.png`（系统授权框压在 P0 上）、`…-165937-056-a-p3-group-*.png`（加入后的 P3）。
说明：`lan=2` 里的 `192.168.31.39` 是另一台机器（Mi MIX 2S）上残留的群通告，码不对会 `E_JOIN_CODE` 自动跳过；最终 `join_via=lan` 落到桌面端 `192.168.31.218:47100`。整条加入过程**没有**传 `huchuan.debug.manual`，只能靠 mDNS。

### b) 拒绝后停在无权限态，重试能再次拉起或跳设置

```text
$ bash scripts/verify-targetsdk37.sh --phase bc
CHECK PASS b.p0_no_permission_state (本地网络未授权)
CHECK PASS b.retry_visible
CHECK PASS b.retry_reprompts (第二次系统授权框)
CHECK PASS b.still_no_permission
CHECK PASS b.retry_routes_to_settings (com.android.settings/.applications.InstalledAppDetails)
```

```text
17:05:11.671 state=required granted=false permanent=false
17:05:11.671 state=request auto=true
17:05:15.354 state=denied source=prompt permanent=false      ← 第一次拒绝：系统还能再弹
17:05:27.781 state=required granted=false permanent=false      ← 点「重试」
17:05:27.781 state=request auto=true
17:05:34.556 state=denied source=prompt permanent=true        ← 第二次拒绝：不再弹
17:05:43.128 state=settings_requested sdk=37                  ← 再点「重试」→ 应用详情页
```

截图：`…-170519-386-b-p0-no-permission-*.png`（只剩「本地网络未授权」+「重试」）、`…-170543-446-b-settings-page-*.png`（系统应用详情页）。

### c) 系统设置里授权后回到应用，自动复检通过并可用

```text
CHECK PASS c.granted_from_settings
CHECK PASS c.auto_recheck_on_resume (source=resume)
CHECK PASS c.p0_pass_after_resume
CHECK PASS c.entered_p1
CHECK PASS c.join_after_settings_grant (lan=2 join_via=lan)
check pass=12 fail=0
PASS
```

```text
17:06:11.396 state=granted sdk=37 source=resume
17:06:12.826 event=p0_result status=pass local_network=granted
```

截图：`…-170612-211-c-p1-after-auto-recheck-*.png`（回到 P1）、`…-170627-656-c-p3-group-*.png`（P3「验收群 成员（2 人）」）。
这一条不只断言 P0 放行，还断言了授权后**真的能靠 mDNS 加入群组**——因为内核是在拿到权限那一刻才起的，发现层要能正常工作才算「回来能用」。

### d) Mi MIX 2S（API 33）不弹权限、功能照常

```text
CHECK PASS d.mix_api33 (sdk=33)
CHECK PASS d.mix_no_local_network_perm (系统不认识该权限)
CHECK PASS d.no_permission_prompt
CHECK PASS d.p0_pass_no_gate
CHECK PASS d.entered_p1
CHECK PASS d.join_works (lan=1)
check pass=8 fail=0
PASS
```

```text
17:02:26.427 event=p0_result status=pass local_network=not_required
17:03:43.579 event=join_candidates lan=1 total=1 tail8=0HEKTCTT addrs=192.168.31.218:47100
17:03:43.703 event=group_joined gid=5PD4D7WXV0A0YBCB name=验收群 fp=TB9927RR fp_ok=true members=2
```

截图：`artifacts/screenshots/5b94673a/20261106-170323-715-d-p1-*.png`、`…-170340-215-d-p3-group-*.png`。

## 5. 实现细节与踩过的坑

### 5.1 内核必须等权限到位再启动（否则 mDNS 永久残废）

`HuchuanApplication.onCreate` 原来无条件 `runtime.start()`，而 `M1Runtime.start()` 里第一件事就是 `kernel.start()` → `lanDiscovery.start()`（jmDNS 组播套接字）。首次启动时用户还没点「允许」，这一步在 API 37 上会被本地网络保护拦住，`LanDiscovery` 把错误记在 `lastError` 里就一直那样了——之后授权也不会重试。

实测（未修版本，首次启动授权后立刻加入）：

```text
event=join_candidates lan=0 total=0 tail8=6XTDRKK3
event=join_failed reason=E_JOIN_CODE
```

同一台机器、权限已在系统设置里授过之后再冷启动，同样的操作：

```text
event=join_candidates lan=2 total=2 addrs=192.168.31.39:47100,192.168.31.218:47100
event=join_via source=lan addr=192.168.31.218:47100
```

修法（只在 `app/` 内）：未授权时不起内核，P0 在权限判定通过的那一行调 `state.runtime?.start()`（`start()` 自带幂等）。API < 37 或已授权时行为完全不变。

### 5.2 「系统不再弹窗」的判定

用 `ActivityCompat.shouldShowRequestPermissionRationale` + 「是否已经请求过」两个条件：拒绝回调里 `rationale == false` 才置 `permanentDenial`。第一次拒绝时 rationale 仍是 true，所以「重试」会再弹一次；第二次拒绝后才把「重试」改成跳设置页。平台文档里的 permission request reset counter 会让这个判定偶尔多给一次机会，属预期。

### 5.3 ColorOS「安装增强防护」

全新安装（先卸载过）时，`adb install` 会停在 `com.oplus.appdetail/InstallGuideActivity`：必须点右上「更多 → 开始深度扫描」，扫完（10–20 s）点「继续安装」，否则 adb 端一直挂着直到超时。`install_apk()` 已经自动化；判据用 activity 名 `InstallGuideActivity`，「更多」按钮没有文字，按 `resource-id` 取 bounds 中心点击。

### 5.4 logcat 断言不可靠

OPPO 上安装 + `uiautomator dump` 期间日志量很大，`logcat -d` 拿到的往往只剩最近一两分钟的心跳行，导致「明明打过 `p0_result` 却断言失败」。脚本改成：清日志后起一个 `adb logcat ... | grep --line-buffered > file` 的流，断言只读这个文件。另外 adb 日志流里会混 NUL 字节，`grep` 会把它当二进制跳过（输出 `Binary file … matches`），读之前统一 `tr -d '\000\r'`。

### 5.5 桌面端对端（证据里的另一端）

`app/tools/peer/DesktopPeer.java`（不在 APK 内），以 `host` 角色起在 PC 上建群、打印 `tail8`。脚本读的是 `PEER ready role=host tail8=…` 那一行——早先读「日志里第一个 `tail8=`」会读到上一轮残留的值，拿旧码去 join 必然 `E_JOIN_CODE`。

## 6. 回退方式

改动集中在 5 个文件 + 1 个新文件，全部在 `app/` 和 `scripts/` 下，与内核无关：

```bash
git checkout -- app/build.gradle.kts app/src/main/AndroidManifest.xml \
  app/src/main/kotlin/app/huchuan/HuchuanApplication.kt \
  app/src/main/kotlin/app/huchuan/ui/NetworkScreen.kt \
  app/src/main/res/values/strings.xml
rm -f app/src/main/kotlin/app/huchuan/kernel/LocalNetworkPermission.kt scripts/verify-targetsdk37.sh
./gradlew :app:assembleDebug
```

只想降 targetSdk、保留代码也可以：把 `targetSdk` 改回 36 即可——`LocalNetworkPermission.enforced` 在 API < 37 时为 false，权限门、弹窗、日志全部不生效，内核回到 `Application` 里启动，与改动前一致。清单里多声明一个权限对 targetSdk 36 无影响（系统仍按 legacy 隐式授权放行）。

回退后需要重新安装（不能只覆盖安装）：旧的授权状态留在系统里，`pm clear` 在 OPPO 上不可用。

## 7. 手工复验前把设备恢复到「没授权」

脚本跑完会把 OPPO 留在「已授权 + 已加入验收群」的状态。要复现「清权限 → 启动 → 拒绝」：

```bash
# 唯一可靠的办法：卸载重装（ColorOS 上 pm clear / pm revoke 都抛 SecurityException）
adb -s 3B1F5HEADK1TXSF0 uninstall app.huchuan
bash scripts/verify-targetsdk37.sh --phase a     # 脚本自己装，且不带 -g

# 或者不卸载：设置 → 应用 → 互传 → 权限管理 → 附近的设备 → 不允许，然后冷启动 App
adb -s 3B1F5HEADK1TXSF0 shell am force-stop app.huchuan
```

注意 `tools/devkit` 的 `install` 默认带 `-g`（安装即授予全部运行时权限），手工装想看到授权弹窗必须加 `--no-grant`。

## 8. 未做与风险

1. **`AGENTS.md` §9 的基线仍写 `targetSdk 36`**，本次不改该文件（在边界外），需要 L1 决定何时同步成 37。
2. `docs/design/product-spec.md` §3 P0 状态表里「无权限」态只写了「停留，拉起系统授权」，没写「拒绝后只给重试」。实现按任务要求补了「重试」（复用 `common_retry` 文案），规格文档没有同步（在边界外）。
3. 没覆盖：用户在 P0 停留期间切到别的应用再回来（`ON_RESUME` 逻辑同样生效，但没做专项用例）；权限被系统策略（如家长控制、企业策略）强制拒绝的场景。
4. `c.join_after_settings_grant` 依赖局域网里有可发现的桌面端；没有对端时这条会失败，属环境问题不是回归。
5. 只在 OPPO PKJ110（ColorOS / API 37）与 Mi MIX 2S（MIUI / API 33）上验过，别的 OEM 的权限页文案可能不同（脚本按「权限管理 / 附近的设备 / 允许」三段文字走）。

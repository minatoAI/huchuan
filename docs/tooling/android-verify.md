# Android 真机自动化验证链路

- 日期：2026-11-06
- 适用设备：Mi MIX 2S（Android 13 / API 33，720x1280 override）、OPPO PKJ110（Android 17 / API 37，1440x3168）
- 工具：`tools/devkit/`（Node，零第三方依赖）
- 产物：`artifacts/`（截图、UI XML、logcat、录屏、冒烟报告）
- 规范依据：根目录 `AGENTS.md` 第 6 节（调试自动化优先、真机是唯一事实）

## 0. 结论

1. 黑盒链路（adb + `uiautomator dump` + `input`）与白盒链路（Espresso / Compose UI Test）不是二选一：
   前者负责安装、启动、系统 UI、权限弹窗、OEM 差异和最终验收；后者负责应用内状态、Compose 语义和快速回归。
2. `tools/devkit` 已实现 `devices / install / launch / screenshot / ui-dump / tap-text / wait-text /
   input / logcat-dump / clear-logs`，另加 `key / swipe / record / doctor`。输出支持行格式与 `--json`，
   退出码区分断言失败、用法错误、设备错误、本地文件错误。`launch` 的 `--extra k=v` 与 `input text`
   已经覆盖原先必须绕过 devkit 直调 adb 的两处（`am start --es`、`input text`），见 3.7。
3. 两台真机均已实测通过：`devices`、`screenshot`、`ui-dump`、`tap-text`、`wait-text` 和
   `examples/system-ui-smoke.mjs`（pass=2 fail=0）。
4. 真机最大的坑不是 adb，而是 `uiautomator dump` 的"idle"：MIUI 的"关于手机"页会稳定返回
   `ERROR: could not get idle state.`，且退出码仍为 0。devkit 每次先删远端文件、校验 XML，
   失败重试后明确报错，绝不返回上一次的旧 XML。
5. Android 17（API 37）起，`targetSdk >= 37` 的应用访问局域网需要运行时权限
   `android.permission.ACCESS_LOCAL_NETWORK`（属 `NEARBY_DEVICES` 权限组）。当前基线
   `targetSdk 36` 在 Android 17 上仍是 legacy 隐式授权；一旦升到 37，BT 局域网直连的数据面
   和 mDNS 发现都会被默认阻断，必须在应用内请求该权限，并把权限状态纳入自动化断言。

## 1. 验证链路分层

| 层 | 工具 | 能回答的问题 | 不能回答的问题 |
| --- | --- | --- | --- |
| 设备控制 | adb（本机未进 PATH） | 设备在线、安装、启动、按键、截图、录屏、logcat | 页面语义 |
| UI 黑盒 | uiautomator dump + input | 当前窗口文本/控件、跨应用流程、权限弹窗、OEM 页面 | 动画中间态、内部状态、非无障碍节点 |
| UI 白盒 | Espresso / Compose UI Test | 语义树、状态、点击回调、同步等待、单元级断言 | 系统 UI、跨进程权限弹窗、真机 OEM 差异 |
| 网络 | logcat + 端口探测/抓包 | 监听、连接、传输事件、错误码 | 界面是否误导 |
| 证据 | artifacts/ + manifest.jsonl | 谁在什么时间、哪台设备、跑了什么、得到什么 | 无 |

原则：**每一层都要有可重跑的入口**；手工点击/截图只用于第一次探索，不进入验收。

## 2. adb + uiautomator dump 与 Espresso / Compose UI Test 的取舍

| 维度 | adb + uiautomator dump | Espresso / Compose UI Test |
| --- | --- | --- |
| 依赖 | 只要 adb + 已安装 APK；不需要源码/构建 | 需要源码、instrumentation、Gradle、测试 APK |
| 启动成本 | 一条命令，约 1-3 秒/次 dump | 需要先编译测试 APK，安装后跑 instrumentation |
| 等待模型 | 轮询 `wait-text`（最终一致，但会慢） | IdlingResource / Compose 测试时钟，同步精确 |
| 断言粒度 | 文本、content-desc、bounds、clickable | 语义节点、状态、回调、数据库/网络假实现 |
| 跨应用 | 可以（设置、权限弹窗、分享面板） | 默认不能跨进程 |
| 系统权限弹窗 | 可以 dump/点击 | 不能可靠断言（属于系统 UI） |
| OEM/机型差异 | 直接暴露（MIUI/ColorOS 的坑） | 被测试框架隔离，覆盖不到 |
| 动画/不稳定页面 | 弱：不 idle 直接失败（见 3.3） | 强：可控制时钟、可等待语义 |
| 失败定位 | 需要看 XML/截图/logcat | 堆栈直接指向语义节点 |
| CI 成本 | 低（Node + adb + 两台真机） | 中（Gradle、设备农场或本地真机） |
| 适合 | 安装→启动→操作→断言→留证的端到端验收 | 业务逻辑、Compose 状态、回归测试 |

结论：

- **功能开发的每次提交**：优先补 Compose UI Test（快、稳定、定位准）；如果工程还没接 Gradle，
  先用 devkit 做真机冒烟，别为了测试先造一套构建。
- **每个里程碑/发版前**：必须在两台真机上跑 devkit 端到端（安装、权限、启动、核心操作、日志断言、截图）。
- **权限弹窗、系统设置、OEM 差异**：只能靠 devkit；Espresso/Compose 覆盖不到。
- **不要把 uiautomator 当唯一断言源**：它拿到的是无障碍树，不是业务状态；UI 文案"在线"不等于
  网络真的连通（见 8.8 的旧 APK 观察）。

## 3. 真机稳定性技巧

### 3.1 等待策略

禁止 `sleep 5` 这类固定等待。可用策略按优先级：

1. **命令自带等待**：`am start -W` 等启动完成；devkit 的 `launch` 还会轮询 `mCurrentFocus` 确认目标包在前台。
2. **断言式等待**：`wait-text` 轮询 UI 直到文本出现，默认 15 秒、间隔 400ms；超时退出 1。
3. **失败重试**：`uiautomator dump` 失败重试 3 次（每次先删远端文件）；仍失败就报错，不返回旧数据。
4. **固定 sleep 只用于"页面已稳定后的极短沉降"**：例如点击后等 200-300ms 再截图，不能用于判断业务完成。

建议超时：

| 场景 | 超时 |
| --- | --- |
| 同页文本出现（点击、弹窗） | 5-8 秒 |
| 跨页跳转/冷启动 | 10-15 秒 |
| 网络握手/传输状态 | 20-30 秒（按业务定） |
| logcat 异步事件 | 用轮询日志，不用固定 sleep |

失败时必须同时留：截图、UI XML、logcat、命令退出码。devkit 的 `--json` 输出包含 `code/kind/error`，
脚本据此区分"断言失败"和"设备掉线"。

### 3.2 键盘遮挡

现象：输入框聚焦后软键盘弹出，`uiautomator dump` 的窗口层级可能不含 IME，但截图含 IME；
坐标点击可能点在键盘上；断言"按钮可见"时实际被键盘挡住。

处理：

- 输入完成后先收起键盘再断言/截图：
  `adb -s $SERIAL shell input keyevent KEYCODE_BACK`（devkit：`key KEYCODE_BACK`）
- 判断键盘是否显示：
  `adb -s $SERIAL shell dumpsys input_method | grep mInputShown`
- Espresso：`closeSoftKeyboard()`；Compose：`performImeAction()` 或先点空白区。
- 文本输入：devkit `input text "hello world"`（引号由 devkit 加，空格直接写；只支持 ASCII）。
  中文等非 ASCII 需要 ADBKeyboard，细节与实测见 3.7。
- 截图取证前统一 `key KEYCODE_BACK`，避免键盘进入证据图。

### 3.3 动画/抖动

- 关闭系统动画（可恢复）：
  `adb -s $SERIAL shell settings put global window_animation_scale 0`
  `adb -s $SERIAL shell settings put global transition_animation_scale 0`
  `adb -s $SERIAL shell settings put global animator_duration_scale 0`
  恢复：把三个值改回 `1.0`（本机 MIX 2S 已恢复）。
- **关闭动画不是万能的**：实测 Mi MIX 2S 的"设置 → 关于手机"页，即使三个 scale 全为 0，
  `uiautomator dump` 仍稳定报 `could not get idle state`；而同一台机器的设置首页、电池页、
  系统页都正常。说明该页有持续更新的节点/动画，uiautomator 的 idle 判定无法满足。
- 对这类页面：
  1. 黑盒断言避开该页（本项目的冒烟脚本就避开了"关于手机"）；
  2. 必须验证该页时，用 Compose/Espresso 的同步等待，或用 `wait-text` 配合可 idle 的子页面；
  3. 不要把 `dump` 失败当成"页面没变"，更不要复用旧 XML。
- 截图/录屏也会抓到转场中间态：先 `wait-text` 目标文本，再等 200-300ms 截图；
  录屏用于证明"过程"，静态截图用于证明"终态"。

### 3.4 多设备：始终显式 `-s`

- `adb` 默认只对一台设备操作；两台在线时很多命令会直接报 `more than one device`。
- devkit 规则：
  - `-s SERIAL` 可重复或逗号分隔；
  - 未指定且只有一台在线：自动选它；
  - 未指定且多台在线：`screenshot/ui-dump/logcat-dump/clear-logs` 默认跑全部在线设备；
    其它会改变状态的命令（install/launch/tap/wait/key/swipe/record）退出 2，要求显式选择。
- 设备序列号从 `devkit devices` 取；不要硬编码"第一台"。
- 需要并行时，不同设备可以并行；同一设备的 `uiautomator dump` 不要并发，否则会互相覆盖远端文件
  （devkit 用进程号+时间戳区分远端路径，仍建议顺序执行）。
- 可设 `ANDROID_SERIAL` 让裸 `adb` 命令默认落到某台设备，但脚本里仍建议显式 `-s`。

### 3.5 设备状态与 OEM 设置

- 唤醒/解锁：`adb shell input keyevent KEYCODE_WAKEUP`、`adb shell wm dismiss-keyguard`；
  有锁屏密码时自动化无法解锁，需要测试机保持常亮/无锁。
- 常亮：`adb shell svc power stayon true`；息屏时间：`settings put system screen_off_timeout 2147483647`。
- 保持 USB 调试授权；MIUI 还需"USB 调试（安全设置）"和"USB 安装"；ColorOS 有"禁止权限监控"等开关。
- 电池优化/自启动：长传输测试前把被测应用加入"不优化/允许后台"，否则 FGS 被系统限制会影响结论。
- 断连恢复：`adb reconnect`、`adb kill-server && adb start-server`；OPPO 间歇在线，脚本应先 `devices` 再决定跑哪些机器。

### 3.6 本机实测到的 OEM 差异

| 现象 | 设备 | 结论 |
| --- | --- | --- |
| `uiautomator dump` 返回 `could not get idle state` | MIX 2S / 关于手机页 | 避开该页；黑盒 dump 不是万能 |
| `pm grant/revoke` 抛 `SecurityException`（shell 无 `GRANT_RUNTIME_PERMISSIONS`） | OPPO ColorOS / Android 17 | 用 `adb install -g` 或 UI 点"允许" |
| `pm grant/revoke` 正常 | MIX 2S / Android 13 | 标准命令可用 |
| MSYS 把 `/sdcard` 改写成 `C:/Program Files/Git/sdcard` | 本机 bash | 调 adb 不要经过 MSYS shell；Node spawn 直调 |
| 系统 UI 是中文，但 `ro.product.locale` 为 en-US | MIX 2S | 断言按实际界面语言，不按 locale |
| 设置界面行数超出屏幕 | 两台 | 只 dump 可见节点；点击前先确认候选在 `texts` 里 |
| 截图尺寸与物理分辨率不同（720x1280 vs 1080x2160） | MIX 2S（override） | 坐标以 dump 的 bounds 为准，不要按物理分辨率算 |

#### MIUI 文件选择器（SAF）的三个坑

2026-11-08 在 MIX 2S（Android 13 / API 33）的 DocumentsUI 上实测，证据见
`artifacts/pre-release-qa/steps-a.md`：

1. **「显示根目录」是开关，不是一次性按钮。** 抽屉已经开着时再点一次会把它关掉，第二次点击落在遮罩上，
   后面的「下载」就点空。正确顺序是先看抽屉标志「可用空间」是否出现，再点「下载」；
   不要用「显示根目录」是否存在来判断抽屉状态。
2. **在文件列表里做竖向 swipe 会退回「近期的文件」。** 刚 push 到 `/sdcard/Download` 的文件会从当前视图消失，
   后续按名字找不到。翻列表用 `tap` 或返回键，不要用竖向滑动。
3. **一次 `ui-dump` 要 2–3 s，探测超时必须 > 3000 ms。** `wait-text` 的 timeout 设 3000 或更小时，
   每次都在 dump 完成前超时，看起来像「界面没有这个文本」。脚本里按场景用 3000/8000/15000 分级，
   并给抽屉动画留 1–2 s 沉降。

### 3.7 文本输入与调试 extra（`input text` / `launch --extra`）

这两个动词补齐了以前必须绕过 devkit 直调 adb 的两处：`adb shell input text` 和
`am start -n ... --es k v`。完整参数见 `tools/devkit/README.md`，这里只记动词、限制与实测结论。

| 动词 | 干什么 | 限制（实测） |
| --- | --- | --- |
| `input text <text>` | 往当前焦点输入框打字；`--verify` 打完 dump 断言文本真的落进输入框 | 只支持 ASCII；中文/任意非 ASCII 在 Android 13 上直接抛 NPE，必须换 ADBKeyboard（本机未装、未实测）。密码框回显 `●`，不能用 `--verify`。输入框有字数上限时会报"只落进前 N 个字符" |
| `launch <pkg> --extra k=v`（可重复，`--es` 同义） | 启动时透传字符串 intent extra | 只有字符串 extra；给了 extra 就自动先 force-stop（前台 task 上 `am start` 只 `LaunchState: HOT`，`onCreate` 不跑，extra 会被吞掉）；key 限 `[A-Za-z0-9_.-]`，value 不能以 `-` 开头 |

用法（Mi MIX 2S / 5b94673a 实测）：

```bash
S=5b94673a
# 1. 点进输入框，再打字；--verify 会 dump 一次并断言包含
node tools/devkit/devkit.mjs tap-text "Mi MIX 2S" -s $S
node tools/devkit/devkit.mjs input text "mix-2s-check-42" --verify -s $S
node tools/devkit/devkit.mjs ui-dump --texts -s $S | grep "mix-2s-check-42"

# 2. 带 extra 启动，验证 P0 自检的失败态（调试入口只走 intent extra，界面没有开关）
node tools/devkit/devkit.mjs launch app.huchuan --extra huchuan.debug.network=failed -s $S
node tools/devkit/devkit.mjs wait-text "检测失败" --timeout 15000 -s $S
node tools/devkit/devkit.mjs screenshot --name p0-failed -s $S
```

实测输出（2026-11-06，Mi MIX 2S / Android 13）：

```text
$ devkit input text "mix-2s-check-42" --verify -s 5b94673a
OK input serial=5b94673a chars=15 method=input-text text=mix-2s-check-42 verified=true field=mix-2s-check-42 elapsedMs=2719

$ devkit input text "中文测试" -s 5b94673a            # 退出 3，原因可读
ERR input serial=5b94673a code=3 kind=device error="input text 只能注入 ASCII；文本里的 中文测试 需要 ADBKeyboard"
HINT 装 ADBKeyboard（com.android.adbkeyboard）并设为默认输入法；devkit 会自动改用 ADB_INPUT_B64 广播

$ devkit launch app.huchuan --extra huchuan.debug.network=failed -s 5b94673a
OK launch serial=5b94673a package=app.huchuan activity=app.huchuan.MainActivity component=app.huchuan/.MainActivity method=am extras=huchuan.debug.network=failed fresh=true elapsedMs=2050
```

四条真机结论：

1. **空格和特殊字符不需要转义**：`input text` 收到的实参原样注入，`hello world`、`It's 100% & $HOME "q" \x`
   都能原样打出并 dump 回来（devkit 负责按设备 shell 规则加引号）。`%s` 会被 `input text` 当空格，
   devkit 把字面量 `%s` 拆成 `%`+`s` 两段发，保住原样。
2. **中文不是"打得乱码"，是直接失败**：继续传非 ASCII，`input text` 抛
   `java.lang.NullPointerException: Attempt to get length of null array`（`InputShellCommand.sendText`），
   adb 退出 255。所以 devkit 在发送前拦截，退出 3，并说明需要 ADBKeyboard。
   唯一已知通路是 ADBKeyboard 作为默认输入法接收 `ADB_INPUT_B64` 广播；本机没有装，脚本里的这条分支
   还没在真机上跑过，需要中文断言时必须先补这一步实验。
3. **没有焦点时 adb 仍返回 0**：命令"成功"但一个字也没进去。CI 里必须加 `--verify`，
   否则会出现"脚本绿了、字段是空的"。`--verify` 用"包含"判断（Compose 输入框在 XML 里会多出尾部空格），
   截断时明确报"只落进前 N 个字符"。
4. **extra 必须走 `am` 且要冷启动**：`monkey` 不能带 extra；进程在前台时 `am start` 只输出
   `Warning: Activity not started, its current task has been brought to the front` / `LaunchState: HOT`，
   `MainActivity.onCreate` 不重跑，新 extra 被丢弃。devkit 因此对带 `--extra` 的启动一律先 `am force-stop`。
   实测同一个 `huchuan.debug.network` 三个取值分别落到 `检测失败`（failed）、`无网络连接`（offline）、
   `仅局域网可用`（limited），不带 extra 时 P0 正常放行到 P1。

## 4. 截图与录屏取证

### 4.1 截图

推荐（二进制安全，不会把 CRLF 写进 PNG）：

```bash
adb -s $SERIAL exec-out screencap -p > shot.png
```

等价但多一步：

```bash
adb -s $SERIAL shell screencap -p /sdcard/shot.png
adb -s $SERIAL pull /sdcard/shot.png shot.png
```

注意：

- 不要用 `adb shell screencap -p > file` 再当 PNG 用：部分 adb/终端会把 `\n` 转成 `\r\n`，
  文件头就坏了。devkit 校验 PNG 魔数（`89 50 4E 47`）和 IHDR 宽高。
- 截图前先收起键盘、等目标文本出现；截图后记录设备序列号与时间。
- 本机实测：MIX 2S 720x1280 截图约 70-130KB；OPPO 1440x3168 约 330-400KB（内容不同浮动）。

### 4.2 录屏

```bash
adb -s $SERIAL shell screenrecord --time-limit 20 --bit-rate 8000000 /sdcard/demo.mp4
adb -s $SERIAL pull /sdcard/demo.mp4 demo.mp4
adb -s $SERIAL shell rm -f /sdcard/demo.mp4
```

- 单段上限 180 秒；更长要分段再拼接（本机无 ffmpeg，暂不承诺）。
- 分辨率高的机器可加 `--size 720x1280` 降低体积。
- 正常到时/结束会 finalize；中途 `Ctrl-C` 可能留下不可播放文件，所以 devkit 使用 `--time-limit`。
- devkit：`record --seconds 20 -s SERIAL`，自动拉回 `artifacts/videos/<serial>/`。
- 录屏适合证明"点击→转场→状态变化"；静态验收仍以截图 + UI XML + logcat 为主。

### 4.3 产物规范

每次运行写 `artifacts/manifest.jsonl`：`ts / cmd / kind / serial / path / bytes / sha256`。
文件名带毫秒时间戳，永不覆盖旧证据。

## 5. 结构化日志与断言

### 5.1 tag 规范

统一前缀 `BtShare.<域>`，域固定、可枚举：

| tag | 用途 | 示例事件 |
| --- | --- | --- |
| `BtShare.Ui` | 页面/交互 | `UI_TAP_START`、`UI_STATE_IDLE` |
| `BtShare.Perm` | 权限 | `PERM_LOCAL_NETWORK state=denied` |
| `BtShare.Session` | 会话生命周期 | `SESSION_START id=...`、`SESSION_STOP reason=...` |
| `BtShare.Net` | 监听/连接 | `LAN_LISTEN port=6881 result=ok`、`PEER_CONNECTED ip=...` |
| `BtShare.Transfer` | 传输 | `TRANSFER_START file=... bytes=...`、`TRANSFER_DONE` |
| `BtShare.Bt` | 蓝牙发现/配对 | `BT_SCAN_START`、`BT_BOND_LOST` |
| `BtShare.Tracker` | tracker/发现 | `TRACKER_ANNOUNCE result=ok` |

事件行统一 `EVENT KEY=value KEY=value`，不要写自由文本；错误必须带 `code=` 和 `reason=`。
示例：

```text
I/BtShare.Perm: PERM_LOCAL_NETWORK state=denied sdk=37 target=37
I/BtShare.Net: LAN_LISTEN port=6881 iface=wlan0 result=ok
I/BtShare.Session: SESSION_START id=abc123 peer=192.168.1.23 role=seed
E/BtShare.Transfer: TRANSFER_FAIL code=EPERM file=... reason=local_network_denied
```

日志级别：`V` 只用于开发细节；`I` 关键状态迁移；`W` 可恢复异常；`E` 用户可见失败。
不要打印文件内容、密钥、邀请码明文。

### 5.2 采集

```bash
adb -s $SERIAL logcat -G 16M
adb -s $SERIAL logcat -c
adb -s $SERIAL logcat -d -v threadtime -s BtShare.Ui:V BtShare.Perm:V BtShare.Net:V BtShare.Session:V
```

devkit：

```bash
node tools/devkit/devkit.mjs clear-logs -s $SERIAL
node tools/devkit/devkit.mjs logcat-dump --lines 5000 --grep "BtShare" -s $SERIAL
node tools/devkit/devkit.mjs logcat-dump --require "BtShare.Net.*PEER_CONNECTED" -s $SERIAL
```

`--require` 是断言：没有命中就退出 1，适合 CI。`--grep` 只过滤不判失败。

### 5.3 验证脚本里的 grep 断言

正断言（必须出现）：

```bash
node tools/devkit/devkit.mjs logcat-dump --require "BtShare.Session.*SESSION_START" -s $SERIAL
node tools/devkit/devkit.mjs logcat-dump --require "BtShare.Transfer.*TRANSFER_DONE bytes=[1-9]" -s $SERIAL
```

负断言（不能出现）——用导出文件加 grep 白名单，避免把二进制/构建产物扫进来：

```bash
node tools/devkit/devkit.mjs logcat-dump --lines 5000 --out artifacts/logcat/$SERIAL/run.log -s $SERIAL
if grep -Eq "FATAL EXCEPTION|ANR in dev\.example\.bt" artifacts/logcat/$SERIAL/run.log; then
  echo "crash or ANR detected" >&2
  exit 1
fi
```

注意：

- 只对 `.log/.txt/.json/.xml` 等文本产物 grep；不要对 APK/so/aar 做"符号 grep"当证据（AGENTS 第 8 节）。
- 事件要先有"开始/成功/失败"三种，才能断言状态机；只打"成功"无法定位卡住。
- 时间窗口：`clear-logs` 后跑场景，再 `logcat-dump`；不要拿历史日志当本次证据。
- 多设备时每台设备单独断言，不要把两台日志混在一起。

## 6. Gradle Managed Devices 是否值得

Gradle Managed Devices（GMD）用 Gradle 管理模拟器（ATD/Google APIs 镜像），跑 instrumentation 测试。
对本项目的结论：**当前不值得，且不替代真机**。

| 维度 | GMD | 本机真机 |
| --- | --- | --- |
| 安装成本 | 需要 Gradle + emulator + 系统镜像 + 硬件加速；本机未装 Gradle/模拟器 | 已就绪（adb + 两台真机） |
| 系统版本 | 镜像可用性滞后；需要 API 37 镜像 | 已有 Android 13 + Android 17 真机 |
| 蓝牙 | 模拟器无真实 BT 控制器 | 真实 BT/配对/权限 |
| Wi-Fi Direct/局域网 | 模拟，不可信 | 真实 LAN |
| OEM 行为 | 无（AOSP 镜像） | MIUI/ColorOS 的坑直接暴露 |
| 速度/并行 | 可分片、可并行 | 两台串行也够快（本轮冒烟约 30 秒） |
| 结论 | 只适合纯 Compose UI 的预检 | 项目验收的唯一事实 |

建议：等工程接入 Gradle 后，用 `connectedAndroidTest` 跑 Espresso/Compose 测试到两台真机；
GMD 仅在"没有真机可用、且只验证纯 UI 逻辑"时再考虑，并在结论里标注"不可替代真机"。

## 7. "CI 式一键验证脚本"长什么样

一个合格的脚本必须满足：

1. **预检**：脚本读取 `devices` 输出，确认目标设备在线、系统版本符合预期；不满足就退出 3。
2. **可重复的初始状态**：`launch --fresh` 或 force-stop + 清日志，避免上一次状态污染。
3. **安装**：`install <apk>`；失败立即退出，不留半套状态。
4. **启动**：`launch <pkg> [activity]`，并确认前台包名。
5. **操作**：`tap-text` 等；每步都有明确目标文案。
6. **断言**：`wait-text` 等文本 + `logcat-dump --require` 事件；失败退出 1。
7. **取证**：关键步骤前后 `screenshot` + `ui-dump`；日志导出。
8. **汇总**：写 `artifacts/verify/<stamp>/summary.json` 与 `summary.txt`，人可读、机器可解析。
9. **退出码**：全过 0，任一断言失败 1，设备/环境错误 3；CI 只看退出码。

仓库里的实现：`tools/devkit/examples/system-ui-smoke.mjs`。它不依赖被测 App，用系统设置证明链路，
并把候选入口/期望文案写成可替换的 `CANDIDATES`。实际运行结果：

```text
[3B1F5HEADK1TXSF0] PKJ110 (Android 17/API 37) — start
[3B1F5HEADK1TXSF0] PASS 显示与亮度 → 亮色模式 (2362ms)
[5b94673a] Mi MIX 2S (Android 13/API 33) — start
[5b94673a] PASS 电池 → 电池用量 (2460ms)
# pass=2 fail=0
```

换成被测 App 的模板：

```bash
SERIALS=$(node tools/devkit/devkit.mjs devices --json | grep '"state":"device"' | sed -E 's/.*"serial":"([^"]+)".*/\1/')
for S in $SERIALS; do
  node tools/devkit/devkit.mjs clear-logs -s $S
  node tools/devkit/devkit.mjs install build/outputs/apk/debug/app-debug.apk -s $S
  node tools/devkit/devkit.mjs launch dev.example.bt --fresh -s $S
  node tools/devkit/devkit.mjs wait-text "开始共享" --timeout 10000 -s $S
  node tools/devkit/devkit.mjs tap-text "开始共享" -s $S
  node tools/devkit/devkit.mjs wait-text "等待连接" --timeout 10000 -s $S
  node tools/devkit/devkit.mjs screenshot --name sharing -s $S
  node tools/devkit/devkit.mjs logcat-dump --require "BtShare.Ui.*UI_STATE_IDLE" -s $S
done
```

要点：脚本里不要出现"人工判断"和"如果看起来对就继续"；所有分支都由退出码/`--json` 字段决定。

## 8. Android 17 / API 37 本地网络权限与 Android 16 起的权限变化

### 8.1 现象：什么会坏

Android 17 对 `targetSdk >= 37` 的应用默认阻断局域网访问（Local Network Protections，LNP）：

- 出站 TCP 连接、入站 TCP accept；
- UDP unicast / multicast / broadcast 的发送与接收；
- mDNS/`.local` 解析；
- 拦截在系统网络栈，**所有网络 API 都受影响**：Java/Kotlin socket、OkHttp/Cronet、nsd、以及
  JNI 里的 libtorrent 原生 socket 都绕不过去。

典型现象：连接超时/被拒；UDP `sendto` 返回 `EPERM`；应用 UI 仍显示"在线"或"等待连接"，
但实际数据面不通。Internet 访问不受影响（权限只管本地网络）。

当前项目基线是 `targetSdk 36`：

- 在 Android 17 上按 legacy 应用处理，只要声明了 `INTERNET` 就获得隐式授权，暂不阻断；
- 一旦把 `targetSdk` 升到 37，隐式授权消失，必须声明并请求 `ACCESS_LOCAL_NETWORK`；
- Android 16（`targetSdk 36`）是 opt-in 阶段，需要 compat flag 才模拟阻断（见 8.5）。

> 结论：现在（targetSdk 36）两台机器能跑，不代表升到 37 还能跑；LNP 必须在升 targetSdk 前进入测试矩阵。

### 8.2 权限名与权限组

| 权限 | API | 保护级别 | 说明 |
| --- | --- | --- | --- |
| `android.permission.ACCESS_LOCAL_NETWORK` | 37 | dangerous | 访问本地网络；属于 `NEARBY_DEVICES` 权限组 |
| `android.permission.NEARBY_WIFI_DEVICES` | 33 | dangerous | Wi-Fi 邻近设备；Android 16 的 LNP opt-in 阶段临时用它做门禁 |
| `android.permission.BLUETOOTH_SCAN` | 31 | dangerous | 蓝牙扫描；可加 `neverForLocation` |
| `android.permission.BLUETOOTH_CONNECT` | 31 | dangerous | 连接已配对设备 |
| `android.permission.BLUETOOTH_ADVERTISE` | 31 | dangerous | 被其他设备发现 |

权限组关系（官方文档 + 本机 OPPO 实测）：

- `NEARBY_DEVICES` 组包含蓝牙三权限、`NEARBY_WIFI_DEVICES`、`ACCESS_LOCAL_NETWORK`。
- 用户已授予组内任一权限（例如蓝牙）时，请求 `ACCESS_LOCAL_NETWORK` 不会再次弹窗（预授权）。
- 反过来：如果应用完全没有组内权限，Android 17 上第一次访问局域网会被阻断，需要显式请求。

OPPO 实测（Android 17）：

```text
$ adb -s 3B1F5HEADK1TXSF0 shell pm list permissions -g -f | grep -i "local\|nearby"
+ permission:android.permission.ACCESS_LOCAL_NETWORK
  protectionLevel:dangerous|runtime
+ permission:android.permission.NEARBY_WIFI_DEVICES
  protectionLevel:dangerous
+ group:android.permission-group.NEARBY_DEVICES
```

### 8.3 Manifest 声明

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />

    <!-- Android 12+ 蓝牙 -->
    <uses-permission
        android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />

    <!-- Android 13+ Wi-Fi 邻近设备（Android 16 LNP opt-in 阶段也用它） -->
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />

    <!-- Android 17+ 本地网络 -->
    <uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />

    <!-- 前台服务：长传输用 connectedDevice，不用 dataSync -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application ...>
        <service
            android:name=".TransferService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
    </application>
</manifest>
```

注意：

- `ACCESS_LOCAL_NETWORK` 需要 `compileSdk 37` 才有常量；当前基线 compileSdk 37、targetSdk 36，
  声明不报错，但 targetSdk 36 时不会触发运行时请求（legacy 隐式授权）。
- 只声明不请求：Android 17 targetSdk 37 上仍会被阻断。
- 蓝牙扫描如果确实不用于推断位置，保留 `neverForLocation`；否则还需要位置权限，属于另一条链路。

### 8.4 运行时请求（应用侧）

```kotlin
private const val REQ_LOCAL_NETWORK = 1001

private fun hasLocalNetworkPermission(): Boolean {
    if (Build.VERSION.SDK_INT < 37) return true
    return checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
        PackageManager.PERMISSION_GRANTED
}

private fun ensureLocalNetworkPermission() {
    if (hasLocalNetworkPermission()) return
    requestPermissions(
        arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK),
        REQ_LOCAL_NETWORK,
    )
}

override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode != REQ_LOCAL_NETWORK) return
    val granted = grantResults.isNotEmpty() &&
        grantResults[0] == PackageManager.PERMISSION_GRANTED
    val state = if (granted) "granted" else "denied"
    Log.i("BtShare.Perm", "PERM_LOCAL_NETWORK state=" + state)
    if (!granted) {
        // UI 进入"需要局域网权限"状态；不要静默重试，不要假装在线
    }
}
```

触发时机：第一次点"开始共享/连接"时请求，而不是冷启动就弹；拒绝后 UI 明确显示原因与重试入口，
并记录 `PERM_LOCAL_NETWORK state=denied`。系统 picker 路径（NsdManager `FLAG_SHOW_PICKER`）只适合
系统托管的发现，libtorrent 的 raw mDNS 用不上，所以本项目需要走运行时权限。

### 8.5 adb 授予/撤销/验证

标准命令（需要应用已声明该权限）：

```bash
adb -s $SERIAL shell pm grant  <pkg> android.permission.ACCESS_LOCAL_NETWORK
adb -s $SERIAL shell pm revoke <pkg> android.permission.ACCESS_LOCAL_NETWORK
adb -s $SERIAL shell dumpsys package <pkg> | grep -E "ACCESS_LOCAL_NETWORK|BLUETOOTH_|NEARBY_WIFI_DEVICES"
```

安装时一并授予（**推荐给 CI**，不弹窗）：

```bash
adb -s $SERIAL install -r -g -t app-debug.apk
```

`-g` 会授予应用声明的全部运行时权限（包括 `ACCESS_LOCAL_NETWORK`，前提是应用已声明且设备为 API 37+）。
devkit 的 `install` 默认就带 `-g`；需要测试"未授权"路径时用 `--no-grant` 或 `pm revoke`。

Android 16 opt-in（本机没有 Android 16 设备，命令来自官方文档，待有机器时验证）：

```bash
adb -s $SERIAL shell am compat enable RESTRICT_LOCAL_NETWORK <pkg>
adb -s $SERIAL reboot
# 恢复
adb -s $SERIAL shell am compat disable RESTRICT_LOCAL_NETWORK <pkg>
```

OEM 坑（本机实测）：

- OPPO ColorOS（Android 17）的 shell 没有 `GRANT_RUNTIME_PERMISSIONS` / `REVOKE_RUNTIME_PERMISSIONS`，
  `pm grant/revoke` 直接抛 `SecurityException`：

```text
java.lang.SecurityException: grantRuntimePermission: Neither user 2000 nor current process
has android.permission.GRANT_RUNTIME_PERMISSIONS.
```

- MIX 2S（Android 13）用 CAMERA 权限验证同一命令正常（该版本没有 ACCESS_LOCAL_NETWORK）。
- 因此在 ColorOS 上做权限矩阵，优先：
  1. `adb install -g`（安装时授予）；
  2. devkit 点系统权限弹窗的"允许"（需要先清应用数据/卸载重装触发弹窗）；
  3. 必要时用工程机的 root/系统权限（本项目未要求）。

### 8.6 对本项目 BT 局域网直连的影响

基线：BT 用于发现/配对，局域网用于数据面（libtorrent），发现层是 mDNS + tracker + 手动连接。

| 路径 | Android 17 targetSdk 37 下是否受 LNP 约束 | 说明 |
| --- | --- | --- |
| TCP 连接对端 IP:port | 是 | 手动连接、tracker 返回局域网 peer 都算 |
| TCP 监听/accept | 是 | 做种端/接收端都要权限；只给"连接方"授权不够 |
| UDP tracker（公网） | 否 | 目标是公网地址；若 tracker 部署在局域网则受约束 |
| mDNS 组播发现 | 是 | 原始 multicast socket；libtorrent 内部实现也绕不过 |
| DHT/PEX/LSD | 私有模式已关闭 | 关闭后减少公网暴露，但不改变 LNP 结论 |
| 蓝牙 RFCOMM/扫描 | 不受 LNP 约束 | 受蓝牙运行时权限/邻近设备组约束 |

结论：

1. **两端都要授权**。数据面是双向的：一端 accept 也需要 `ACCESS_LOCAL_NETWORK`，否则连接建不起来。
2. **发现层也要授权**。mDNS 是本地组播；targetSdk 37 下未授权会直接失败。系统 picker 只能覆盖
   NsdManager 路径，libtorrent 的 mDNS 需要自己请求权限。
3. **权限状态必须是 UI 状态**。未授权时不要显示"在线/等待连接"；显示"需要局域网权限"并给重试入口。
4. **权限状态必须可断言**：日志 `PERM_LOCAL_NETWORK state=` + `dumpsys package` 双证据；
   UI 文案不能作为网络连通的证据（见 8.8）。
5. **前台服务**：传输放到 `connectedDevice` 类型的 FGS，声明 `FOREGROUND_SERVICE_CONNECTED_DEVICE`；
   不要在后台裸起线程/服务。Android 16 起 FGS 启动的后台任务还要受 JobScheduler 配额约束。

### 8.7 Android 16 起的邻近设备 / 前台服务变化

邻近设备（Nearby devices）：

- Android 12（API 31）起，蓝牙三权限属于 `NEARBY_DEVICES` 组，系统弹窗文案是"附近的设备"。
- Android 13（API 33）加入 `NEARBY_WIFI_DEVICES`；Android 17（API 37）加入 `ACCESS_LOCAL_NETWORK`，
  仍属同一组，组内已有授权时不再重复弹窗。
- Android 16 的 LNP opt-in 用 `NEARBY_WIFI_DEVICES` 作为临时门禁；Android 17 正式切换为
  `ACCESS_LOCAL_NETWORK`，并把"targetSdk < 37 的 legacy 隐式授权"作为过渡（官方表格写明是临时的）。
- Android 16 还改了 CompanionDeviceManager 的配对回调：不再直接通知
  `RESULT_DISCOVERY_TIMEOUT`，超时由用户弹窗处理，应用收到 `RESULT_USER_REJECTED`；
  如果用 CDM 做发现，状态机要按这个改（本项目当前不依赖 CDM）。
- 蓝牙 bond：Android 16 新增 `ACTION_KEY_MISSING` / `ACTION_ENCRYPTION_CHANGE` 两个 intent；
  Android 17 引入系统自动重配对（autonomous re-pairing），`ACTION_KEY_MISSING` 只在自动重配对失败后
  才广播，`ACTION_PAIRING_REQUEST` 带 `EXTRA_PAIRING_CONTEXT`。配对状态机不能假设"用户手动重新配对"
  就一定发生，也不能把旧的 bond 丢失当成永久失败。

前台服务（Foreground Service）：

- Android 14（API 34）起，`startForeground()` 必须声明类型；长传输用 `connectedDevice`，
  需要 `FOREGROUND_SERVICE_CONNECTED_DEVICE` 权限，且满足以下条件之一：
  manifest 声明 `CHANGE_NETWORK_STATE` / `CHANGE_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE` /
  `NFC` / `TRANSMIT_IR`，或已授予 `BLUETOOTH_CONNECT/SCAN/ADVERTISE` / `UWB_RANGING` 之一。
- Android 15（API 35）起，`dataSync` 类型前台服务有 6 小时超时，且 `BOOT_COMPLETED` 不能启动
  某些类型；长传输不要用 `dataSync`。
- Android 16（API 36）起，前台服务启动的后台任务（JobScheduler/WorkManager/DownloadManager）
  也必须遵守配额；用 `connectedDevice` 保持传输前台可见，比后台 job 更可靠。
- Android 13+ 需要 `POST_NOTIFICATIONS` 才能显示 FGS 通知；没有通知用户看不到传输状态，也更容易被系统清理。

> 精确清单以官方页面为准：
> - Local Network：https://developer.android.com/privacy-and-security/local-network-permission
> - Android 16 behavior changes：https://developer.android.com/about/versions/16/behavior-changes-16
> - Android 17 behavior changes：https://developer.android.com/about/versions/17/behavior-changes-17
> - FGS changes：https://developer.android.com/develop/background-work/services/fgs/changes
> - FGS types：https://developer.android.com/develop/background-work/services/fgs/service-types
> - Bluetooth permissions：https://developer.android.com/develop/connectivity/bluetooth/bt-permissions

### 8.8 本机验证现状与测试矩阵

已验证：

- OPPO（API 37）存在 `ACCESS_LOCAL_NETWORK`（dangerous，`NEARBY_DEVICES` 组）；
  `pm list permissions` 与 `dumpsys package android` 均可查到。
- OPPO 的 `pm grant/revoke` 被 OEM 拒绝（见 8.5）；`install -g` 可用（用旧 APK 的 CAMERA 实测）。
- 旧项目 APK（`dev.p2pshare.app` 0.4.5，targetSdk 37、compileSdk 37）在 OPPO 上能安装、启动，
  UI 显示"在线"；但它网络/相机相关只声明了 `INTERNET`、`ACCESS_NETWORK_STATE`、`CAMERA`
  （另有一个动态接收器权限），没有 `ACCESS_LOCAL_NETWORK` 或蓝牙权限。**这说明 UI 的"在线"不能证明局域网数据面可用**，
  必须用 socket/日志断言。该 APK 已从两台设备卸载。

待补（应用首个 APK 落地后必做）：

| 编号 | 设备/系统 | targetSdk | 操作 | 期望 |
| --- | --- | --- | --- | --- |
| P1 | MIX 2S / 13 | 36/37 | 安装、启动、局域网互传 | 正常（API 33 无 LNP） |
| P2 | OPPO / 17 | 36 | 同 P1 | 正常（legacy 隐式授权） |
| P3 | OPPO / 17 | 37 | 不授权直接访问 LAN | 失败；日志/异常可断言 |
| P4 | OPPO / 17 | 37 | 请求并允许 `ACCESS_LOCAL_NETWORK` | 数据传输成功；日志 `PERM...granted` |
| P5 | OPPO / 17 | 37 | 拒绝/撤销后再访问 | UI 提示权限；数据面失败；`PERM...denied` |
| P6 | OPPO / 17 | 37 | 点击/旋转/切后台后传输 | FGS 存活、无 ANR、数据完成 |
| P7 | 两台互联 | 36/37 | MIX ↔ OPPO 互传 | 同一协议、同一内核/等价实现，双方可见 |

P3/P5 的触发方式：ColorOS 上不要依赖 `pm revoke`；用 `install --no-grant` + UI 拒绝，或卸载重装清数据。

## 9. 产物与复现

本轮产物（全部在 `artifacts/`）：

- `artifacts/verify/20261106-015932/summary.json`：两台设备 smoke 的 PASS/FAIL 与证据路径。
- `artifacts/verify/acceptance-20261106-020008.txt`：doctor/devices/screenshot/ui-dump 的原始输出与退出码。
- `artifacts/screenshots/<serial>/20261106-020008-*.png`：验收命令 `devkit screenshot` 的产物。
- `artifacts/ui-dumps/<serial>/20261106-020012-*.xml`、`20261106-020014-*.xml`：验收命令 `devkit ui-dump` 的产物。
- `artifacts/logcat/...`、`artifacts/videos/...`：日志与录屏样例。
- `artifacts/manifest.jsonl`：所有产物的 sha256 索引。

复跑验收（管理层可直接执行）：

```bash
node tools/devkit/devkit.mjs devices
node tools/devkit/devkit.mjs screenshot
node tools/devkit/devkit.mjs ui-dump
node tools/devkit/examples/system-ui-smoke.mjs
```

预期：两条 `OK`（或每台一行 `DEVICE/OK`）；新增文件带当前时间戳；smoke 退出 0、`pass=2`。

## 10. 风险与未决

1. **uiautomator 的 idle 限制**：MIUI"关于手机"类页面无法黑盒 dump。需要 Compose/Espresso 补位，
   或产品验收避开该页。
2. **OPPO 间歇在线**：离线时显式 `-s` 退出 3；默认多设备命令只跑在线设备，报告里要写清"本次少一台"。
3. **ColorOS 的 pm grant 限制**：权限矩阵依赖 `install -g` 或 UI 点击；等首个声明 `ACCESS_LOCAL_NETWORK`
   的 APK 出来，才能把 `pm grant` 在 API 37 的最终行为验证死。
4. **targetSdk 36 → 37 的迁移**：这是产品/架构决策，不由本工具决定；但迁移前必须把 P3-P5 加入验收。
5. **构建链尚未就绪**：本机无 Gradle，Espresso/Compose 测试还没法跑；devkit 先覆盖黑盒端到端。
6. **录屏上限 180 秒**：长传输需要分段；本机无 ffmpeg，暂不做拼接。
7. **文件一致性**：devkit 的 dump 用进程号+时间戳命名远端文件；同一设备并发调用仍可能互相干扰，
   脚本应顺序执行单设备 UI 操作。
8. **中文输入还没有闭环**：`input text` 只支持 ASCII，非 ASCII 在 Android 13 上直接 NPE（见 3.7）。
   要断言中文文案（用户名、评论内容），必须先装 ADBKeyboard 并把它设为默认输入法，再验证 devkit 的
   base64 广播分支——这条分支目前只有代码，没有真机证据。把它设为默认输入法会改变软键盘行为，
   `uiautomator` 的层级和"按返回收键盘"的步骤都会受影响，做这个实验前要先和 UI 自动化脚本对齐。
9. **`launch --extra` 只有字符串 extra**：`--ei` / `--ez` / `--el` 等类型没接；需要整数或布尔调试开关时
   要么在应用侧加一个字符串版本，要么先扩 devkit。

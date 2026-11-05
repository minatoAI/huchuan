# devkit — Android 真机验证 CLI

零第三方依赖（Node >= 18，只用 `node:` 内置模块）。所有 adb 调用直接 spawn，不经过 shell，
因此不受 MSYS 的 POSIX 路径转换影响（`/sdcard` 不会被改写成 `C:/Program Files/Git/sdcard`）。

## 快速开始

```bash
node tools/devkit/devkit.mjs doctor
node tools/devkit/devkit.mjs devices
node tools/devkit/devkit.mjs screenshot            # 默认对全部在线设备
node tools/devkit/devkit.mjs ui-dump               # 默认对全部在线设备
node tools/devkit/devkit.mjs ui-dump -s 5b94673a --texts
node tools/devkit/devkit.mjs tap-text "电池" -s 5b94673a
node tools/devkit/devkit.mjs wait-text "电池用量" --timeout 8000 -s 5b94673a
node tools/devkit/devkit.mjs wait-text "电池用量" 8000 -s 5b94673a   # 位置参数也接受
node tools/devkit/devkit.mjs input text "mix-2s" --verify -s 5b94673a   # 往焦点输入框打字
node tools/devkit/devkit.mjs launch app.huchuan --extra huchuan.debug.network=failed -s 5b94673a
```

也可以直接用仓库内的启动器：

```bash
tools/devkit/devkit devices        # MSYS / Git Bash
tools\devkit\devkit.cmd devices   # cmd / PowerShell
```

## adb 查找顺序

1. `DEVKIT_ADB`
2. `ADB`
3. `ANDROID_HOME` / `ANDROID_SDK_ROOT` 下的 `platform-tools/adb`
4. 本机默认：`E:/agentHome/android-sdk/platform-tools/adb.exe`
5. 用户目录常见 SDK、`C:/Android/Sdk`
6. `PATH` 里的 `adb`

显式设置 `DEVKIT_ADB` / `ADB` 时具有权威性：路径不存在会直接报错（退出 3），不会静默回退到其它 adb。
`doctor` 会打印实际使用的路径与来源。其它机器部署时建议显式设置 `DEVKIT_ADB`。

## 命令

| 命令 | 说明 | 关键选项 |
| --- | --- | --- |
| `devices` | 列出在线/离线设备、型号、Android 版本、SDK、有效分辨率、密度 | `--json` |
| `doctor` | 环境自检：adb 版本、产物目录、设备数 | `--json` |
| `install <apk>` | `adb install -r -t`，默认加 `-g` 授予全部运行时权限 | `--no-grant`、`--downgrade` |
| `launch <pkg> [activity]` | 启动并轮询确认目标包在前台 | `--fresh`、`--activity`、`--timeout`、`--extra k=v`（可重复）、`--es` |
| `screenshot` | `exec-out screencap -p`，校验 PNG 魔数 | `--name` |
| `ui-dump` | dump 当前窗口层级，保存 XML | `--texts`、`--name` |
| `tap-text <text>` | 点文本/content-desc/hint 命中的节点中心 | `--exact`、`--regex`、`--index` |
| `input text <text>` | 往当前焦点输入框打字（ASCII 原样注入） | `--verify` |
| `wait-text <text> [timeoutMs]` | 轮询直到文本出现，超时退出 1 | `--exact`、`--regex`、`--timeout`、`--interval` |
| `logcat-dump` | 导出最近 N 行，可 tag/正则过滤；`--require` 命中断言 | `--grep`、`--require`、`--tag`、`--lines`、`--buffer`、`--out` |
| `clear-logs` | 清空 logcat（先 `-b all`，失败回退默认） | |
| `key <CODE>` | `input keyevent`，如 `KEYCODE_BACK` | |
| `swipe <x1> <y1> <x2> <y2> [ms]` | `input swipe`，默认 300ms | |
| `record` | `screenrecord` 取证，自动拉回 mp4 | `--seconds`（1-180）、`--bitrate` |

全局选项：`-s/--serial`（可重复、可逗号分隔）、`--all`、`--json`、`--artifacts <dir>`、
`--no-manifest`、`-h/--help`、`--version`。

## 设备选择规则

- 指定了 `-s` / `--serial`：只跑指定设备；离线/不存在会单独报错（退出 3）。
- 未指定：
  - 只有一台在线：自动选它；
  - 多台在线且命令是 `screenshot` / `ui-dump` / `logcat-dump` / `clear-logs`：默认跑全部在线设备；
  - 多台在线且命令会改变设备状态（install/launch/tap/wait/key/swipe/record）：退出 2，
    提示用 `-s` 或 `--all`，避免误操作。
- `devices` / `doctor` 永远列出全部设备，不受 `-s` 影响。

## 文本输入（`input text`）

先 `tap-text` 点进输入框，再 `input text <text>`。文本原样交给设备端 `input text`，
设备 shell 的引号由 devkit 加，所以空格、单双引号、`&`、`$`、`|`、`;`、`\`、`%` 都不用 devkit 再转义
（调用方 shell 该有的引号还是要有，别让 bash 先吃掉 `$`）：

```bash
node tools/devkit/devkit.mjs tap-text "mi mix 2s" -s $S      # 点进输入框
node tools/devkit/devkit.mjs key KEYCODE_MOVE_END -s $S      # 光标移到末尾（可选）
node tools/devkit/devkit.mjs input text "mix-2s-check-42" --verify -s $S
node tools/devkit/devkit.mjs ui-dump --texts -s $S | grep "mix-2s"
```

- **空格**：直接写。`input text` 的 `%s` 也被它当空格，所以字面量 `%s` 会被 devkit 拆成
  `%` + `s` 两段发送，保证打出来还是 `%s`（实测）。
- **`--verify`**：打完 dump 一次当前界面，读焦点输入框的文本，断言包含所打内容。
  不通过退出 1，并区分两种情况：一个字都没进（没聚焦/密码框），和只进了前 N 个字符
  （输入框有字数上限，实测用户名框上限 20 字）。
- **不加 `--verify` 时不做 dump**：adb 在"没有焦点输入框"时依旧返回 0，命令会"成功"但什么也没打进去。
  CI 里请用 `--verify`。
- **中文 / 非 ASCII 打不进去**：Android 的 `input text` 只支持 ASCII，非 ASCII 会直接抛
  `NullPointerException`（实测 Android 13：`InputShellCommand.sendText` 里
  `Attempt to get length of null array`，adb 退出 255）。devkit 在发送前就拦住，退出 3 并打印原因。
  唯一通路是装 ADBKeyboard（`com.android.adbkeyboard`）并设为默认输入法：devkit 检测到它会自动改用
  `am broadcast -a ADB_INPUT_B64 --es msg <base64>`。本机设备没装，这条分支未实测。
- 密码框回显 `●`，`--verify` 拿不到明文，会报断言失败；这类输入不要加 `--verify`。

## 启动带 extra（`launch --extra`）

```bash
node tools/devkit/devkit.mjs launch app.huchuan --extra huchuan.debug.network=failed -s $S
node tools/devkit/devkit.mjs launch app.huchuan --es huchuan.debug.network=limited -s $S
node tools/devkit/devkit.mjs launch app.huchuan .MainActivity \
  --extra huchuan.debug.manual=10.0.0.5:47100,10.0.0.6:47100 -s $S
```

- `--extra k=v` 可重复，`--es` 是同一件事的别名，最终拼成 `am start -W -n <组件> --es k v ...`。
- 没给 activity 时用 `cmd package resolve-activity --brief` 解析 launcher activity（extra 必须走 `am`，
  `monkey` 不能带 extra）。
- 带 `--extra` 会**自动先 force-stop**：进程已在前台时 `am start` 只把 task 提到最前
  （实测 `LaunchState: HOT` / `Activity not started`），`onCreate` 不再跑，新 extra 会被吞掉。
  输出里 `fresh=true` 就是这个意思。
- 只支持字符串 extra。`am` 的类型选项（`--ei` / `--ez` / `--el`）没接。
- key 只接受 `[A-Za-z0-9_.-]`；value 不能以 `-` 开头（`am` 会当成自己的选项）；其余字符原样透传，
  含空格、冒号、逗号都可以。

## 文本匹配

- 默认：对 `text`、`content-desc`、`hint` 做不区分大小写的子串匹配。
- `--exact`：去空白后完全相等；`--regex`：JS 正则（`i` 标志）。
- 只接受 bounds 有效的节点（宽高 > 0），避免点到 `(0,0)`。MIUI 个别标题节点 bounds 为负高度，
  会被自动跳过。
- 多个命中时精确命中优先，其余按文档顺序；`--index` 选第 n 个（0 起）。
- 命中节点不可点击也照点其中心——绝大多数列表项的可点击父容器覆盖文字中心。

## 产物

默认根目录 `<repo>/artifacts`（可用 `--artifacts` / `DEVKIT_ARTIFACTS` 覆盖）：

```
artifacts/
  manifest.jsonl                     # 每次产物的 JSONL 索引（含 sha256）
  screenshots/<serial>/<stamp>[-name].png
  ui-dumps/<serial>/<stamp>[-name].xml
  logcat/<serial>/<stamp>.log
  videos/<serial>/<stamp>.mp4
```

`manifest.jsonl` 字段：`ts`、`cmd`、`kind`、`serial`、`path`（相对 artifacts）、`bytes`、`sha256`。

## 输出与退出码

- 默认行格式：`OK <cmd> serial=... key=value...`、`ERR ...`、`NODE ...`、`LOG ...`。
- `--json`：每行一个 JSON 对象，成功与失败都带 `ok`、`cmd`、`serial`。
- 退出码：
  - `0` 成功；
  - `1` 断言失败（文本未找到、启动未到前台）；
  - `2` 用法错误（多设备未指定、参数非法）；
  - `3` adb / 设备错误（离线、dump 失败、安装失败）；
  - `4` 本地文件错误（APK 不存在、产物不可写）。
- 多设备：任意设备失败即非 0；返回优先级 `2 > 3 > 4 > 1`。

## 真机已知问题（实测）

1. `uiautomator dump` 在 UI 不 idle 时输出 `ERROR: could not get idle state.`，**退出码仍是 0**。
   devkit 每次先删远端文件、再校验拿到的 XML；失败重试 3 次后报错，绝不返回旧 XML。
   实测：Mi MIX 2S（Android 13）设置 → 关于手机页稳定复现；动画缩放设为 0 也不能规避。
2. MSYS 下直接用 `adb shell uiautomator dump /sdcard/...` 会被路径转换破坏；devkit 不经过 shell，
   不受影响。
3. OPPO ColorOS（Android 17）的 shell 没有 `GRANT_RUNTIME_PERMISSIONS`，`adb shell pm grant`
   会抛 `SecurityException`；Xiaomi Android 13 正常。需要授权时用 `adb install -g`（安装时授予）
   或 UI 自动化点"允许"。
4. `screenrecord` 单段上限 180 秒；录屏文件在命令正常结束/到时后才完整，devkit 用 `--time-limit`
   保证有界。
5. OPPO 间歇在线；离线时显式 `-s` 会退出 3，默认多设备命令只会跑在线设备。
6. `uiautomator dump` 碰到含双引号的文本会改用单引号包属性值（`text='say "hi"'`），
   普通 `text="([^"]*)"` 解析会整条丢掉。devkit 的 XML 扫描两种引号都认（实测 app.huchuan 评论输入框）。
7. Compose 输入框的文本在 XML 里会多出尾部空格（`text="abc  "`），所以 `input text --verify`
   用"包含"判断，不用等值判断。
8. `input text` 在非 ASCII 上不是报错而是抛 NPE：`InputShellCommand.sendText` 里
   `Attempt to get length of null array`，adb 退出 255（实测 Mi MIX 2S / Android 13）。

## 例子

```bash
# 系统设置冒烟：两台真机各跑一遍"启动→找入口→点击→等待→截图/留证"
node tools/devkit/examples/system-ui-smoke.mjs

# 被测 App 的典型链路
node tools/devkit/devkit.mjs clear-logs -s $S
node tools/devkit/devkit.mjs install build/outputs/apk/debug/app-debug.apk -s $S
node tools/devkit/devkit.mjs launch dev.example.bt -s $S --fresh
node tools/devkit/devkit.mjs wait-text "等待连接" --timeout 10000 -s $S
node tools/devkit/devkit.mjs tap-text "开始共享" -s $S
node tools/devkit/devkit.mjs screenshot --name after-start -s $S
node tools/devkit/devkit.mjs logcat-dump --grep "BtShare" -s $S
```

## 限制

- 不支持 split APK / AAB（用 `bundletool` 产出 universal APK，或补 `install-multiple`）。
- 没有坐标点击、滚动查找；需要时用 `swipe` + `ui-dump` 组合，或后续补命令。
- `input text` 只能注入 ASCII；中文等非 ASCII 依赖 ADBKeyboard（未实测）。没有 Unicode 输入的原生通路。
- `launch --extra` 只透传字符串 extra，不支持 `--ei` / `--ez` / `--el` 等类型。
- 不管理应用权限（grant/revoke），权限验证走 `install -g` + UI 或 adb 直连。
- 不做网络断言（iperf/端口连通性），后续单独扩展。

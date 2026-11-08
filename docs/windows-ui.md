# Windows 壳 · 十页 Web UI（已接真 daemon）

- 结论：P0–P9 十页全部实现，纯静态（HTML + 手写 CSS + 原生 JS，零依赖、零 CDN）。
  数据不再来自内存假数据，而是 **daemon 的 REST + WebSocket**（`docs/daemon-api.md`）。
- 公开种子（磁力导入 / 公开条目 / 本机移除）已接进 P3 / P4，口径与文案同 Android：
  「公开」「等待来源」「本机文件」「磁力链接」「移除」。
- 链接分享（W1）已接进 P4 / P8 / P9：P4「…」→「分享链接」创建 7 天链接并进 P8，
  二维码由页面本地编码并画成 SVG（`js/qrcode.js`，不出网）；P7「分享管理」→ P9 列表与撤销。
- 一条命令出结果：`bash scripts/build-windows.sh` → 编译 → 起一个带料的 daemon →
  逐页截图 → 16 张门禁逐张断言，再补 3 张公开种子截图（同一套离屏截图与断言）。
- P8 / P9 的两张截图单独跑：`bash windows/scripts/capture-share-ui.sh`（会真向 Worker 登记
  一条链接，所以不放进构建门禁，见 §6.4）。
- 壳只负责窗口与 WebView2：启动时拉起 daemon、退出时带走它，业务逻辑一行都不在 `main.cpp` 里。

## 1. 怎么起、怎么连

### 1.1 只开浏览器（最快）

```bash
./gradlew :daemon:daemonJar
java -jar daemon/build/dist/daemon.jar          # 固定 127.0.0.1:47200
```

浏览器打开 **http://127.0.0.1:47200/** —— 页面就是 daemon 自己发的那份，同源，不需要任何额外服务。
建群、加文件、评论、激活/休眠全部直接落到 daemon（数据在 `<data-dir>`，默认 `%LOCALAPPDATA%\Huchuan\daemon`）。

开发时想改完立刻看到效果（不用重打 jar）：

```bash
./gradlew :daemon:daemonJar
java -jar daemon/build/dist/daemon.jar --web-root=windows/resources/web
```

改 `windows/resources/web` 下的文件，刷新浏览器即可。

### 1.2 开壳（原生窗口）

```bash
bash scripts/build-windows.sh --no-evidence     # 编译
windows/build/Huchuan.exe                       # 双击或命令行都行
```

壳启动后做三件事，然后才导航：

1. 探 `127.0.0.1:47200`。已经在跑 → 直接复用（退出时也不会去关它，那是别人的进程）。
2. 没在跑 → 按 `--daemon-jar=` → 环境变量 `HUCHUAN_DAEMON_JAR` → exe 同级（`daemon.jar` 或
   `daemon\daemon.jar`）→ `%LOCALAPPDATA%\Huchuan\daemon\daemon.jar` → 源码树
   `daemon/build/dist/daemon.jar` 的顺序找 jar；java 按 **exe 同级 `runtime\bin\javaw.exe`** →
   `%JAVA_HOME%\bin` → PATH 的顺序找。找到就以 `--port=47200` 拉起，并把它绑进一个
   `KILL_ON_JOB_CLOSE` 的 job object。
3. 导航到 `http://127.0.0.1:47200/index.html`。

绿色版（`dist/Huchuan/`）靠第 2 步的头两项自洽：exe 同级既有 `daemon.jar` 也有 `runtime/`，
所以机器上装没装 Java 都一样，见 `docs/daemon-api.md` §8。

退出时先 `POST /api/shutdown` 让它落盘收尾，3 秒没退就让 job 统一带走 —— 壳崩溃或被杀也一样，
不会留孤儿 daemon。

找不到 jar 或 Java 时，壳退回 exe 内嵌的那份页面；页面自检会显示「网络不可用」，点「重试」会重新连。

### 1.3 调试参数

浏览器加在 URL 上，exe 里是同名命令行开关（正常启动一个都不带）：

| 参数 | 取值 | 作用 |
| --- | --- | --- |
| `route` | `p0`…`p9`；取证别名 `p3-magnet` / `p4-public` / `p3-personal` / `p3-acquiring` / `p4-acquiring` / `p3-parked` / `p8-share` / `p9-list` | 直接落到某一页，跳过开屏 |
| `fixture` | `parked` | 页面夹具：把文件列表换成一条 `kernelState=parked` 的条目（只给 `p3-parked` 截图用，见 §6.5） |
| `theme` | `light` / `dark` | 强制主题（默认跟随系统） |
| `network` | `pass` / `limited` / `fail` | 强制自检结论；不带 = 用真实自检（daemon 在不在） |
| `hold` | `boot` / `checking` / `result` | 把 P0 停在某个阶段，供截图 |
| `pick` | `1` | 进 P3 后直接弹一次系统文件选择器（跳过「添加文件」弹层），用来无人值守地验壳桥 |
| `api` | `http://host:port` | 浏览器里指定 daemon 地址（跨源场景 / 换端口调试） |
| `manual` | `host:port`（逗号分隔） | 加入群时的手动候选，填对方的消息层端口 |

这些别名只给截图取证用，正常导航不带（见 §6.3 / §6.4 / §6.5）：`p3-magnet` 落到 P3 的磁力输入，
`p4-public` 落到 P4 的第一条公开条目；`p8-share` / `p9-list` 会先确保 daemon 里有一条有效分享
（没有就用第一个有文件的群的第一个私有文件建一条 7 天的），再落到 P8 / P9。

`p3-personal` / `p3-acquiring` / `p4-acquiring` / `p3-parked` 钉的是**落哪个群**：个人空间
（`isPersonal`）/ 有公开条目的那个群。深链默认落「有文件的第一个群」（见 §3.5），个人空间常常是空的，
不钉死就会截出一张空列表。页面名仍是 `p3` / `p4` / `p8` / `p9`，所以截图任务表的
`page` / `--shot-page` 不用改。

壳自己的开关（不属于页面参数，正常双击一个都不带）：`--daemon-jar=`、`--daemon-port=`、
`--daemon-data-dir=`（给测试用独立数据目录，别动 `%LOCALAPPDATA%\Huchuan\daemon`）。

取证专用的离屏截图开关（只有壳认，页面不认；用法见 §5.1）：

| 参数 | 取值 | 作用 |
| --- | --- | --- |
| `--shot` | PNG 绝对路径 | 离屏渲染一张图写盘后退出（WebView2 `CapturePreview`），不抓屏 |
| `--shot-page` | `p0`…`p9` | 等到 `document.body.dataset.page` 等于这一页再截；只决定等哪一页，落到哪一页仍由 `--route=` 决定 |
| `--shot-delay` | 毫秒，默认 1200 | 导航完成后最少等多久再截（留出首帧、字体、接口回来） |
| `--shot-timeout` | 毫秒，默认 12000 | 等 `--shot-page` 的上限，超时就按当前页截并记一条告警 |

例：`http://127.0.0.1:47200/?route=p3&theme=dark`；exe：`Huchuan.exe --route=p3 --theme=dark`；
离屏截一张 P4 深色：`Huchuan.exe --route=p4 --shot-page=p4 --theme=dark --shot=D:\tmp\p4.png`。

## 2. 目录

```
windows/resources/web/
  index.html          壳页面：一个 #app 容器 + 脚本，不含业务
  css/tokens.css      颜色 / 间距 / 字号令牌，浅深两套（取值同 Android Theme.kt）
  css/app.css         版面与组件（列表、chip、开关、进度、抽屉、二维码、溢出菜单、开屏）
  js/data.js          daemon 客户端：Api（REST/WS）+ Store（同步读 + 异步写）
  js/qrcode.js        本地二维码编码器（字节模式 / M 级 / 版本 1–10；只出矩阵与 SVG，不出网）
  js/app.js           路由 + 十页视图 + 事件（文案表 COPY 在文件顶部）
windows/scripts/
  dev-server.mjs      开发用静态服务器（node 内置模块，零依赖；只在不想起 daemon 时用）
  capture-ui.ps1      逐页截图取证：离屏截图是门禁，GDI 抓图降级为兜底诊断（纯 ASCII，见 §5 的坑）
  ui-capture-jobs.json 截图任务表：页名 / 路由 / 主题 / 断言文案
  capture-team-ui.sh  组队通告 + 限速的三张取证图（起三个 daemon，见 §6.1）
  ui-capture-teammates.json 上面那三张的任务表（同一份 capture-ui.ps1，换 -JobsPath / -IndexName）
  capture-tracker-ui.sh  跨网发现关闭态的一张取证图（一个 daemon，见 §6.2）
  ui-capture-tracker.json 上面那张的任务表（用 --daemon-port 连取证装置）
  capture-share-ui.sh  链接分享两张：P8 / P9（一个 daemon，向 Worker 真登记一条链接，见 §6.4）
  ui-capture-share.json 上面两张的任务表（同一份 capture-ui.ps1，换 -JobsPath / -IndexName）
  click-navigate.ps1  点击链路冒烟（可选，不进构建门禁）
scripts/build-windows.sh   构建 + 内嵌 + 起取证 daemon + 截图
scripts/package-windows.sh 绿色版打包：jlink runtime + exe + jar → dist/Huchuan/（见 docs/daemon-api.md §8）
scripts/m1-windows.sh      双 daemon 端到端验收（daemon 侧验收，见 docs/daemon-api.md §9）
```

## 3. 数据层：data.js 怎么接的

两层，职责单一：

- **Api**：只有 `fetch`。所有端点在这里点名一次，别处不拼 URL。页面由 daemon 同源提供时用
  `location.origin`，由壳内嵌提供时用 `http://127.0.0.1:47200`（可用 `?api=` 覆盖）。
- **Store**：页面看到的门面。**读是同步的**（返回本地镜像），**写是异步的**（发请求 → 重拉快照）。
  镜像永远来自 `GET /api/state` 这一份快照，写操作完成后立刻重拉；WS 的 `state` / `tick`
  也触发重拉。

为什么读保持同步：`app.js` 是按同步 store 写的，几十处渲染不能改成 `async`。镜像只是
daemon 最近一次快照，任何写操作之后都会收敛回 daemon 的真实状态，不会出现「页面说自己改了、
后端其实没改」。

WS 只负责说「变了」，不搬数据：收到 `state`（有东西变了）或 `tick`（传输速率/进度变了）
就防抖 120 ms 重拉一次 `/api/state`。

链接分享是**第二份镜像**：它不在 `/api/state` 里，所以 P8 / P9 打开时每 2 s 拉一次
`GET /api/shares`（离开这两页立刻停），其余页面照旧只吃 WS。P8 的状态行从「等待访客」
走到「传输中 42%」靠的就是这次轮询。

### 3.1 重绘不打断输入

传输中 `tick` 每秒来一次，页面会整页重绘。`render()` 在画之前记下焦点元素 id 与光标位置，
画完再还回去；草稿（`State.draftComment`）本来就存在状态里。所以正在打字时被刷新不会丢字、
不会掉焦点。

### 3.2 添加文件：一个入口两项

P3 工具栏仍然只有一个「添加文件」按钮；点开是一个弹层，两项（同 Android 的 `AddFileSheet`）：

| 项 | 做什么 |
| --- | --- |
| 本机文件 | 走壳的系统选择器；路径回传后 `POST /api/groups/{gid}/files` 建私有种子 |
| 磁力链接 | 同一弹层里换成输入框 +「导入」；`POST /api/groups/{gid}/files/import-magnet` |

**本机文件**的路径只能由壳给（浏览器拿不到 `<input type=file>` 的真实路径）：

    点「本机文件」
      → window.chrome.webview.postMessage({type:'pick-file'})
      → 壳 PostMessage(WM_APP+1) 回消息循环，开 IFileOpenDialog（模态）
      → 选中：{type:'file-picked', path:'C:\\…\\a.mp4'}
        取消：{type:'pick-file-cancelled'}
      → 页面拿 path 调 daemon，成功后按 spec 流程 4 直接进 P4

- 壳这一侧：`main.cpp` 的 `OnWebMessageReceived` → `PickFileAndReply`。对话框只收真实文件
  （`FOS_FORCEFILESYSTEM | FOS_FILEMUSTEXIST | FOS_PATHMUSTEXIST`），和 daemon 那头的校验对齐。
- 页面这一侧：`app.js` 的 `Shell`（`window.chrome.webview` 存在才有）、`pickLocalFile`、`pickFile`、
  `addPickedFile`。壳桥不在（普通浏览器里）时点「本机文件」只显示「只能在互传窗口里选文件」。
- 日志：壳的 `shell.log` 里一进一出两条对称记录 —— `页面消息：{"type":"pick-file"}`、
  `回页面：{"type":"file-picked",…}`，排查时直接 grep `pick-file`。
- 失败（daemon 拒绝、文件被删、权限不够）把 daemon 的 `error` 原文显示在工具栏下面。

**磁力链接**：输入框占位「粘贴磁力链接」，右侧「导入」。导入中按钮变「导入中」，输入框与弹层
都不许关；内核最多等 60 s 元数据（与 Android 同口径，`timeoutMs=60000`）。三种结果分开处理：

- `ok=true`：元数据到手，条目建好，直接进 P4。
- `ok=false`（`no_source` / `timeout` / `bad_metadata`）：条目**已经建好**，内核那一句话留在输入框
  下面（不染红）；关掉弹层后 P3 里该条目显示「公开 + 等待来源」，元数据到手后随 `/api/state`
  刷新自动变成尺寸/下载状态。不弹窗、不要求重试。
- 请求失败（`invalid_link` / `duplicate` / 公开会话 503）：显示 daemon 的 `error` 原文并染红。

弹层没有标题与关闭按钮：点空白处或 `Esc` 关闭；选「本机文件」时先收起弹层再弹系统选择器。
取证参数 `pick=1` 跳过弹层直接弹选择器（见 §1.3）。

P3 页面本身没有输入框：`Enter` 打开「添加文件」弹层；弹层里在磁力输入框按 `Enter` = 导入。

### 3.3 公开、组队、限速与跨网发现：页面读的是哪一份数

这几处都容易「显示一个漂亮的假数」，所以来源写死在这里：

`data.js` 的 `toFile` 透传公开条目的 `scope` / `magnet` / `sizeKnown`（`scope` 缺字段按
`private`，`sizeKnown` 缺字段按 `true`，与 daemon-api 的旧对端口径一致），以及内核态的
`kernelState` / `diskBytes`（缺字段按空串 / 0 —— 老 daemon 照样能跑）。页面只看这些字段，
不按名字或状态猜。

| 界面上的 | 数据来源 | 判定 |
| --- | --- | --- |
| P3 行内 chip「公开」 | `file.scope` | 仅 `public` 出现，固定在状态 chip 左侧；私有条目没有「私有」补位 chip |
| P3/P4 状态 chip | `file.kernelState`（六值） | 见下面的映射表；`kernelState` 缺字段 / 认不出的写法才退回 `state` 三值的老口径 |
| P3/P4 状态「等待来源」 | `kernelState === 'waiting_metadata'`（老 daemon 退回 `scope` + `sizeKnown`） | `fileAwaitingSource()` 也参与：公开且尺寸未知；尺寸整段不显示（不是 0 B） |
| P4 激活开关 | `fileActive()` | 在传（`downloading` / `seeding`）**或 `acquiring`** 都算「开」；`parked` / `waiting_metadata` / `absent` 是「关」 |
| P4「已落盘」 | `file.diskBytes` | 只在 `kernelState=downloading` 且 `diskBytes > 0` 时出现一行；做种（与尺寸/100% 重复）与休眠（过时的数）都不出现 |
| P4 磁力摘要 | `file.magnet` | 公开且磁力非空；只显示 `btih:` 前 16 位，点一下复制整条 |
| P4「移除」 | 仅在公开条目出现 | `POST /api/groups/{gid}/files/{fid}/remove`：只藏本机列表，不广播、不删盘 |
| P3 行内 chip「组队 n 人」 | `file.teammates`（daemon 直读 `kernel.teammates`） | `data.js` 的 `fileInTeam`：队友 ≥ 1 且进度 < 100；n = 队友数 + 1（含本机） |
| P4「正在组队 · n 人」 | 同上 | 同一个 `fileInTeam`，进度到 100% 的同一拍消失 |
| P7「内核读回」 | `settings.sessionUploadLimitKbps` / `sessionDownloadLimitKbps` | daemon 下发内核后读回的**会话真值**；0 显示「不限」 |
| P7 输入框 | `settings.uploadLimitKbps` / `downloadLimitKbps` | 用户设的值，写 `POST /api/settings/limits` |
| P4 单文件限速读回 | `file.uploadLimitKbps` + `file.uploadLimitInForce` | 已生效 / 已设置；core 没有按 torrent 读回限速的口子（daemon-api 缺口 G9） |
| P7「跨网发现」开关 | `settings.trackerEnabled` | 落盘的设置；关掉 = 只走局域网 mDNS 与手动地址 |
| P7 只读地址 / 「重启后生效」 | `settings.trackerBaseUrl` / `trackerRestartRequired` | 地址由 daemon 配（默认 `https://huchuan-a9f.pages.dev`，Pages 入口）；内核只在启动时装配 tracker，改完要重启进程才生效 |

「不限」开关与输入框是同一件事的两面：开关打开 → 两项都按 0 下发（输入框同时置灰，与 Android 一致）；
输入框里给出非 0 值 → 开关自动关掉（daemon 侧就是这么解释的，见 `docs/daemon-api.md` §3.1）。
页面不缓存限速：每次重绘都从 `/api/state` 取，所以别人（脚本 / 另一台）改了限速，这里也会跟着变。

跨网发现同理：开关写 `POST /api/settings/tracker`，页面不自己记状态。关掉时 P7 的「网络状态」说
「仅局域网」；如果设置与内核现状差一次重启，开关下面多一行「重启后生效」（daemon 的
`trackerRestartRequired`），不把「已点的值」冒充成「已经生效」。

#### 状态 chip：`kernelState` 六值映射

`state` 只有三个值，「导入后放着等来源」与「用户点了激活、内核正在找源」在它里面都是 `sleeping`；
要说出两句不同的话只能看 `kernelState`（`docs/daemon-api.md` §3.3、`docs/core.md` §3.12）。
判定在 `app.js` 的 `fileStateLabel()` / `fileActive()` 两个纯函数里，P3 的行 chip 与 P4 的
chip、开关共用它们：

| `kernelState` | P3/P4 状态 chip | 激活开关 | 什么时候出现 |
| --- | --- | --- | --- |
| `absent` | 休眠（公开条目且尺寸未知时仍说「等待来源」） | 关 | 不在会话里：休眠、或元数据到手后内核把导入任务摘掉 |
| `parked` | 休眠 | 关 | 会话里有 torrent 但被 libtorrent 挂着（`paused`）；正常流程走不到，见 §6.5 |
| `waiting_metadata` | 等待来源 | 关 | 导入后没激活、内核在等来源（`state` 也是 `sleeping`） |
| `acquiring` | 正在寻找来源 | **开** | 用户点了激活、元数据还没到手（`state` 仍是 `sleeping`、`role` 仍是 `none`、字节 0） |
| `downloading` | 下载中 | 开 | 真在传；n% 在同一行的进度条旁（`progressPercent`） |
| `seeding` | 做种中 | 开 | 数据完整（或已进做种集合） |

两条口径上的取舍：

- **n% 不进 chip**。chip 只写「下载中」，百分比在行内进度条旁 / P4 的速率行里（与 Android 的
  `fileStateLabel()` 一致：`p3_state_downloading` 也是「下载中」，`42%` 是另一个文本）。
  spec §3 的「下载中 42%」说的是整块状态区，不是 chip 的字面。
- **`acquiring` 期间开关不弹回休眠**。内核读数仍是「没在传」（`state=sleeping`、字节 0），
  但用户刚拨过开关，弹回去就是「点了激活没反应」（`docs/core.md` §3.12）。所以
  `fileActive()` 把 `acquiring` 也算「开」，P4 的 `aria-checked` 与 P3 chip 的高亮都读它。

### 3.4 链接分享（W1）：P4 → P8 → P9

入口与出口（与 Android 同构，`docs/design/link-share.md` §3）：

- **P4「…」→「分享链接」**：`POST /api/shares {fileId, expireDays: 7}` → 进 P8（`#/p8/<token>`）。
  创建中停在同一页显示「创建中」，失败显示一句话 +「重试」，不弹窗。
- **P7 抽屉「分享管理」**（右侧是有效链接条数）→ P9（`#/p9`）；P9 点行 → 该链接的 P8，撤销在 P9。
- 分享服务不可用（`GET /api/shares` 的 `available=false`，例如缺 WebRTC 原生库）时，这两处入口都不出现，
  与 Android 的 `linkAvailable` 同口径；直接深链到 P8 会显示「没有这条分享」。

页面读的是 daemon 的分享对象，字段一个不改名（`docs/daemon-api.md` §3.6）：

| 界面上的 | 数据来源 | 判定 |
| --- | --- | --- |
| P8 二维码 | `share.qrText` | 与 `link` 同值；`js/qrcode.js` 本地编成模块矩阵、页面画 SVG，不调用任何在线二维码服务 |
| P8 链接文本 /「复制链接」 | `share.link` | `navigator.clipboard`，失败退回 `execCommand`；「已复制」在行内留 1.6 s |
| P8 状态行 | `share.status` + `share.progressPercent` | `waiting` 等待访客 · `connecting` 访客已加入 · `transferring` 传输中 N% · `done` / `failed` / `revoked` / `expired` / `file-missing` 已结束（带原因） |
| P8 剩余有效期 | `share.expiresAt`（参考 `createdAt`） | 向上取整：N 天后 / N 小时后 / N 分钟后失效，过了就是「已失效」。`createdAt` 只用来兜住 Worker 时钟快一两秒的情况，不让刚建好的链接显示「8 天」 |
| P9 列表的一行 | `GET /api/shares` 的 `shares[]` | 只排除 `status` 是 `revoked` / `expired` / `file-missing` 的；`done` / `failed` 仍显示（链接还能再给下一位访客取） |
| P9 文件名 / 被访问 n 次 | `share.fileName` / `share.transfers` | 文件名创建时定下，列表不重新读盘；次数是 daemon 记的取件完成次数（不是 Worker 的 visitCount） |
| P9 撤销 | `DELETE /api/shares/{token}` | 不可逆破坏动作，允许一次确认；回包 `remoteRevoked=false` 只表示服务端还没同步，本机记录已经撤销 |

二维码为什么自己写编码器：要编的是一条约 60 字节的固定字符串，为它多一个构建依赖与一份第三方许可
不划算。`js/qrcode.js` 是字节模式、纠错等级 M、版本 1–10 的标准实现；真实链接 59 字节落在
**版本 4（33×33）**，与设计里「版本 3–4」一致。独立验证见 §6.4。

P4 的「…」里只有「分享链接」一项：单文件限速在 Windows 是页内控件（§8 第 11 条），不重复收进菜单；
公开 / 取消公开这两个动作 Windows 端还没做（§9）。

分享不在 `/api/state` 里，所以 P8 / P9 打开时每 2 s 拉一次 `/api/shares`，离开就停（见 §3 结尾）。

端到端证据：`artifacts/link-share-win/e2e/`（真 Worker + 真 WebRTC：daemon 发 8 MiB，Edge headless
打开 Pages 接收页收字节并自己算 SHA-256，两个 SHA 与字节数都对得上）；界面这条链路的证据见 §6.4。

已知边界（设计口径，不是缺陷）：

- **同时 1 个访客**（Worker 房间的硬上限）。链接可以先后给多个人，`transfers` 累加；同一文件被建成
  两条链接时 daemon 也只放一个访客进来（另一条记 `file-busy`）。
- 浏览器接收页单文件上限 **1 GiB**（OPFS / 内存降级），更大的走 App。
- **没有 TURN**：对称 NAT / CGNAT 下打洞失败会如实显示「无法直连」。
- 分享期间 daemon 要在线；进程退出链接变 offline。有效期上限 7 天，过期不续期。

### 3.5 个人空间（我的分享）

`docs/design/personal-space.md` 已经落到 Windows 页面：`/api/state` 的第一个群是
`isPersonal=true` 的个人空间（32 字符 gid、`inviteCode` 是空串、`memberCount` 恒 1）。
页面只认 `isPersonal` 这一位，不看名字、不看成员数（`docs/daemon-api.md` §3.2）。

| 界面上的 | 数据来源 | 判定 |
| --- | --- | --- |
| P2 置顶的「我的分享」 | `group.isPersonal` | `orderedGroups()` 把它排到最前；行上多一枚上传图标 + 左边一道品牌色竖线（`.row.is-personal`），与普通群分开 |
| P2 不显示成员数 | —— | P2 的行本来只写「做种 n（· 组队 n）」，个人空间同样不写成员数 |
| P3 进个人空间 | 深链 `?route=p3-personal`（取证）；正常从 P2 点进去 | 标题就是群名「我的分享」；添加文件 / 磁力导入 / 分享链接与普通群同一套代码 |
| P6 邀请码 | `group.inviteCode` | 个人空间不生成邀请码（daemon 给空串），P6 的邀请码整块不出现，不是显示一个空框 |
| P5 加入失败 | `E_JOIN_PERSONAL` / `E_JOIN_FAILED` | 对端明确说「我是个人空间」→「对方是个人空间，不能被加入」；其余仍是「邀请码不对」 |
| 深链默认落点 | `defaultGroup()` | **有文件的第一个群**；一个都没有才退回第一个群 —— 个人空间刚建时是空的，直接落上去就是一张空列表 |

正常导航不受影响：P2 点哪个群就进哪个群。深链 / 取证默认跳过空的个人空间，需要它时用
`p3-personal` 钉死（§1.3）。分享链接的入口仍在 P4「…」，个人空间的文件照样能建链接。

## 4. 内嵌：资源表怎么来的（现在只是兜底）

daemon 起不来时壳回退到 exe 内嵌的那份页面，所以内嵌这条路仍然保留。
`windows/CMakeLists.txt` 用 `file(GLOB_RECURSE … CONFIGURE_DEPENDS)` 扫 `resources/web/` 下的
html / css / js / svg / png / woff2，按路径排序后：

1. 资源 ID 从 101 起顺序分配；
2. 生成 `build/app.rc`（`<id> RCDATA "<绝对路径>"`，模板是 `resources/app.rc.in`）；
3. 生成 `build/assets.generated.h`：`{请求路径, 资源 ID, Content-Type}` 一张表。

`main.cpp` 里只有一处路由判断 —— `FindAsset(path)` 查这张表，未命中就 404；它只拦截
`https://huchuan.invalid/*`，`http://127.0.0.1:47200` 的请求直接走网络，壳不参与。

| 要做的事 | 改哪里 |
| --- | --- |
| 加 / 删 / 改名一个页面文件 | 只动 `windows/resources/web/`，重新构建即可（daemon 那边也会打进 jar） |
| 加一类新扩展名（如 `.webp`） | `CMakeLists.txt` 的 `huchuan_content_type()` 加一行 |
| 改某个文件的 Content-Type | 同上 |

## 5. 构建与验收

```bash
bash scripts/build-windows.sh                # 编译 + 起取证 daemon + 16 张门禁截图 + 3 张公开种子补充图
bash scripts/build-windows.sh --no-evidence  # 只编译
bash scripts/build-windows.sh --clean        # 先清 windows/build
bash scripts/package-windows.sh              # 绿色版：jlink runtime + exe + jar → dist/Huchuan/
bash windows/scripts/capture-team-ui.sh      # 组队通告 + 限速的三张取证图（三个 daemon，见 §6.1）
bash windows/scripts/capture-tracker-ui.sh   # 跨网发现关闭态的一张取证图（一个 daemon，见 §6.2）
bash windows/scripts/capture-share-ui.sh     # P8 / P9 两张（会真登记一条 7 天链接，见 §6.4）
bash windows/scripts/capture-kernelstate-ui.sh  # 内核态 acquiring / parked + 个人空间五张（见 §6.5）
```

四道关，任一不过脚本退出非 0：

1. **构建**：MSVC + Ninja + /MT 静态 CRT，产物 `windows/build/Huchuan.exe`。
2. **绿色版**：exe 同级不许有 dll；导入表只允许系统 dll（ADVAPI32 / GDI32 / KERNEL32 / SHELL32 /
   SHLWAPI / USER32 / WS2_32 / ole32）。离屏截图用的 WIC（`windowscodecs`）是 COM 运行时装起来的，
   只把 CLSID 编进 exe，导入表里不会多一行。
3. **取证 daemon**：脚本自己起一个 daemon（`artifacts/windows/daemon/`，端口 47200），
   建一个群「老板的群」、共享一个 1 MiB 文件、发一条评论，再导入一条**无来源的公开磁力**
   （随机 infohash + `timeoutMs=0` → `scope=public`、`sizeKnown=false`）。没有它，P2 是「还没有群组」，
   P3/P4 会直接退回首页，逐页断言就没有对象了；公开条目缺席则 §6.3 的三张图没有对象。
   用户名故意留空，冷启动才会落在 P1。
   **jar 过期就重打，不能只看它存不存在**：页面由这个 daemon 从 jar 里发出，jar 是旧的时候
   门禁照样能出一堆「合格」的图，判的却是旧页面 —— 所以脚本比 mtime：`daemon/src/**`、
   `core/src/**`、`windows/resources/web/**`、`daemon/build.gradle.kts`、
   `core/build.gradle.kts`、根 `build.gradle.kts` / `settings.gradle.kts` / `gradle.properties`、
   `gradle/libs.versions.toml` 任一比 `daemon.jar` 新（或 jar 不存在）就先跑 `:daemon:daemonJar`，
   增量仍然交给 gradle，脚本只做这一个粗粒度判断；是复用还是本次重建，
   `artifacts/windows/build-report.md` 里会记一行。
4. **渲染**：`capture-ui.ps1` 按 `ui-capture-jobs.json` 逐页跑一个一次性 exe
   （`--shot=<png> --shot-page=<页>`，出图方式见 §5.1），每张图必须同时满足
   - 运行日志里出现截图那一刻的探针 `DOM 探针(shot)：page=<页>| …`，且正文包含该页断言文案；
   - PNG 像素非空白（颜色数 ≥ 8；没渲染出来的那一帧只有 1 种颜色），
     浅色平均亮度 ≥ 140、深色 ≤ 120。

逐张证据写进 `artifacts/windows/ui/index.json`（页名、断言文案、出图方式 `mode=offscreen`、
进程退出码、尝试次数、尺寸、颜色数、主色占比、亮度、探针原文）。每页最多重跑 3 次（`-Attempts`）。

门禁跑完后再跑一轮**公开种子补充截图**（§6.3）：同样是 `capture-ui.ps1` + 一次性 exe，任务表由
`build-windows.sh` 现场生成 `public-capture-jobs.json`，索引单独写 `public-index.json`，不挤进那 16 张。
脚本另外按屏 grep 探针原文（`公开` / `等待来源` / `移除` / `btih:` / `导入`），像素断言照旧。

两个已经踩过的坑，改这里之前先看一眼：

- **rc.exe 不追踪 RCDATA 引用的文件**。不声明依赖时，改了 css/js，ninja 认为 `app.rc` 没变，
  exe 里还是旧页面 —— 症状是「改了没反应」。修法是
  `set_property(SOURCE app.rc APPEND PROPERTY OBJECT_DEPENDS ${文件列表})`，已在 CMakeLists 里。
- **`capture-ui.ps1` 必须纯 ASCII**。PowerShell 5.1 用 ANSI 代码页解码无 BOM 的脚本，
  中文注释会把下一行吃掉，表现为「脚本逻辑明明对，就是不生效」。中文一律放在
  `ui-capture-jobs.json` 和 daemon 的数据里。

另有一个**不进构建门禁**的点击链路冒烟（坐标按 DPI 换算，窗口位置或点击时序变化时可能误报）：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File windows/scripts/click-navigate.ps1 \
  -ExePath "$(cygpath -w windows/build/Huchuan.exe)" -OutDir "$(cygpath -w artifacts/windows/ui/nav)"
```

### 5.1 为什么截图不抓屏

2026-11 实测：这个桌面会话根本不渲染 —— 整屏 `CopyFromScreen` 抓 3840×2160 只有 **1 种颜色**；
同一个 exe 走 GDI 抓窗口也是 `colors=1`、主色 100%，而那一页的 DOM 探针（页名 + 文案）完全正常。
显示器关闭、显示模式变化、远程会话断开都会造成这种状态，跟页面好坏无关。
旧门禁把「GDI 截图非空白」当判据，在这种环境下必然全红 —— 凡是依赖"屏幕上有东西"的取证都不会绿。

所以出图走壳自己的一条路：`Huchuan.exe --shot=<png>` 让 **WebView2 渲染进程合成当前页面**，
用 `ICoreWebView2CapturePreview` 直接拿到 PNG，写盘后退出。它跟屏幕内容、窗口层级、
窗口是否在前台/置顶都没有关系，桌面不渲染照样出图。每个页面一个一次性进程，
时序是：导航完成 → 轮询 `document.body.dataset.page` 等目标页 → 打一条 `DOM 探针(shot)` →
截图 → 壳自己用 WIC 把 PNG 解一遍（颜色数、主色占比、平均亮度）自检 → 合格写盘退出，
不合格隔 400 ms 重来，最多 6 次。

`capture-ui.ps1` 里还留着老的 GDI 抓窗口那条路（`CopyFromScreen`），但它**不是门禁**：
上面那种桌面环境下它只会返回一块纯色，而它唯一的价值是"窗口上到底画了什么"（标题栏、
层级、遮挡），所以只在离屏截图失败时跑一次，产物是 `<页名>.gdi.png` + `<页名>.gdi.log`，
记进 `index.json` 的 `gdi` 字段，页面的 `ok` 不会因此变真。

对应的断言阈值两边共用一套：颜色数 ≥ 8（空白帧 = 1 种颜色），浅色平均亮度 ≥ 140、深色 ≤ 120。
历史阈值是"颜色数 ≥ 32"，那是给带窗口边框的 GDI 抓图定的；离屏出图没有窗口边框，
P0 开屏、P1 用户名这种大半是底色的页面抽样只有 29–39 种颜色、主色占到 99.6%，
32 会把好图判成空白 —— 门槛因此下调到 8，并把"主色占比"留作证据而不是判据。

## 6. 截图清单

构建门禁的 `artifacts/windows/ui/` 共 16 张，每张一个独立进程；同一轮构建还会补 3 张公开种子图
（§6.3，索引单独一份 `public-index.json`）。另有 §6.1 / §6.2 / §6.4 / §6.5 四个脚本产的 11 张也落在
同一目录（P8 / P9 要真登记一条链接，内核态 / 个人空间那五张要另一套装置，都不进构建门禁）。
涉及数据的断言写的是取证 daemon 那份料的字面：

| 文件 | 页 | 状态 | 断言文案 |
| --- | --- | --- | --- |
| `cold-start.png` | P1 | 不带任何参数冷启动，自检放行后自动进入（零点击） | 最多 20 字 |
| `p0-boot.png` | P0 | 启动中 | 正在启动 |
| `p0-check.png` | P0 | 检测中 | 正在检测网络 |
| `p0-pass.png` | P0 | 可互传（自动放行前） | 可互传 |
| `p0-degraded.png` | P0 | 仅局域网可用 | 仅局域网可用 |
| `p0-failed.png` | P0 | 网络不可用，只有「重试」 | 网络不可用 |
| `p1-username.png` | P1 | 用户名（预填设备名） | 最多 20 字 |
| `p2-home.png` | P2 | 首页：搜索 / 设置 / ＋ / 群组行 | 老板的群 |
| `p3-group.png` | P3 | 群组：添加文件 / 文件列表（含一条公开条目）/ 成员入口 | 添加文件 |
| `p4-file.png` | P4 | 文件详情：进度、开关、限速、评论 | 单文件限速 |
| `p5-new-group.png` | P5 | 新群组：邀请码后 8 位 / 加入 / 新建群组 | 新建群组 |
| `p6-members.png` | P6 | 成员列表 + 邀请码 | 邀请码 |
| `p7-settings.png` | P7 | 设置抽屉：用户名 / 全局限速 / 通知 / 跨网发现 / 分享管理 / 网络状态 | 全局限速 |
| `p2-home-dark.png` | P2 | 深色 | 老板的群 |
| `p4-file-dark.png` | P4 | 深色 | 单文件限速 |
| `p7-settings-dark.png` | P7 | 深色 | 全局限速 |

同名 `.log` 是该次运行被截断后拷出的完整壳日志（含资源命中、导航、`nav`/`settled`/`late`/`shot`
四条 DOM 探针、以及 `离屏截图：第 N 次尝试 …` / `离屏截图完成：…` 两行统计）。
图本身是 1650×1140（窗口 1100×760 DIP × 150% 缩放，离屏出图按进程 DPI 渲染），不含窗口边框。

### 6.1 组队通告与限速的三张（另一个脚本）

`bash windows/scripts/capture-team-ui.sh` 单独跑一套三进程装置：S（47201）做种、C1（47200）与
C2（47202）同时下同一个 32 MiB 文件，两个下载者全局限速 128 KB/s，截图截的是 C1。

为什么非要三个进程：spec §306 的队友数只数「真互连、**不是做种者**、且本机仍在『部分』」的成员 ——
「一端做种、一端下载」永远数出 0。C1 先入群、C2 后入群，S 收到 C2 的 `peer.announce` 后转发给 C1，
C1 按成员拨号拨通 C2，`teammates` 从 0 变 1，界面上的 n = 队友数 + 1 = 2。

| 文件 | 页 | 状态 | 断言文案 |
| --- | --- | --- | --- |
| `p3-team.png` | P3 | 文件正在下、队友 1 人 | 组队 2 人 |
| `p4-team.png` | P4 | 「正在组队 · 2 人」+ 单文件限速 512 KB/s 已生效 + 「已落盘 n KB」（下载中才有，§3.3） | 正在组队 · 2 人 |
| `p7-limits.png` | P7 | 全局限速 上行 512 / 下行 128，含内核读回 | 上行 512 KB/s |

断言分两层：`capture-ui.ps1` 那套（页名 + 文案 + 像素）照旧；脚本另外断言 API 侧的事实 ——
C1 的 `teammates` 变成 1、`teamSize` = 2、`sessionUploadLimitKbps` 读回 512、单文件限速回包
`uploadLimitInForce=true`，再从 `p4-team.log` / `p7-limits.log` 里 grep「已生效 512 KB/s」
「内核读回 上行 512 KB/s」。截图与 `teammates-index.json` 落在同一个 `artifacts/windows/ui/`，
同名 `.log` 是那一页的完整壳日志（含 `DOM 探针(shot)` 原文）。

### 6.2 跨网发现（关闭态）的一张

`bash windows/scripts/capture-tracker-ui.sh` 起一个专用 daemon（数据目录 `artifacts/windows/tracker/`），
里面在启动前就写了 `trackerEnabled=false`；建一个群，然后离屏截 P7：

| 文件 | 页 | 状态 | 断言文案 |
| --- | --- | --- | --- |
| `p7-tracker.png` | P7 | 跨网发现关闭：开关关、地址只读、网络状态「仅局域网」 | 仅局域网 |

脚本先断言 API 与日志侧的事实：`/api/settings/tracker` 读回 `enabled=false active=false`、
`kernel_started` 里 `tracker=off`、建群后没有 `tracker_service_started` / `tracker_register` /
`tracker_announce`；截图那一步再断言 DOM 探针里有「跨网发现」「仅局域网」和地址原文，
PNG 与 `tracker-index.json` 落在 `artifacts/windows/ui/`。

为什么不用 47200：那是 daemon 的默认端口，同机可能已经被别的服务或别的 agent 的 daemon 占着。
这条取证用 47260，让壳通过 `--daemon-port=47260`（任务表的 `daemonPort` 字段）连它；
daemon 还用 `--device-name=Tracker-Off` 认领 health，避免把占着同一端口的别人进程当成自己的。

### 6.3 公开种子三张（构建脚本的后半段）

`bash scripts/build-windows.sh` 在 16 张门禁之后，用同一个取证 daemon 再跑一轮离屏截图。
daemon 里那条公开条目是随机 infohash + `timeoutMs=0` 导入的：没有来源，但条目已建好，
`scope=public`、`sizeKnown=false`，正好是「等待来源」那一态。

| 文件 | 页 | 状态 | 断言文案 |
| --- | --- | --- | --- |
| `p3-public.png` | P3 | 公开文件行：状态 chip 左侧「公开」，右侧「等待来源」，不写 0 B | 等待来源 + 公开 |
| `p4-public.png` | P4 | 公开条目：公开 / 等待来源、磁力摘要（点按复制）、右上「移除」 | 移除 + 公开 + btih: |
| `p3-magnet.png` | P3 | 「添加文件」弹层的磁力链接态：粘贴磁力链接 + 导入 | 导入 |

两张 P3/P4 用取证**路由别名**落到指定状态（见 §1.3）：`p4-public` 让 P4 选中本群第一条公开
条目（普通深链靠 `?file=<fid>`）；`p3-magnet` 让 P3 首次渲染就带着磁力输入弹层。页面名仍是
`p3` / `p4`，所以 `--shot-page` 与 DOM 探针不受影响。

任务表由构建脚本现场写到 `artifacts/windows/ui/public-capture-jobs.json`（源码里没有第四份任务表），
断言分两层：`capture-ui.ps1` 的页名 + 文案 + 像素照旧；脚本另外 grep 截图那一刻的探针原文，
把「公开」「等待来源」「移除」「btih:」「导入」逐条钉住（文件名「远山」不含「公开」，不会误命中）。
索引是 `public-index.json`，三张都 `mode=offscreen`、`failed=0` 才算过；16 张门禁的 `index.json`
不受影响。

### 6.4 链接分享两张（P8 / P9，单独跑，不进构建门禁）

**为什么不进门禁**：这两张要真的向 Pages 的 Worker 登记一条 7 天有效链接（`POST /api/shares`），
而构建不该依赖外网与 pages.dev 的可用性 —— pages.dev 一抖，整条 `build-windows.sh` 就红。
所以门禁保持 16 张，这两张由独立脚本跑、单独出 `share-index.json`。

```bash
bash windows/scripts/capture-share-ui.sh
```

装置是一个专用 daemon（127.0.0.1:47270，`--device-name=Share-Evidence`）：一个群 + 一个 1 MiB
私有文件。两个任务用取证别名落到页面（§1.3）：`p8-share` 先确保 daemon 里有一条有效分享
（没有就用本群第一个私有文件建一条 7 天的），再落 P8；`p9-list` 复用同一条（同一个 daemon），落 P9。
收尾会把这条链接 `DELETE` 掉（`remoteRevoked=true`），不在 Worker 上留 7 天记录。

| 文件 | 页 | 状态 | 断言文案 |
| --- | --- | --- | --- |
| `p8-share.png` | P8 | 本地二维码（版本 4）+ 链接 + 复制链接 + 7 天后失效 + 等待访客 | 复制链接 |
| `p9-list.png` | P9 | 一行：文件名 / 7 天后失效 · 被访问 0 次 / 撤销 | 被访问 |

两张与门禁那 16 张走同一套离屏截图 + DOM / 像素断言；同名 `.log` 里有截图那一刻的
`DOM 探针(shot)` 原文。脚本另外断言 API 与日志侧的事实：`/api/shares` 的 `available=true`、
只创建了一条（`link_share_created` 恰好 1 行）、P8 页面里的 link 与 daemon 日志里的 link 逐字相同、
列表里 `status=waiting transfers=0`、撤销回包 `remoteRevoked=true`。

另有三条可重跑的机读证据：

- `bash artifacts/windows/share-ui-check.sh`：Edge headless + CDP 真实点击链路 —— P4「…」→
  「分享链接」→ P8（二维码 / 链接 / 复制 / 有效期 / 状态）→ 返回 P2 → P7「分享管理」→ P9
  （文件名 / 有效期 / 被访问次数 / 撤销）→ 点行回 P8 → 返回 → 撤销（一次确认）→ 行消失；
  再验 `?route=p9-list` / `?route=p8-share` 两个别名。日志 `artifacts/windows/share-ui-check.log`。
- `bash artifacts/windows/qr-check/run.sh`：编码器本身的独立验证 —— 6 个用例（版本 1 / 2 / 4 / 10、
  UTF-8、213 字节上限）画成 PNG 交给 ZXing 3.5.3 解码，与原文逐字比。
- `bash artifacts/windows/qr-shot-check.sh`：从 `p8-share.png` 里把二维码解出来，和取证 daemon
  日志里 `link_share_created` 的 link 逐字比 —— 同一张图、同一条链接。

**网络依赖**：pages.dev 不可达（或本机没有 WebRTC 原生库）时这个脚本会明确失败，16 张门禁截图
不受影响（门禁本身不碰外网）。这条链接只在脚本运行的这一两分钟里有效，跑完即撤销。

### 6.5 内核态两态 + 个人空间两态（另一个脚本）

`bash windows/scripts/capture-kernelstate-ui.sh`

**先跑门禁再跑它**：`build-windows.sh` 出图前会 `rm -rf artifacts/windows/ui`（避免拿上次的图冒充本次），
§6.1 / §6.2 / §6.4 的脚本同理 —— 它们都落在同一个目录，跑完门禁再跑。

装置是一个专用 daemon（127.0.0.1:47280，`--device-name=KernelState-Evidence`）：个人空间里放一个
64 KiB 文件；普通群「老板的群」里放一个 64 KiB 文件 + 一条**没有来源**的公开磁力（随机 infohash +
`timeoutMs=0`），再对它 `POST …/active {active:true}` —— 回包 `kernelState=acquiring`、
`state=sleeping`、`role=none`、`downloadedBytes=diskBytes=0`，正好是「点了激活、元数据还没到手」那一段。

| 文件 | 页 | 状态 | 断言文案 |
| --- | --- | --- | --- |
| `p2-personal.png` | P2 | 「我的分享」固定置顶（图标 + 品牌色竖线）+ 普通群 | 我的分享 + 老板的群 |
| `p3-personal.png` | P3 | 个人空间：标题「我的分享」、文件行、成员（1 人） | 我的分享 + travel-photos.zip |
| `p3-acquiring.png` | P3 | 公开行「公开 + 正在寻找来源」，不写 0 B | 正在寻找来源 |
| `p4-acquiring.png` | P4 | chip「正在寻找来源」+ 激活开关保持「已激活」，没有「已落盘」 | 正在寻找来源 + 激活 休眠 |
| `p3-parked.png` | P3 | `kernelState=parked` 的行仍说「休眠」 | 休眠 |

落点用取证别名钉死（`p3-personal` / `p3-acquiring` / `p4-acquiring`，见 §1.3）：深链默认落
「有文件的第一个群」，而个人空间刚建时是空的。

`parked` 那一态**没有任何 API 造得出来**：磁力导入元数据一到就摘任务（`absent`），点激活是
`acquiring` / `downloading`，私有文件挂进会话就直接做种。core 的 `GroupRuntime.kernelState` 读得到它
（daemon 也照实投成 `paused=true`），但没有哪一步能让它停在这。所以这一张走取证别名 `p3-parked` 的
页面夹具（`app.js` 的 `fixtureGroup()`）：只在 `?route=p3-parked` / `?fixture=parked` 时把文件列表换成
一条与 daemon 同口径的 `parked` 条目，正常启动永远不生效。

断言分两层：`capture-ui.ps1` 的页名 + 文案 + 像素照旧（五张都 `mode=offscreen`）；脚本另外跑一遍
Edge headless + CDP（`windows/scripts/kernel-state-probe.mjs`）读回页面侧的事实 —— P3 chip 的文案与
`is-on`、P4 开关的 `aria-checked="true"`、六个 `kernelState` 值的映射表（`fileStateLabel()` /
`fileActive()` 逐值对一遍）、P2 第一行是不是个人空间、P3 个人空间的标题与文件行。索引是
`kernelstate-index.json`，与门禁那 16 张互不影响。

## 7. 页面与文案

文案表在 `windows/resources/web/js/app.js` 顶部（`COPY`），逐条对应
`docs/design/product-spec.md` §3 各页「文案」行。改文案的顺序：先改 spec，再改 `COPY`，
再跑一次 `bash scripts/build-windows.sh` 让截图断言跟着更新（16 张门禁的断言文案在
`ui-capture-jobs.json`；公开种子三张的任务表由 `build-windows.sh` 现场生成，见 §6.3；
P8 / P9 两张在 `ui-capture-share.json`，见 §6.4；内核态 / 个人空间五张在
`ui-capture-kernelstate.json`，见 §6.5）。

P7 的「跨网发现」「仅局域网」「重启后生效」是本轮新增的三条 Windows 文案，spec §3 P7 的元素表
与文案行还没列进去（`docs/design/` 不归本任务改）；合并时由设计 agent 补进 spec，以免两处各说一套。

P8 / P9 的文案取自 Android（`app/src/main/res/values/strings.xml` 的 `p8_*` / `p9_*`），只有两处
Windows 口径：传输态是「传输中 42%」（Android 是「正在发送 42%」），终态一律从「已结束」起头
（「已结束」「已结束 · 链接已撤销」「已结束 · 链接已过期」…），对应本轮的「已结束」需求。
P8 没有做 Android 的那一次性 toast（「对方可见你的网络地址」，见 §9 第 9 条）。

导航与状态全部纯 JS：`#/p0`…`#/p9` 是路由，返回栈在 `Nav` 里，不依赖浏览器历史。

## 8. 与 Android 端的差异

| # | 项 | Android | Windows |
| --- | --- | --- | --- |
| 1 | 返回 | 系统返回键（BackHandler） | 没有系统返回键：页头 `←`、`Esc`、`Alt+←`；P0 / P1 / P2 无返回 |
| 2 | P7 设置 | 覆盖在 P2 上的 Drawer，无独立目的地 | 右侧抽屉 + 路由 `#/p7`（为了深链和逐页截图）；关闭或返回都回 P2 |
| 3 | P0「无权限」态 | 有（本地网络未授权，拉起系统授权） | 桌面没有本地网络授权模型，不实现；失败与降级按 spec 文案「网络不可用」「仅局域网可用」 |
| 4 | 本机成员平台 | `Android` | `Windows` |
| 5 | P3 文件列表 | 两行 ListItem（名称 / 大小·做种·进度 + 状态 chip） | 列对齐：名称+进度条 / 大小 / 做种 n / 状态 chip；窗口窄于 900px 折回单列 |
| 6 | P4 版面 | 单栏纵向 | 双栏：左传输控制（进度、激活开关、单文件限速），右评论列表 + 输入行 |
| 7 | 添加文件 | SAF `OpenDocument` | 同一个「添加文件」弹层两项：本机文件走壳的 `IFileOpenDialog`（绝对路径回传，浏览器拿不到）；磁力链接在弹层里输入并导入。导入成功都直接进 P4 |
| 8 | 复制邀请码 | ClipboardManager + Snackbar | `navigator.clipboard`，失败退回 `execCommand`；「已复制」在行内显示 1.6 秒 |
| 9 | 主题 | `isSystemInDarkTheme()` | `prefers-color-scheme`；窗口标题栏与 WebView2 首帧底色跟着页面主题走 |
| 10 | 键盘 | 无 | `Enter` 提交当前页主操作（保存 / 加入 / 发送 / 创建；P3 上是打开「添加文件」弹层，磁力输入框里是导入），`Esc` 关弹层 / 抽屉或返回 |
| 11 | 限速读数 | 下发后只写日志（`global_limit_applied … ok=`），界面显示用户设的值 | P7 多一行「内核读回」、P4 多一行「已生效 / 已设置」，直接把会话里生效的值显示出来 |
| 12 | 公开种子 | P3「公开」chip、P4 磁力摘要 +「移除」 | 同样三处，文案一致。磁力摘要点按复制，提示是行内「已复制」1.6 秒（Android 是 Snackbar）；移除只藏本机列表，成功后回 P3 |
| 13 | P8 二维码 | zxing 出 BitMatrix + Compose Canvas | `js/qrcode.js` 自写编码器（字节模式 / M 级 / 版本 1–10）+ SVG；用同一个 zxing 3.5.3 在构建机外解码验证（§6.4） |
| 14 | P8「分享链接」按钮 | 拉起系统分享菜单（ACTION_SEND） | 就是「复制链接」：Windows 没有系统分享菜单，设计里这个出口就落成剪贴板（link-share.md §3 P8） |
| 15 | P9 撤销 | AlertDialog 一次确认 | 同款 scrim + modal 一次确认；确认后行立刻消失，服务端没同步时本机照样算撤销（`remoteRevoked=false`） |
| 16 | P8 / P9 刷新 | 控制器的 StateFlow（信令回调里推进） | `GET /api/shares` 每 2 s 轮询（分享不在 `/api/state`），离开两页就停；状态行的「等待 → 传输中 → 已结束」都靠它 |
| 17 | P4「…」 | 菜单：公开 / 取消公开 + 分享链接 | 菜单只有「分享链接」：单文件限速在页内（第 11 条），公开 / 取消公开 Windows 未实现（§9） |
| 18 | 个人空间 | 本轮未接（`isPersonal` 还没进 UI） | P2 置顶「我的分享」+ 图标 + 品牌色竖线、P6 隐藏邀请码、P5 区分 `E_JOIN_PERSONAL`（§3.5） |
| 19 | 状态 chip | 三种状态 + `searchingSource`（`ACQUIRING` →「正在寻找来源」） | 直接读 `kernelState` 六值（§3.3 的映射表）；`parked` 与 `waiting_metadata` 也能分开说 |

## 9. 当前限制

数据是真的，但下面这些还没到位，页面上的表现都写在括号里：

1. **没有未读数**（首页「N 条新消息」永远不出现）。core 没给 unread 来源，见 daemon-api 缺口 G4。
2. ~~没有组队~~ **已接**：P3 的「组队 n 人」chip 与 P4 的「正在组队 · n 人」都读 `file.teammates`（内核观测口径，做种者不计入），
   见 §3.3 与 §6.1。剩下的边界在 core：同一台机器上跑多个成员（`127.0.0.1` 多端口）时
   「连接 ↔ 成员」只能按 host 退化配对；局域网一台设备一个 IP 的常态下没问题。
3. **别人的做种数与平台是 0 / 「未知」**，只有本机是真数。缺口 G3。
4. **群只能建、不能退**。core 没有退出群的 API。缺口 G1。
5. **评论发出去不能删**。缺口 G6。
6. **绿色版自带 JRE**：`dist/Huchuan/` 里带着 jlink 裁出来的 `runtime/`（27.43 MiB），目标机
   不用装 Java。只有在既没有 `runtime/` 又没装 Java 的机器上（比如直接跑 `windows/build/Huchuan.exe`），
   壳才退回内嵌页面并显示「网络不可用」。打包见 `docs/daemon-api.md` §8。
7. **跨网发现已接，但没有打洞与端口映射**：默认开、地址是已部署的 Pages 入口
   `https://huchuan-a9f.pages.dev`（P7 只读展示）；
   关掉只走局域网 mDNS + 手动地址，P7 的网络状态显示「仅局域网」。私有群的 infohash 不进
   DHT / PEX（协议 §8），tracker 只收 groupHash20。开关与地址只在 daemon 启动时装配，P7 改了
   要重启进程才生效（页面显示「重启后生效」）；pages.dev 不可达时注册会失败，局域网不受影响。
8. **无鉴权**：daemon 只监听 127.0.0.1，本机任何进程都能调它的 API。M1 接受这个前提。

9. **链接分享已接页面，但仍是「分享者必须在线」的直连**：P8 / P9 的入口、二维码、撤销都通了（§3.4），
   边界沿用设计口径 —— 同时 1 个访客、没有 TURN（对称 NAT 下显示「无法直连」）、接收页单文件上限 1 GiB、
   daemon 退出后链接变 offline。另外两处 Windows 未做：P8 没有 Android 的一次性 toast
   （「对方可见你的网络地址」，需要的话是一次 toast + localStorage 标记，不影响其它状态）；
   P4「…」里没有「公开 / 取消公开」两项（公开条目目前从磁力导入，见 §8 第 17 条）。

10. ~~acquiring 期间点「休眠」不会真停下~~ **已修**（core/daemon 提交 c664256）：daemon 的
    `POST …/files/{fid}/active {active:false}` 改为按 `kernelState` 判定，`acquiring` 时调
    `PublicBtSession.cancelAcquire` 同步摘掉找源任务并释放公开会话，回包带 `stopped` 与前后 `kernelState`。
    P4 的开关在 `acquiring` 时仍显示「已激活」（§3.3），但点它现在真的能停下来。

## 10. 体积

| 项 | 字节 |
| --- | --- |
| `index.html` | 984 |
| `css/tokens.css` | 2,573 |
| `css/app.css` | 22,061 |
| `js/data.js` | 30,625 |
| `js/qrcode.js` | 15,916 |
| `js/app.js` | 83,146 |
| **页面资源合计** | **155,305**（151.7 KiB，预算 200 KB） |
| `Huchuan.exe` | 458,752（WebView2 运行时是系统组件，不在 exe 内） |
| `daemon.jar` | 18,091,562（含 core、libtorrent4j 的 x86_64 dll、WebRTC 原生包、jmdns、上面这份页面） |
| `runtime/`（jlink） | 28,766,257（27.43 MiB，模块 `java.base,java.logging,java.net.http,jdk.crypto.ec`） |
| **`dist/Huchuan` 合计** | **47,316,511**（45.12 MiB，按当前 exe + jar + runtime 相加；`dist/` 里还是 11-06 打的那份，本轮没重打） |

2026-11-07 读数：链接分享把 webrtc-java 1.0.0 的原生包（DLL 22.8 MB）打进 jar，daemon.jar 从 8.1 MiB
涨到 17.22 MiB；runtime 因为 `java.net.http` 多 0.55 MiB。页面这一轮为 P8 / P9 增加了 `qrcode.js`
（15.9 KB）与分享页样式、数据接口，页面资源从 96.7 KiB 涨到 140.0 KiB。
2026-11-08 读数：状态 chip 改读 `kernelState`（§3.3）+ 个人空间置顶与图标（§3.5），页面资源
从 140.0 KiB 涨到 151.7 KiB（`app.js` +9.7 KB、`data.js` +1.8 KB、`app.css` +0.4 KB）。

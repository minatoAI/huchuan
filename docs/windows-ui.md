# Windows 壳 · 八页 Web UI（已接真 daemon）

- 结论：P0–P7 八页全部实现，纯静态（HTML + 手写 CSS + 原生 JS，零依赖、零 CDN）。
  数据不再来自内存假数据，而是 **daemon 的 REST + WebSocket**（`docs/daemon-api.md`）。
- 一条命令出结果：`bash scripts/build-windows.sh` → 编译 → 起一个带料的 daemon → 逐页截图 → 16 张逐张断言。
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
2. 没在跑 → 按 `--daemon-jar=` → 环境变量 `HUCHUAN_DAEMON_JAR` → `%LOCALAPPDATA%\Huchuan\daemon.jar` →
   exe 同级 `daemon.jar` → 源码树 `daemon/build/dist/daemon.jar` 的顺序找 jar，
   用 `javaw.exe`（`%JAVA_HOME%\bin` 优先，其次 PATH）以 `--port=47200` 拉起，
   并把它绑进一个 `KILL_ON_JOB_CLOSE` 的 job object。
3. 导航到 `http://127.0.0.1:47200/index.html`。

退出时先 `POST /api/shutdown` 让它落盘收尾，3 秒没退就让 job 统一带走 —— 壳崩溃或被杀也一样，
不会留孤儿 daemon。

找不到 jar 或 Java 时，壳退回 exe 内嵌的那份页面；页面自检会显示「网络不可用」，点「重试」会重新连。

### 1.3 调试参数

浏览器加在 URL 上，exe 里是同名命令行开关（正常启动一个都不带）：

| 参数 | 取值 | 作用 |
| --- | --- | --- |
| `route` | `p0`…`p7` | 直接落到某一页，跳过开屏 |
| `theme` | `light` / `dark` | 强制主题（默认跟随系统） |
| `network` | `pass` / `limited` / `fail` | 强制自检结论；不带 = 用真实自检（daemon 在不在） |
| `hold` | `boot` / `checking` / `result` | 把 P0 停在某个阶段，供截图 |
| `api` | `http://host:port` | 浏览器里指定 daemon 地址（跨源场景 / 换端口调试） |
| `manual` | `host:port`（逗号分隔） | 加入群时的手动候选，填对方的消息层端口 |

例：`http://127.0.0.1:47200/?route=p3&theme=dark`；exe：`Huchuan.exe --route=p3 --theme=dark`。

## 2. 目录

```
windows/resources/web/
  index.html          壳页面：一个 #app 容器 + 两个脚本，不含业务
  css/tokens.css      颜色 / 间距 / 字号令牌，浅深两套（取值同 Android Theme.kt）
  css/app.css         版面与组件（列表、chip、开关、进度、抽屉、开屏）
  js/data.js          daemon 客户端：Api（REST/WS）+ Store（同步读 + 异步写）
  js/app.js           路由 + 八页视图 + 事件（文案表 COPY 在文件顶部）
windows/scripts/
  dev-server.mjs      开发用静态服务器（node 内置模块，零依赖；只在不想起 daemon 时用）
  capture-ui.ps1      逐页截图取证（纯 ASCII，见 §5 的坑）
  ui-capture-jobs.json 截图任务表：页名 / 路由 / 主题 / 断言文案
  click-navigate.ps1  点击链路冒烟（可选，不进构建门禁）
scripts/build-windows.sh 构建 + 内嵌 + 起取证 daemon + 截图
scripts/m1-windows.sh    双 daemon 端到端验收（daemon 侧验收，见 docs/daemon-api.md §9）
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

### 3.1 重绘不打断输入

传输中 `tick` 每秒来一次，页面会整页重绘。`render()` 在画之前记下焦点元素 id 与光标位置，
画完再还回去；草稿（`State.draftComment`）本来就存在状态里。所以正在打字时被刷新不会丢字、
不会掉焦点。

### 3.2 添加文件收的是「本机绝对路径」

浏览器出于安全拿不到 `<input type=file>` 的真实路径，而 `POST /api/groups/{gid}/files` 要的
就是路径。所以 P3 的工具栏是一个路径输入框 + 「添加文件」按钮（Enter 也能提交），
失败时把 daemon 的 `error` 原文显示在输入框下面。

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
bash scripts/build-windows.sh                # 编译 + 起取证 daemon + 16 张截图（约 55 秒）
bash scripts/build-windows.sh --no-evidence  # 只编译
bash scripts/build-windows.sh --clean        # 先清 windows/build
```

四道关，任一不过脚本退出非 0：

1. **构建**：MSVC + Ninja + /MT 静态 CRT，产物 `windows/build/Huchuan.exe`。
2. **绿色版**：exe 同级不许有 dll；导入表只允许系统 dll（ADVAPI32 / GDI32 / KERNEL32 / SHELL32 /
   SHLWAPI / USER32 / WS2_32 / ole32）。
3. **取证 daemon**：脚本自己起一个 daemon（`artifacts/windows/daemon/`，端口 47200），
   建一个群「老板的群」、共享一个 1 MiB 文件、发一条评论。没有它，P2 是「还没有群组」，
   P3/P4 会直接退回首页，逐页断言就没有对象了。用户名故意留空，冷启动才会落在 P1。
4. **渲染**：`capture-ui.ps1` 按 `ui-capture-jobs.json` 逐页启动 exe，每张图必须同时满足
   - 运行日志里出现该页的稳定态探针 `DOM 探针(settled)：page=<页>| …`，且正文包含该页断言文案；
   - 截图窗口像素非空白（颜色数 ≥ 32），浅色平均亮度 ≥ 140、深色 ≤ 120。

逐张证据写进 `artifacts/windows/ui/index.json`（页名、断言文案、窗口尺寸、颜色数、亮度、探针原文）。

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

## 6. 截图清单

`artifacts/windows/ui/` 共 16 张，每张一个独立进程。涉及数据的断言写的是取证 daemon 那份料的字面：

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
| `p3-group.png` | P3 | 群组：添加文件 / 文件列表 / 成员入口 | 添加文件 |
| `p4-file.png` | P4 | 文件详情：进度、开关、限速、评论 | 单文件限速 |
| `p5-new-group.png` | P5 | 新群组：邀请码后 8 位 / 加入 / 新建群组 | 新建群组 |
| `p6-members.png` | P6 | 成员列表 + 邀请码 | 邀请码 |
| `p7-settings.png` | P7 | 设置抽屉：用户名 / 全局限速 / 通知 / 网络状态 | 全局限速 |
| `p2-home-dark.png` | P2 | 深色 | 老板的群 |
| `p4-file-dark.png` | P4 | 深色 | 单文件限速 |
| `p7-settings-dark.png` | P7 | 深色 | 全局限速 |

同名 `.log` 是该次运行被截断后拷出的完整壳日志（含资源命中、导航、三次 DOM 探针）。

## 7. 页面与文案

文案表在 `windows/resources/web/js/app.js` 顶部（`COPY`），逐条对应
`docs/design/product-spec.md` §3 各页「文案」行。改文案的顺序：先改 spec，再改 `COPY`，
再跑一次 `bash scripts/build-windows.sh` 让截图断言跟着更新（断言文案在 `ui-capture-jobs.json`）。

导航与状态全部纯 JS：`#/p0`…`#/p7` 是路由，返回栈在 `Nav` 里，不依赖浏览器历史。

## 8. 与 Android 端的差异

| # | 项 | Android | Windows |
| --- | --- | --- | --- |
| 1 | 返回 | 系统返回键（BackHandler） | 没有系统返回键：页头 `←`、`Esc`、`Alt+←`；P0 / P1 / P2 无返回 |
| 2 | P7 设置 | 覆盖在 P2 上的 Drawer，无独立目的地 | 右侧抽屉 + 路由 `#/p7`（为了深链和逐页截图）；关闭或返回都回 P2 |
| 3 | P0「无权限」态 | 有（本地网络未授权，拉起系统授权） | 桌面没有本地网络授权模型，不实现；失败与降级按 spec 文案「网络不可用」「仅局域网可用」 |
| 4 | 本机成员平台 | `Android` | `Windows` |
| 5 | P3 文件列表 | 两行 ListItem（名称 / 大小·做种·进度 + 状态 chip） | 列对齐：名称+进度条 / 大小 / 做种 n / 状态 chip；窗口窄于 900px 折回单列 |
| 6 | P4 版面 | 单栏纵向 | 双栏：左传输控制（进度、激活开关、单文件限速），右评论列表 + 输入行 |
| 7 | 添加文件 | SAF `OpenDocument` | 本机绝对路径输入框（浏览器拿不到 `<input type=file>` 的真实路径）；两者都直接落内核 |
| 8 | 复制邀请码 | ClipboardManager + Snackbar | `navigator.clipboard`，失败退回 `execCommand`；「已复制」在行内显示 1.6 秒 |
| 9 | 主题 | `isSystemInDarkTheme()` | `prefers-color-scheme`；窗口标题栏与 WebView2 首帧底色跟着页面主题走 |
| 10 | 键盘 | 无 | `Enter` 提交当前页主操作（保存 / 加入 / 发送 / 创建 / 添加文件），`Esc` 关闭抽屉或返回 |

## 9. 当前限制

数据是真的，但下面这些还没到位，页面上的表现都写在括号里：

1. **没有未读数**（首页「N 条新消息」永远不出现）。core 没给 unread 来源，见 daemon-api 缺口 G4。
2. **没有组队**（「组队 N 人」的 chip 走不到）。缺口 G5。
3. **别人的做种数与平台是 0 / 「未知」**，只有本机是真数。缺口 G3。
4. **群只能建、不能退**。core 没有退出群的 API。缺口 G1。
5. **评论发出去不能删**。缺口 G6。
6. **JRE 要自己装**：壳找 `JAVA_HOME` 或 PATH 里的 `javaw.exe`；没有 Java 时退回内嵌页面并显示
   「网络不可用」。jlink 打包方案写在 `docs/daemon-api.md` §8，本轮不做。
7. **跨网还没开**：只走局域网 mDNS + 手动地址，没有 tracker、没有打洞、没有端口映射窗口。
8. **无鉴权**：daemon 只监听 127.0.0.1，本机任何进程都能调它的 API。M1 接受这个前提。

## 10. 体积

| 项 | 字节 |
| --- | --- |
| `index.html` | 947 |
| `css/tokens.css` | 2,573 |
| `css/app.css` | 15,494 |
| `js/data.js` | 16,481 |
| `js/app.js` | 38,649 |
| **页面资源合计** | **74,144**（预算 200 KB） |
| `Huchuan.exe` | 344,064（WebView2 运行时是系统组件，不在 exe 内） |
| `daemon.jar` | 8,033,932（含 core、libtorrent4j 的 x86_64 dll、jmdns、上面这份页面） |

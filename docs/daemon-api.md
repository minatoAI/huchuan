# daemon API（Windows 桌面控制面）

互传 Windows 端的业务全在这个 daemon 进程里：它内嵌 core 内核（协议、群、BT 会话、评论日志），
对外只暴露 **127.0.0.1 上的一个端口**，HTTP 给 REST，同一端口上的 WebSocket 给状态推送。
原生壳（`windows/src/main.cpp`）只负责窗口与 WebView2，页面也是由 daemon 同源提供的。

- 监听：`127.0.0.1:47200`（**固定端口**，写进这里就是约定）
- 协议：HTTP/1.1（一律 `Connection: close`）+ WebSocket（RFC 6455 的 text/ping/close 子集）
- 编码：JSON，UTF-8；数字一律整数（协议 §5 禁浮点）
- 鉴权：无。只监听回环地址，信任本机用户；不做 token、不做 TLS

---

## 1. 起 daemon

```bash
# 单文件 jar（自带 core、libtorrent4j、jmdns、Web UI 静态资源）
./gradlew :daemon:daemonJar
java -jar daemon/build/dist/daemon.jar

# 或者直接跑
./gradlew :daemon:run

# 绿色版里不用系统 java：dist/Huchuan/runtime/bin/java.exe 就是 daemon 的运行时（见 §8）
dist/Huchuan/runtime/bin/java.exe -jar dist/Huchuan/daemon.jar
```

启动成功后 stdout 会打一行机读的就绪信号，脚本可以直接等它：

    daemon_ready api_port=47200

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `--data-dir=PATH` | `%LOCALAPPDATA%\Huchuan\daemon` | 数据目录：`state.json`（core）、`settings.json`（daemon）、`downloads\`、`native\libtorrent4j.dll`、`daemon.log`、`daemon.port` |
| `--port=N` | `47200` | HTTP/WS 端口。`0` = 随机（脚本用；同机跑两个实例时必须不同） |
| `--msg-port=N` | `47100` | 消息层端口提示。被占用时内核会自己往上找，实际端口见 `/api/health` 的 `msgPort` |
| `--bt-port=N` | `51413` | BT 端口提示，同上 |
| `--device-name=NAME` | `COMPUTERNAME` | 本机设备名 |
| `--web-root=PATH` | jar 内 `web/` | 开发时指到 `windows/resources/web`，改完刷新页面就生效 |
| `--log=PATH` | `<data-dir>/daemon.log` | 日志文件；`-` 表示不写文件 |
| `--restore-seeds=N` | `1` | 启动后把本机共享过、原文件还在的文件重新做种；`0` 关掉 |

同机跑两个实例（`scripts/m1-windows.sh` 就是这么做的）：

```bash
java -jar daemon.jar --data-dir=A --port=47200 --msg-port=47300 --bt-port=51500 --device-name=A
java -jar daemon.jar --data-dir=B --port=47201 --msg-port=47310 --bt-port=51510 --device-name=B
```

### 为什么页面也由 daemon 提供

WebView2 里的页面如果从一个伪造源（`https://huchuan.invalid`）去 `fetch` `http://127.0.0.1`，
会撞上 Chromium 的混合内容与私有网络访问（PNA / LNA）检查。同源就没有这一层麻烦，
也免了 CORS 预检。所以 `GET /` 由 daemon 返回 `windows/resources/web` 下的同一份文件
（打包时由 `:daemon:daemonJar` 抄进 jar 的 `web/`），壳直接导航到 `http://127.0.0.1:47200/`。

跨源仍然可用（壳的内嵌兜底页要它）：所有响应都带

    Access-Control-Allow-Origin: *
    Access-Control-Allow-Private-Network: true

---

## 2. 响应包络

成功：

```json
{ "ok": true, "data": { } }
```

失败（HTTP 状态码同时给出 4xx/5xx）：

```json
{ "ok": false, "code": "E_NO_SUCH_FILE", "error": "找不到文件：C:\\nope.bin" }
```

`error` 是给人看的中文，`code` 是给程序判断的。已知 `code`：

| code | 含义 |
| --- | --- |
| `E_BAD_JSON` | 请求体不是 JSON 对象 |
| `E_METHOD` | 方法不支持 |
| `E_NOT_FOUND` | 路由不存在 |
| `E_INTERNAL` | 未预期异常（同时写日志 `api_error`） |
| `E_NO_KERNEL` | 内核还没起好（启动中的极短窗口） |
| `E_NO_GROUP` / `E_NO_FILE` | 群 / 文件不存在 |
| `E_INVITE_FORMAT` | 后 8 位不是 8 个字符 |
| `E_JOIN_FAILED` | 加入失败：码不对、对方没在跑、地址不可达 |
| `E_BAD_PATH` / `E_NO_SUCH_FILE` / `E_UNREADABLE` / `E_EMPTY_FILE` | 添加文件的四类拒绝 |
| `E_EMPTY_COMMENT` / `E_NO_PARENT` | 评论为空 / 回复的目标不见了 |
| `E_BAD_ACTIVE` | `active` 不是布尔 |
| `E_ACTIVATE_FAILED` | 内核里没有这个文件的种子信息 |
| `E_BAD_LIMIT` / `E_BAD_LEVEL` | 限速 / 通知档取值非法 |
| `E_BAD_TRACKER` | 跨网发现取值非法：`enabled` 不是布尔、`baseUrl` 不是 http(s)、或一项都没给 |
| `E_NOT_PUBLIC` | 想移除一个私有条目：私有条目只能休眠（`active=false`），「移除」只对公开条目开放 |
| `invalid_link` / `duplicate` / `session_error` | 磁力导入的请求级失败（400 / 409 / 503）：链接非法 / 同群已有这个种子 / 公开会话没起来。回包另带内核同名的 `message` 与 `fileId`，见 3.3 |

---

## 3. 端点

### 3.1 基础

#### `GET /api/health`

进程级探针，脚本用它等启动完成。

```json
{ "status": "ok", "version": "0.1.0", "pid": 12412, "apiPort": 47200,
  "msgPort": 47300, "btPort": 51500, "mdns": true, "groups": 2, "deviceName": "WIN11-0715" }
```

#### `GET /api/state`

**页面要的全部东西一次给完**：daemon 信息、本机设备、设置、群（含成员、文件、评论）。

```json
{
  "daemon": { "version": "0.1.0", "pid": 12412, "apiPort": 47200, "msgPort": 47300,
              "btPort": 51500, "dhtNodes": 0, "mdns": true, "startedAt": 1793921106898,
              "rev": 27, "wsClients": 1, "dataDir": "C:\\...", "logPath": "C:\\..." },
  "device": { "deviceId": "3ACYGKG4Q1Q9R2BP", "deviceName": "WIN11-0715",
              "userName": "我的设备", "displayName": "我的设备" },
  "settings": { "userName": "我的设备", "uploadLimitKbps": 0,
                "downloadLimitKbps": 0, "unlimited": true,
                "sessionUploadLimitKbps": 0, "sessionDownloadLimitKbps": 0,
                "trackerEnabled": true,
                "trackerBaseUrl": "https://huchuan-relay.autoai233.workers.dev",
                "trackerActive": true, "trackerRestartRequired": false },
  "groups": [ /* 见 3.2 的群对象；每个文件额外带 comments */ ]
}
```

`settings` 里有两组限速数：`uploadLimitKbps` / `downloadLimitKbps` 是**用户设的值**（落盘），
`sessionUploadLimitKbps` / `sessionDownloadLimitKbps` 是**内核读回值**（会话里真正生效的那个）。
两者平时相等；不等就说明下发没生效（daemon 日志有 `session_limit_applied … ok=false`）。

跨网发现的四项也在 `settings` 里：`trackerEnabled` / `trackerBaseUrl` 是落盘设置，
`trackerActive` 是内核此刻有没有在跑，`trackerRestartRequired` 表示两者差一次进程重启
（内核只在启动时装配 tracker，详见本节的 `/api/settings/tracker`）。

**为什么带评论**：Web UI 每次刷新只发一个请求。传输中页面每秒重绘，若再按文件拉评论，
请求数会跟着文件数涨。`/api/state` 是 UI 的唯一读口，评论就一起给；其余接口（`/api/groups`
列表、`/api/groups/{gid}/files`）不带评论，避免列表接口被放大。

#### `GET|POST /api/settings`

```json
GET  → { "settings": { "userName": "", "uploadLimitKbps": 0, "downloadLimitKbps": 0, "unlimited": true } }
POST { "userName": "我的设备" }          // 字段都可选，给几个改几个
```

落盘在 `<data-dir>/settings.json`。`userName` 截断到 20 字，限速 0 = 不限。
这里改到 `uploadLimitKbps` / `downloadLimitKbps` / `unlimited` 时同样会下发内核（见下一条），
但脚本要断言限速请直接走 `/api/settings/limits`：那条回包里带内核读回值。

跨网发现不在这条路由里改：它有独立的 `/api/settings/tracker`（见本节后面），
因为回包必须同时给出「内核此刻在不在跑」和「要不要重启才生效」，这两件这里没有。

#### `GET|POST /api/settings/limits`

全局限速（P7 的那两个输入框 + 「不限」开关）。

```json
GET  → { "limits": { "uploadLimitKbps": 512, "downloadLimitKbps": 128, "unlimited": false,
                     "sessionUploadLimitKbps": 512, "sessionDownloadLimitKbps": 128 } }

POST { "uploadKbps": 512, "downloadKbps": 128 }    // KB/s，0 = 不限；字段都可选
  → { "limits": { …同上… } }

POST { "unlimited": true }                         // 「不限」：两项都按 0 下发
```

- `session*Kbps` 是**内核读回值**：下发后立刻读会话里生效的限速（libtorrent 的 settings），
  不是把请求原样回显。读到的和要的不一样时日志有一行 `session_limit_applied … ok=false`。
- 运行期立即生效：走 libtorrent 的 `applySettings`，已经在传的 torrent 同样受约束；
  多个文件同时传共用这一份总预算（不是每个 torrent 各给一份）。
- 单文件限速是另一层（见 3.3 的 `upload-limit`），两层同时存在时每个 torrent 取更小的那个。
- 显式给了一个非 0 的 `uploadKbps` / `downloadKbps` 就等于「要限速」：`unlimited` 会一起被关掉，
  不用另发一次。只给 0 则只改值，不动开关。
- 校验：kbps 必须是 0–2097151 的整数（与 core 的 `saneKbps` 同界），否则 400 `E_BAD_LIMIT`；
  一项都没给也是 400。
- 落盘在 `settings.json`，重启后 daemon 建会话时按落盘值重新下发一次（内核不持久化限速）。

#### `GET|POST /api/settings/tracker`

跨网发现（P7 的开关 + 只读地址）。

```json
GET  → { "tracker": { "enabled": true,
                      "baseUrl": "https://huchuan-relay.autoai233.workers.dev",
                      "active": true, "restartRequired": false } }

POST { "enabled": false }        // enabled / baseUrl 都可选，给几项改几项
  → { "tracker": { …同上，active 仍是内核此刻的真值… } }
```

- `enabled`：布尔，默认 `true`。关掉 = 只走局域网 mDNS 与手动 IP:port；内核根本不装配
  tracker，一条请求都不会发。
- `baseUrl`：tracker 基址，默认是已部署的 Worker（`https://huchuan-relay.autoai233.workers.dev`）。
  给了就必须是 `http://` / `https://` 开头的地址，否则 400 `E_BAD_TRACKER`。
- `active`：**内核此刻**有没有 tracker 在工作（core 的 `kernel.trackerEnabled`），不是刚点的值。
- `restartRequired`：落盘设置与内核现状不一致。core 只在 `start` 时装配 tracker
  （`setTrackerBaseUrl` 在 start 之后会被忽略），所以 POST 只落盘：当前进程继续按启动时的
  配置跑，重启 daemon 后按新设置生效。界面据此多显示一行「重启后生效」。
- 落盘在 `settings.json` 的 `trackerEnabled` / `trackerBaseUrl`；daemon 启动时把它们交给内核
  （`enabled=false` 时传空串，core 因此连 tracker 服务都不建）。
- `/api/state` 的 `settings` 里也有这四个字段（`trackerEnabled` / `trackerBaseUrl` /
  `trackerActive` / `trackerRestartRequired`），页面不额外发请求。
- 日志：关掉时是 `event=tracker_disabled reason=base_url_empty`；开着时 `kernel_started` 里
  `tracker=on tracker_base=<地址>`，每个群一条 `tracker_started`，注册结果见
  `tracker_register` / `tracker_register_failed`。

#### `POST /api/shutdown`

异步停进程（先回包再退），壳退出时用它让 daemon 体面收尾。

#### `GET /api/logs?n=200`

`{"lines": ["ts=... event=...", ...]}`，最近 n 行（1–5000）。日志一行一件事，键值对，脚本能直接 grep。

---

### 3.2 群

群对象：

```json
{
  "id": "7N2PKKSCFVK0NZT7", "name": "老板的群",
  "inviteCode": "RC4ME4MDAQ2078EP", "inviteTail8": "AQ2078EP",
  "invitePretty": "RC4M-E4MD-AQ20-78EP", "fingerprint": "3D482SC7",
  "memberCount": 2, "fileCount": 1, "seedingCount": 1,
  "teamSize": 0, "unreadCount": 0,
  "notificationLevel": "bubble", "onlineCount": 1,
  "members": [ /* 见下 */ ],
  "files": [ /* 见 3.3 */ ]
}
```

成员对象：

```json
{ "dev": "3ACYGKG4Q1Q9R2BP", "name": "WIN11-0715", "online": true,
  "self": true, "seedingCount": 1, "platform": "Windows" }
```

`seedingCount` 只有本机是真数；`platform` 只有本机写 Windows，别人写「未知」——协议里没广播这两样（缺口 G3）。

`teamSize` 是群级的组队人数（含本机）：取本群**正在组队**的文件里最大的那一队，没有文件在组队就是 0。
「正在组队」= 本机处于该文件的「部分」且队友 ≥ 1（口径见 3.3 的 `teammates`）。

#### `GET /api/groups`

`{"groups": [群对象...]}`（不带评论）。

#### `POST /api/groups`

```json
{ "name": "老板的群" }        // 空名 → "<设备名>的群组"；最多 40 字
→ 201 { "group": 群对象 }
```

#### `GET /api/groups/{gid}`

`{"group": 群对象}`

#### `GET /api/groups/{gid}/members`

`{"members": [成员对象...]}`

#### `POST /api/groups/{gid}/notification`

```json
{ "level": "bubble" }         // bubble | in_app | muted
→ { "groupId": "...", "notificationLevel": "bubble" }
```

---

### 3.3 文件

文件对象：

```json
{
  "id": "2b3017a6c140cea19e2dcb4ec31c2e61b77abb43",
  "name": "travel-photos.zip", "sizeBytes": 4194304, "pieceSize": 65536,
  "addedAt": 1793921122426, "sourceMember": "WIN11-0715", "sourceDev": "3ACYGKG4Q1Q9R2BP",
  "mine": true, "state": "seeding", "role": "seeding",
  "progressPercent": 100, "complete": true, "seeders": 0, "teammates": 1,
  "uploadLimitKbps": 512, "uploadLimitInForce": true,
  "downloadRateBps": 0, "uploadRateBps": 0,
  "commentCount": 1, "savedPath": "E:\\...\\travel-photos.zip",
  "magnet": "magnet:?xt=urn:btih:...&dn=...&xl=...",
  "scope": "private", "sizeKnown": true,
  "comments": [ /* 只有 /api/state 和文件详情里有 */ ]
}
```

- `scope`：`private` / `public`（public-swarm §1.1）。公开条目走公网 swarm，磁力必填、
  不内联 .torrent；缺字段的旧对端一律按 `private` 处理。
- `magnet`：规范化后的磁力（`xt=urn:btih:<40 位小写 hex>` 打头，保留发布者自己的 dn / xl / tr）。
  私有条目也带（来自本机建种），公开条目就是群内广播的那一条。
- `sizeKnown`：尺寸是不是已知。公开条目在元数据到手前是 `false`，这时**整个 `sizeBytes` 字段
  不出现**（0 会被误读成「零字节文件」）；`pieceSize` 同样用 0 表示未知。元数据到手后内核本地
  回填 size / pieceSize（不改签名、不重新广播），下一次 `/api/state` 就能看到 `sizeKnown=true`。
- `state`：`seeding` / `downloading` / `sleeping`，是「画面上该显示什么」。
- `role`：`seeding` / `downloading` / `none`，是「内核里挂没挂这个文件」。两者在下载刚完成的那几百毫秒里
  可能不同步，daemon 统一按 `complete` 归一。
- `seeders`：当前连上的 peer 数（协议里没有「全群做种数」这个概念，缺口 G3）。
- `teammates`：**队友数**（产品规格 §306 的观测口径），直接来自 `kernel.teammates(gid, fid)`：
  当前与这个文件的 torrent 真互连、对端**不是做种者**、且本机仍处于「部分」的成员数。
  做种者不计入；本机到 100%（或没挂进会话）内核直接回 0。界面上的「n 人」= 这个数 + 1（含本机）。
  它没有对应事件：只随连接实况变化，daemon 每秒的传输采样把它一起算进 tick 签名，
  所以页面照样能在它变化的那一秒刷新。
- `uploadLimitKbps`：单文件限速的**设置值**（落盘，激活时套到 torrent 上）。
- `uploadLimitInForce`：这条限速此刻是不是真在生效 —— 文件挂在会话里、还没到 100%、且设了非 0 值。
  core 没有「读回某个 torrent 的限速」的口子（缺口 G9），所以这里只能给「设了值 + 会话里挂着」，
  不给一个假装是内核读数的数字。到 100% 时内核会把 torrent 限速清零，这个字段同时变 false。

#### `GET /api/groups/{gid}/files`

`{"files": [文件对象...], "fileCount": 1}`

#### `POST /api/groups/{gid}/files`

```json
{ "path": "C:\\Users\\me\\Videos\\纪录片 4K.mp4" }
→ 201 { "file": 文件对象 }
```

`path` 必须是**本机绝对路径**且是普通文件、非空。daemon 建 v1 私有种子并开始做种，
同时把路径记进 `settings.json`（重启后据此恢复做种，见 §6）。

这个路径由 Windows 壳的系统文件选择器给出（`IFileOpenDialog`），页面不自己拼 —— 见
`docs/windows-ui.md` §3.2。浏览器里没有那条通道，所以直接开浏览器时「添加文件」用不了。

#### `GET /api/groups/{gid}/files/{fid}`

`{"file": 文件对象, "comments": [评论...]}`

#### `POST /api/groups/{gid}/files/{fid}/active`

```json
{ "active": true }      // true = 激活（做种 / 下载），false = 休眠（从会话里摘掉）
→ { "file": 文件对象 }
```

激活时 daemon 自己判断方向：**本机共享的文件重新做种**（用 settings 里记的原路径，
同一文件 → 同一 infohash），**别人的文件才走下载**。core 的 startDownload 固定落
`<data-dir>/downloads`，不区分这两种情况（缺口 G7），所以这一步在 daemon 侧分流。
磁盘上的文件被改过（内容变了、infohash 不再是这个 id）会返回 409 `E_FILE_CHANGED`。

#### `POST /api/groups/{gid}/files/{fid}/upload-limit`

```json
{ "kbps": 8192 }        // 0 = 不限
→ { "file": 文件对象 }
```

单文件限速（P4 那一行），落在 `settings.json` 的 `uploadLimits`，激活时自动套用（`core` 的
`GroupRuntime.setUploadLimitKbps` → `torrent_handle::set_upload_limit`，单位 KB/s，0 = 不限）。
回包里的文件对象带 `uploadLimitKbps`（设的值）与 `uploadLimitInForce`（此刻是否真挂在会话里）；
core 没有按 torrent 读回限速的口子（缺口 G9），所以不给「内核读数」这种说法。

#### `POST /api/groups/{gid}/files/import-magnet`

磁力导入（public-swarm §3）：把链接交给内核的公开会话，建 `scope=public` 的条目并广播给同群成员。

```json
{ "magnet": "magnet:?xt=urn:btih:0123…&dn=…&xl=…",
  "name": "可选显示名", "timeoutMs": 0 }
→ 200 { "ok": true,
        "data": { "ok": false, "fileId": "0123…", "code": "no_source",
                  "message": "找不到来源，先挂着等别人上线",
                  "file": 文件对象 } }
```

- `data` 的四个字段与 core 的 `PublicMagnetResult` 同名：
  - `ok`：元数据是否已到手。`false` **不是请求失败** —— 条目已经建好，列表里显示「等待来源」，
    内核继续在公开会话里找源，元数据到手后后台回填 `sizeBytes` / `pieceSize` 并推 WS。
  - `fileId`：40 位 hex 的 infohash，就是群里的文件 id。
  - `code`：`""` 表示成功；否则是下面五种之一。
  - `message`：内核给的一句中文，成功时为 `""`。
- `timeoutMs`：等元数据的上限，0–120000，**默认 0 = 不等**。daemon 是异步状态模型，导入立即回包；
  要拿同步结果就显式给（内核默认 60 s）。给 0 时通常回 `no_source`，条目照样在。
- `name`：可选，只用于列表显示（最多 120 字）；不给时依次用元数据里的名字、磁力的 `dn`、
  「未命名种子」。

五种失败码（与 core 的 `PublicMagnetResult.failureReasons()` 一致，中文文案原样透传）：

| code | message | HTTP | 条目 |
| --- | --- | --- | --- |
| `invalid_link` | 链接无法识别 | 400 | 不建 |
| `duplicate` | 这个种子已经在群里 | 409 | 已有一条 |
| `no_source` | 找不到来源，先挂着等别人上线 | 200 | 建好，等来源 |
| `timeout` | 等文件信息超时，会继续重试 | 200 | 建好，等来源 |
| `bad_metadata` | 文件信息无效 | 200 | 建好（内核摘掉无效 torrent，条目按等待来源留着） |

请求级失败（400 / 409 / 503）沿用全站错误包络 `{ok:false, code, error}`，并额外带 `message`
（与 `error` 同义）与 `fileId`（重复时是撞上的那条 infohash）；群不存在是 404 `E_NO_GROUP`，
公开会话没起来是 503 `session_error`。

去重口径（§1.1 / 裁决 5）：**同群按 infohash 只留一条**。第二次导入同一个磁力回 409 `duplicate`；
同一个 infohash 已经有私有条目时，内核丢弃公开条目（日志 `file_scope_dedup action=drop`），
`fileId` 仍然指向那条私有条目。

日志：daemon 一行 `api_magnet_import gid=… file_id=… ok=… reason=… timeout_ms=… ms=…`；
内核另有 `public_magnet` / `public_magnet_rejected` / `magnet_added` / `public_session_started`。

#### `POST /api/groups/{gid}/files/{fid}/remove`

本地移除（public-swarm §5.2）：**只从本机列表消失**，不广播、不删磁盘文件。

```json
→ 200 { "fileId": "0123…", "removed": true, "broadcast": false }
```

- 不广播 `file.del`（协议 M2 才有）：同群其他成员照旧看得到这个条目；`broadcast` 恒为 `false`，
  把这件事写进回包里，别让调用方猜。
- 只对**公开条目**开放：私有条目 409 `E_NOT_PUBLIC`，正确动作是 `active=false`（休眠）。
- 正在下载 / 做种 / 找源的任务先摘掉；公开会话没有别的非休眠公开条目时会一起停
  （日志 `public_session_stopped`）。已经下到盘上的文件不动。
- 隐藏标记落在 `settings.json` 的 `hiddenFiles`（key 是 `<gid>/<fid>`），重启后仍然隐藏。
  隐藏之后这个 fid 的详情 / 评论 / 激活 / 限速 / 再移除一律 404 `E_NO_FILE`；
  `/api/groups/{gid}/files` 与 `/api/state` 的列表、`fileCount` 里都不再出现。
- 日志：`event=api_file_remove_local gid=… file_id=… scope=public was_active=… file_kept=…`。

#### `GET|POST /api/groups/{gid}/files/{fid}/comments`

```json
GET  → { "comments": [评论...] }
POST { "body": "这段我下到 60% 了", "parent": "" }     // parent 为空 = 顶层
→ 201 { "comment": { "id", "opId", "author", "body", "ts", "parentId", "seq" } }
```

评论对象（**新评论在前**，按 Lamport 降序、再按 seq 降序）：

```json
{ "id": "G6FSW9BPM37HTEW1DCQT5BAVNC", "opId": "…", "author": "WIN11-0715",
  "authorDev": "3ACYGKG4Q1Q9R2BP", "self": true, "body": "…",
  "ts": 1793921122536, "lamport": 1, "seq": 1, "kind": 0, "parentId": "" }
```

---

### 3.4 加入群

#### `POST /api/join`

```json
{ "tail8": "AQ2078EP",
  "manual": ["127.0.0.1:47300"],
  "timeoutMs": 25000 }
→ { "group": 群对象 }
```

- `tail8`：邀请码**后 8 位**，大小写不敏感。
- `manual`：手动候选，`host:port`，填的是对方的**消息层端口**（`/api/health` 的 `msgPort`，默认 47100 起）。
  可以为空 —— 局域网内 mDNS 会先找到。
- `timeoutMs`：1–60 秒，默认 25 秒。

失败返回 409 `E_JOIN_FAILED`。

---

### 3.5 静态资源

`GET /`、`GET /index.html`、`GET /js/*`、`GET /css/*`、`GET /*.ico` 由 daemon 返回
`windows/resources/web`（或 jar 内 `web/`）。其余路径 404。

---

## 4. WebSocket

    ws://127.0.0.1:47200/ws

连上立刻收到两条：

```json
{"type":"hello","data":{"version":"0.1.0","apiPort":47200,"pid":12412,"deviceName":"WIN11-0715"}}
{"type":"state","rev":27,"event":"hello"}
```

之后只有两种推送：

```json
{"type":"state","rev":28,"event":"file_added"}
{"type":"tick","rev":29}
```

- `state`：有东西变了（群、文件、成员、评论、设置）。`event` 是变更来源，值有
  `kernel_started`、`joined`、`group_created`、`file_added`、`file_active`、`comment_added`、
  `settings_changed`、`seeds_restored` 等。
- `tick`：**传输状态**变了。daemon 每秒采样一次所有在会话里的文件的
  `进度 / 上传速率 / 下载速率 / peer 数`，只有这组数字真的变了才推（空闲不刷屏）。

页面收到 `state` 或 `tick` 都重新拉一次 `/api/state`：推送只告诉「变了」，不搬数据，
省得再定义一套增量格式。

客户端发 `ping`（或空字符串）会收到 `{"type":"pong"}`。服务端不发 ping，也不做心跳超时。

---

## 5. 日志

`<data-dir>/daemon.log`，同时抄一份到 stdout。一行一件事，空格分隔的键值对：

    ts=2026-11-06T07:27:49.859 event=seed_restored file_id=2b30… path=E:\...\travel-photos.zip

排查时常用的 event：`daemon_ready`、`daemon_config`、`api_*`、`http_request`、`ws_open`/`ws_close`、
`ws_push`、`join_*`、`file_*`、`seed_restore_*`、`api_error`、`http_conn_error`。

跨网发现（tracker）这一组：

    ts=… event=kernel_started … tracker=on tracker_base=https://huchuan-relay.autoai233.workers.dev
    ts=… event=tracker_started gid=7N2P… base=https://huchuan-relay.autoai233.workers.dev …
    ts=… event=tracker_register_failed gid=7N2P… reason=… passkey_masked=abcd****
    ts=… event=tracker_disabled reason=base_url_empty

`tracker=on` / `tracker=off` 是内核启动时的装配结果；`tracker_register`（成功）与
`tracker_register_failed`（失败，含 DNS 不通）都代表一次真实的注册尝试；关闭时只有
`tracker_disabled`，不会有 `tracker_service_started` / `tracker_started` / `tracker_announce`。

限速相关的两行：

    ts=… event=session_limit_applied up_kbps=512 down_kbps=128 session_up_kbps=512 session_down_kbps=128 unlimited=false ok=true
    ts=… event=api_limits_set session_up_kbps=512 session_down_kbps=128

`session_*_kbps` 是内核读回值；`ok=false` 表示下发没生效（限速被拒或会话还没起来），脚本可以直接断言它不出现。

---

## 6. daemon 侧的实现约定

1. **重启后恢复做种**：core 的 `state.json` 只存文件条目，不存 BT 会话。daemon 自己记
   `<data-dir>/settings.json` 的 `seedPaths`，启动后在后台线程里用原路径重新 `addFile`：
   同一个文件 → 同一个 infohash → 覆盖同一条目，不会产生重复文件。
   代价是重启时要重新算一遍 piece hash（后台线程，不卡启动）。`--restore-seeds=0` 可关。
   不做这一步的后果很具体：重启后本机共享的文件显示「休眠」，而「激活」走的是下载路径，
   会把自家文件当别人的下回来。
2. **`/api/state` 是 UI 的唯一读口**，带评论；其余接口不带，避免列表被放大。
3. **写操作一律回包后再推 WS**：页面拿到响应 → 立刻重拉快照，不靠响应体拼状态。
4. **daemon 不缓存 core 的业务状态**：所有读都走 core 的公开读口，daemon 只额外记
   「用户设置」「种子来自哪个本地路径」「本机隐藏了哪些条目」这三件 core 不关心的事。
5. **本地移除是 daemon 侧状态**：core 没有「移除条目」这个动作（条目仍在 core 清单里，同群成员
   照旧看得到）。daemon 在 `settings.json` 的 `hiddenFiles` 里记 `<gid>/<fid>`，投影时统一
   过滤（`Daemon.visibleFiles` / `visibleFile`）；公开条目的元数据回填、公开会话按需启停
   都归 core，daemon 只负责停掉任务、落隐藏标记、不再对外显示。

---

## 7. 缺口（需要 core 补的 API，daemon 侧不改 core）

按「不写这个 API，daemon 只能糊过去或干脆不显示」列：

| # | 缺什么 | 现在怎么办 | 影响 |
| --- | --- | --- | --- |
| G1 | 退出 / 解散群的 API | 没有入口 | 群只能建、不能退 |
| G2 | `reseed(fileId, path)`：按已有条目重新做种 | 用 `addFile(同一个文件)` 覆盖条目（infohash 相同） | 重启恢复做种要重算 hash；文件内容变了会多出一条新条目 |
| G3 | 成员做种数、成员平台、全群 seeders | 只有本机是真数，别人写 0 / 「未知」 | P6 成员页的数字偏低 |
| G4 | 未读数（unread） | 恒 0 | 首页「N 条新消息」永远不出现 |
| ~~G5~~ | ~~组队（teammates）~~ **已补**：core 提供 `kernel.teammates(gid, fileId)`，daemon 接进 `file.teammates` / `group.teamSize` | 直接读内核观测值，不自己推算 | 已关闭（口径与边界见 §3.3） |
| G6 | 评论删除 / 墓碑的对外 API | 只读不删 | 评论发出去就收不回 |
| G7 | `startDownload(fileId, savePath)` 指定落盘目录 | 下载固定落 `<data-dir>/downloads`；本机共享的文件走「重新做种」分流（§3.3 active、§6.1） | 只影响「下载目录不可选」，不再影响休眠→激活的正确性 |
| ~~G8~~ | ~~tracker / 跨网状态查询~~ **已补**：core 提供 `trackerBaseUrl`（构造参数 / start 前的 `setTrackerBaseUrl`）与 `trackerEnabled`，daemon 接成 `/api/settings/tracker` + `settings.tracker*` | 读内核真值，不自己猜 | 已关闭（口径见 §3.1；运行期改配置要重启，core 不支持换基址） |
| G9 | 单文件限速的内核读回（`torrent_handle::upload_limit()`） | 只记 daemon 侧的设置值 + `uploadLimitInForce`（会话里挂着且未完成） | P4 的「已生效」是按「已下发」判的，不是按内核读数判的 |

---

## 8. JRE 打包：绿色版（已落地）

daemon 要 JDK 17+ 的运行时，目标机不该为它装 Java。`scripts/package-windows.sh` 用 jlink 裁出
只含 daemon 用到模块的 runtime，和壳、jar 组成一个目录，拷走就能双击：

    dist/runtime/                jlink 产物（只含 daemon 用到的模块）
    dist/Huchuan/
      Huchuan.exe                壳（windows/build/Huchuan.exe）
      daemon.jar                 同一份 jar：壳在 exe 同级优先找它
      runtime/                   上面那份 runtime 的副本：壳在 exe 同级优先用它

一条命令：`bash scripts/package-windows.sh`（`--skip-jar` / `--skip-exe` / `--clean` 可选）。

### 模块清单怎么来的

```bash
jdeps --multi-release 17 --ignore-missing-deps --print-module-deps daemon.jar
# → java.base,java.logging
```

再补一个 jdeps 看不见的：**`jdk.crypto.ec`**。core 生成邀请码要 EC `KeyPairGenerator`，它是
Security provider 里的东西，字节码里没有直接引用；少了这一项内核启动直接抛
`NoSuchAlgorithmException: EC KeyPairGenerator not available`，daemon 起不来、页面只剩「网络不可用」。
所以打包脚本在 jlink 之后**用产出的 runtime 起一次 daemon 并等 `/api/health` 回 ok**：模块少一个
就当场失败，而不是等用户双击才发现。

jlink 参数：`--strip-debug --no-man-pages --no-header-files --compress=2`。
`--compress` 的写法在 JDK 21 换过（17/18/19/20 只认 `0|1|2`，21+ 才认 `zip-0…zip-9`），
脚本按 `java -version` 的主版本自动选。

### 实际体积（JDK 17 Temurin，2026-11-06）

| 项 | 字节 | MiB |
| --- | ---: | ---: |
| `Huchuan.exe` | 385,024 | 0.37 |
| `daemon.jar` | 8,107,170 | 7.73 |
| `runtime/` | 28,186,664 | 26.88 |
| **合计 `dist/Huchuan`** | **36,678,858** | **34.98** |

runtime 预算是 60 MiB，超了脚本直接失败（说明模块挑多了或压缩没开）。

### 壳怎么找到它们

- **Java**（`main.cpp` 的 `FindJava`）：exe 同级 `runtime\bin\javaw.exe` → `JAVA_HOME\bin` →
  PATH 上的 `javaw.exe` / `java.exe`。三处都没有就退回 exe 内嵌页面，自检显示「网络不可用」，
  点「重试」会重新连（daemon 一起来就恢复）。
- **daemon.jar**（`ResolveDaemonJar`）：`--daemon-jar=` → `HUCHUAN_DAEMON_JAR` →
  exe 同级（`daemon.jar` 或 `daemon\daemon.jar`）→ `%LOCALAPPDATA%\Huchuan\daemon\daemon.jar` →
  源码树。exe 同级排在 `%LOCALAPPDATA%` 前面：绿色版目录里那份就是跟这个 exe 一起发出去的版本。

**为什么不是 GraalVM native-image**：libtorrent4j 的 JNI 与 jmdns 的运行时反射要写一堆
`--initialize-at-build-time` / `reflect-config.json`，调试成本明显高于 jlink，收益只是启动快一点。

---

## 9. 验收

```bash
bash scripts/m1-windows.sh          # 双 daemon 全流程，末行 PASS
```

脚本自己起 A/B 两个 daemon（47200 / 47201，另有一个只验跨网关闭的 C 节点 47202），走完建群 →
后 8 位加入 → 共享 32 MiB → 下载 → SHA-256 比对 → 评论回流 → WS 推送 → Web UI 数据通路，
最后是公开种子的磁力导入 / 本地移除，最后打印 `PASS` / `FAIL`。
共 82 条断言。

限速与组队这一组（5 条）：B 侧 `POST /api/settings/limits {"uploadKbps":512,"downloadKbps":512}`
后 `sessionUploadLimitKbps` / `sessionDownloadLimitKbps` 必须读回 512，改成 0 必须读回 0，
`GET` 与 `POST` 的读数一致，日志里有 `session_limit_applied … ok=true` 且没有 `ok=false`。
组队 2 条：下载期间 9 次采样 `teammates` 字段都在（读口接上了），且全程为 0 —— 这一轮只有 A 一个
做种者，按 spec §306 做种者不计入队友。队友 ≥1 的画面取证在 `windows/scripts/capture-team-ui.sh`
（三个 daemon，S 做种 + C1/C2 同时下载），见 `docs/windows-ui.md` §6.1。

跨网发现 18 条：A 侧默认开、地址是已部署的 Worker、内核里 `active=true`、`kernel_started` 里
`tracker=on`、建群后出现 `tracker_started` 且确实发起注册（本机 DNS 被拦时日志是
`tracker_register_failed`，失败可接受 —— 断言的是「有尝试」，不是「注册成功」）。另起一个
启动前就把 `trackerEnabled` 写成 `false` 的 C 节点（47202）：`kernel_started tracker=off` 且
`tracker_disabled`，建群后没有任何 `tracker_service_started` / `tracker_register` /
`tracker_announce`；运行期 POST `{"enabled":true}` 只落盘，回包 `restartRequired=true` 且
`active=false`。P7 关闭态的界面取证见 `docs/windows-ui.md` §6.2。

公开种子 17 条（最后一组，跑在收尾前）：非法磁力 `POST …/files/import-magnet` 必须 400 +
`invalid_link`（中文文案与 core 一致）；同一合法磁力导入两次，第二次 409 + `duplicate`；导入后
`/api/state` 里该条目 `scope=public`、带规范化后的 `magnet`，且 `sizeKnown=false` 时
`sizeBytes` 整个不出现；`POST …/files/{fid}/remove` 之后 A 的 state 里没有这个 id、详情 404、
`settings.json` 的 `hiddenFiles` 有记录，而同群 B 的 state 里仍然有 —— 证明移除没有广播。

最后一条验绿色版：用 `cmd` 把 `JAVA_HOME` 清空、`PATH` 只留 `C:\Windows\System32`（复现
「目标机没装 Java」），再启动 `dist/Huchuan/Huchuan.exe`，要求 47290 上的 `/api/health` 通，
且壳日志里「拉起 daemon」用的 java 是 exe 同级的 `runtime\bin\javaw.exe`。
`dist/Huchuan` 不存在时脚本会先跑一次 `scripts/package-windows.sh`。

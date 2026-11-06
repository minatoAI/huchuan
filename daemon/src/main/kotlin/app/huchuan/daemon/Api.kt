package app.huchuan.daemon

import app.huchuan.core.FileScope
import app.huchuan.core.m1.GroupRuntime
import app.huchuan.core.m1.J
import app.huchuan.core.m1.PublicMagnetResult
import app.huchuan.core.m1.jarr
import app.huchuan.core.m1.jbool
import app.huchuan.core.m1.jnum
import app.huchuan.core.m1.jobj
import app.huchuan.core.m1.jstr
import java.io.File

/** 统一的错误响应体：{"ok":false,"code":"E_...","error":"人话"}。 */
fun apiError(code: String, message: String): J.Obj = jobj(
    "ok" to jbool(false),
    "code" to jstr(code),
    "error" to jstr(message),
)

private fun ok(data: J, status: Int = 200): HttpResponse = HttpResponse.json(
    jobj("ok" to jbool(true), "data" to data),
    status,
)

private fun fail(code: String, message: String, status: Int = 400): HttpResponse =
    HttpResponse.json(apiError(code, message), status)

/**
 * REST 路由。只做「解析 → 调 core 公开 API → 转 JSON」，不含业务规则。
 * 路由表与字段含义见 docs/daemon-api.md。
 */
class Api(private val daemon: Daemon) {

    fun handle(request: HttpRequest): HttpResponse {
        val segments = request.path.split('/').filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != "api") {
            return fail("E_NOT_FOUND", request.path, 404)
        }
        return try {
            when (val group = segments[1]) {
                "health" -> health(request)
                "state" -> state(request)
                "settings" -> settingsRoute(request, segments.drop(2))
                "join" -> joinRoute(request)
                "shutdown" -> shutdownRoute(request)
                "logs" -> logsRoute(request)
                "groups" -> groupsRoute(request, segments.drop(2))
                else -> fail("E_NOT_FOUND", "/" + group, 404)
            }
        } catch (e: Throwable) {
            daemon.log.event("api_error", mapOf("path" to request.path, "error" to e.toString()))
            fail("E_INTERNAL", e.toString(), 500)
        }
    }

    // ------------------------------------------------------------------ 基础

    private fun health(request: HttpRequest): HttpResponse {
        if (request.method != "GET") return methodNotAllowed(request)
        val snapshot = daemon.snapshot()
        val info = snapshot.obj("daemon")
        val groups = (snapshot["groups"] as? J.Arr)?.values?.size ?: 0
        return ok(jobj(
            "status" to jstr("ok"),
            "version" to (info?.get("version") ?: jstr(Daemon.VERSION)),
            "pid" to (info?.get("pid") ?: jnum(0)),
            "apiPort" to (info?.get("apiPort") ?: jnum(0)),
            "msgPort" to (info?.get("msgPort") ?: jnum(0)),
            "btPort" to (info?.get("btPort") ?: jnum(0)),
            "mdns" to (info?.get("mdns") ?: jbool(false)),
            "groups" to jnum(groups.toLong()),
            "deviceName" to jstr(daemon.options.deviceName),
        ))
    }

    private fun state(request: HttpRequest): HttpResponse {
        if (request.method != "GET") return methodNotAllowed(request)
        return ok(daemon.snapshot())
    }

    /**
     * `/api/settings`（用户名等通用设置）、`/api/settings/limits`（全局限速）、
     * `/api/settings/tracker`（跨网发现）共用这条路由。
     *
     * 限速是唯一需要「运行期下发内核」的设置项：改完必须立刻 applySettings，不能只落
     * settings.json —— 落盘的只是下次启动要恢复的目标值。tracker 正相反：内核只在 start 时
     * 装配它，所以只落盘，回包里用 restartRequired 说明还没生效。
     */
    private fun settingsRoute(request: HttpRequest, rest: List<String>): HttpResponse {
        if (rest.isNotEmpty()) {
            return when (rest[0]) {
                "limits" -> limitsRoute(request)
                "tracker" -> trackerRoute(request)
                else -> fail("E_NOT_FOUND", request.path, 404)
            }
        }
        return when (request.method) {
            "GET" -> ok(jobj("settings" to daemon.settingsJson()))
            "POST" -> {
                val body = request.json() as? J.Obj
                    ?: return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
                daemon.settings.applyPatch(body)
                if (body.fields.keys.any { LIMIT_FIELDS.contains(it) }) daemon.applySessionLimits()
                daemon.bump("settings_changed")
                ok(jobj("settings" to daemon.settingsJson()))
            }
            else -> methodNotAllowed(request)
        }
    }

    /**
     * 全局限速（P7）：GET 给当前设置 + 内核读回，POST 下发内核后回**内核读回值**。
     *
     * 回包里 `sessionUploadLimitKbps` / `sessionDownloadLimitKbps` 是会话里真正生效的值（读的是
     * libtorrent 的 settings，不是 daemon 自己的记录）。读到的与要的不一样时 daemon 日志有一行
     * `session_limit_applied … ok=false`，脚本可以断言它不出现。
     */
    private fun limitsRoute(request: HttpRequest): HttpResponse {
        return when (request.method) {
            "GET" -> ok(jobj("limits" to daemon.limitsJson()))
            "POST" -> {
                val body = request.json() as? J.Obj
                    ?: return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
                val patch = LinkedHashMap<String, J>()
                var positive = false
                for ((apiKey, settingKey) in KPBS_FIELDS) {
                    if (!body.fields.containsKey(apiKey)) continue
                    val value = (body.fields[apiKey] as? J.Num)?.value
                        ?: return fail("E_BAD_LIMIT", apiKey + " 必须是整数（0 = 不限）", 400)
                    if (value < 0 || value > MAX_LIMIT_KBPS) {
                        return fail(
                            "E_BAD_LIMIT",
                            apiKey + " 超出范围：0–" + MAX_LIMIT_KBPS + "（0 = 不限）",
                            400,
                        )
                    }
                    if (value > 0) positive = true
                    // 请求体用 uploadKbps / downloadKbps（跟界面上的 KB/s 一致），落盘字段沿用旧名，
                    // 免得老 settings.json 里的 uploadLimitKbps 认不出来。
                    patch[settingKey] = jnum(value)
                }
                if (body.fields.containsKey("unlimited")) {
                    val flag = body.fields["unlimited"] as? J.Bool
                        ?: return fail("E_BAD_LIMIT", "unlimited 必须是布尔", 400)
                    patch["unlimited"] = flag
                } else if (positive) {
                    // 显式给了一个非 0 的值就是要限速：顺手把「不限」开关关掉，
                    // 否则调用方还得记得补一个 unlimited=false（Android 端的界面也是这么理解输入框的）。
                    patch["unlimited"] = jbool(false)
                }
                if (patch.isEmpty()) {
                    return fail("E_BAD_LIMIT", "至少给一项：uploadKbps / downloadKbps / unlimited", 400)
                }
                daemon.settings.applyPatch(J.Obj(patch))
                val readBack = daemon.applySessionLimits()
                daemon.bump("limits_changed")
                daemon.log.event(
                    "api_limits_set",
                    mapOf(
                        "session_up_kbps" to readBack.first.toString(),
                        "session_down_kbps" to readBack.second.toString(),
                    ),
                )
                ok(jobj("limits" to daemon.limitsJson()))
            }
            else -> methodNotAllowed(request)
        }
    }

    /**
     * 跨网发现（P7 的开关 + 只读地址）。
     *
     * GET 给当前设置与内核现状。POST 只校验并落盘：
     *   - `enabled`：布尔，默认开；
     *   - `baseUrl`：http(s) 地址，写进 settings.json，下次启动传给内核。
     *
     * 内核的 tracker 只在 start 时装配（core 明确不支持运行期换基址），所以 POST 之后
     * `restartRequired` 会是 true，直到进程重启；`active` 始终是内核此刻的真值。
     */
    private fun trackerRoute(request: HttpRequest): HttpResponse {
        return when (request.method) {
            "GET" -> ok(jobj("tracker" to daemon.trackerJson()))
            "POST" -> {
                val body = request.json() as? J.Obj
                    ?: return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
                val enabled = if (body.fields.containsKey("enabled")) {
                    body.bool("enabled")
                        ?: return fail("E_BAD_TRACKER", "enabled 必须是布尔", 400)
                } else {
                    null
                }
                val baseUrl = if (body.fields.containsKey("baseUrl")) {
                    val raw = body.str("baseUrl")
                        ?: return fail("E_BAD_TRACKER", "baseUrl 必须是字符串", 400)
                    val normalized = SettingsStore.normalizeBaseUrl(raw)
                    if (normalized.isEmpty() || !SettingsStore.isValidTrackerBaseUrl(normalized)) {
                        return fail("E_BAD_TRACKER", "baseUrl 必须是 http:// 或 https:// 开头的地址", 400)
                    }
                    normalized
                } else {
                    null
                }
                if (enabled == null && baseUrl == null) {
                    return fail("E_BAD_TRACKER", "至少给一项：enabled / baseUrl", 400)
                }
                val nextEnabled = enabled ?: daemon.settings.trackerEnabled
                val nextBase = baseUrl ?: daemon.settings.trackerBaseUrl
                if (nextEnabled && nextBase.isEmpty()) {
                    return fail("E_BAD_TRACKER", "跨网发现要一个 tracker 地址", 400)
                }
                val changed = daemon.settings.applyTracker(enabled, baseUrl)
                if (changed) daemon.bump("tracker_changed")
                val state = daemon.trackerJson()
                daemon.log.event(
                    "api_tracker_set",
                    mapOf(
                        "enabled" to state.bool("enabled").toString(),
                        "base" to (state.str("baseUrl") ?: ""),
                        "changed" to changed.toString(),
                        "restart_required" to state.bool("restartRequired").toString(),
                    ),
                )
                ok(jobj("tracker" to state))
            }
            else -> methodNotAllowed(request)
        }
    }

    private fun joinRoute(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return methodNotAllowed(request)
        val body = request.json() ?: return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
        val tail8 = (body.str("tail8") ?: "").trim().uppercase()
        if (tail8.length != 8) return fail("E_INVITE_FORMAT", "后 8 位必须是 8 个字符", 400)
        val manual = body.arr("manual")?.mapNotNull { (it as? J.Str)?.value } ?: emptyList()
        val timeoutMs = (body.num("timeoutMs") ?: DEFAULT_JOIN_TIMEOUT_MS).coerceIn(1_000L, 60_000L)
        val kernel = daemon.kernelOrNull() ?: return fail("E_NO_KERNEL", "daemon 还没起好", 503)

        val started = System.currentTimeMillis()
        val joined = kernel.joinByTail8(tail8, manual, timeoutMs)
        val elapsed = System.currentTimeMillis() - started
        if (joined == null) {
            daemon.log.event("api_join_failed", mapOf("tail8" to tail8, "ms" to elapsed.toString()))
            return fail("E_JOIN_FAILED", "没找到这个群：检查后 8 位、对方是否在跑、地址是否可达", 409)
        }
        daemon.bump("joined")
        daemon.log.event(
            "api_join_ok",
            mapOf("gid" to joined.groupId, "ms" to elapsed.toString(), "manual" to manual.joinToString(",")),
        )
        return ok(jobj("group" to daemon.groupJson(joined)))
    }

    private fun shutdownRoute(request: HttpRequest): HttpResponse {
        if (request.method != "POST") return methodNotAllowed(request)
        Thread {
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
            daemon.requestShutdown("api")
        }.apply { isDaemon = true }.start()
        return ok(jobj("stopping" to jbool(true)))
    }

    private fun logsRoute(request: HttpRequest): HttpResponse {
        if (request.method != "GET") return methodNotAllowed(request)
        val path = daemon.options.logPath
        if (path.isNullOrEmpty()) return ok(jobj("lines" to jarr(emptyList())))
        val file = File(path)
        if (!file.isFile) return ok(jobj("lines" to jarr(emptyList())))
        val limit = request.queryInt("n", 200).coerceIn(1, 5000)
        val lines = file.readLines(Charsets.UTF_8)
        val tail = if (lines.size > limit) lines.subList(lines.size - limit, lines.size) else lines
        return ok(jobj("lines" to jarr(tail.map { jstr(it) })))
    }

    // ------------------------------------------------------------------ 群

    private fun groupsRoute(request: HttpRequest, rest: List<String>): HttpResponse {
        if (rest.isEmpty()) {
            return when (request.method) {
                "GET" -> {
                    val groups = daemon.kernelOrNull()?.groups() ?: emptyList()
                    ok(jobj("groups" to jarr(groups.map { daemon.groupJson(it) })))
                }
                "POST" -> createGroup(request)
                else -> methodNotAllowed(request)
            }
        }
        val group = daemon.group(rest[0]) ?: return fail("E_NO_GROUP", rest[0], 404)
        if (rest.size == 1) {
            return if (request.method == "GET") ok(jobj("group" to daemon.groupJson(group))) else methodNotAllowed(request)
        }
        return when (rest[1]) {
            "files" -> filesRoute(request, group, rest.drop(2))
            "members" -> {
                if (request.method != "GET") methodNotAllowed(request)
                else ok(jobj("members" to (daemon.groupJson(group).obj("members") ?: jarr(emptyList()))))
            }
            "notification" -> notificationRoute(request, group)
            else -> fail("E_NOT_FOUND", request.path, 404)
        }
    }

    private fun createGroup(request: HttpRequest): HttpResponse {
        val body = request.json()
        if (body == null) return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
        val name = (body.str("name") ?: "").trim().take(40)
        val kernel = daemon.kernelOrNull() ?: return fail("E_NO_KERNEL", "daemon 还没起好", 503)
        val group = kernel.createGroup(name.ifEmpty { daemon.options.deviceName + "的群组" })
        daemon.bump("group_created")
        daemon.log.event("api_group_created", mapOf("gid" to group.groupId, "name" to group.name, "tail8" to group.inviteCode.takeLast(8)))
        return ok(jobj("group" to daemon.groupJson(group)), 201)
    }

    private fun notificationRoute(request: HttpRequest, group: GroupRuntime): HttpResponse {
        if (request.method != "POST") return methodNotAllowed(request)
        val body = request.json() ?: return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
        val level = body.str("level") ?: return fail("E_BAD_LEVEL", "level 必填", 400)
        if (!daemon.settings.setNotification(group.groupId, level)) {
            return fail("E_BAD_LEVEL", "level 只能是 bubble / in_app / muted", 400)
        }
        daemon.bump("notification_changed")
        return ok(jobj("groupId" to jstr(group.groupId), "notificationLevel" to jstr(daemon.settings.notificationOf(group.groupId))))
    }

    // ------------------------------------------------------------------ 文件

    private fun filesRoute(request: HttpRequest, group: GroupRuntime, rest: List<String>): HttpResponse {
        if (rest.isEmpty()) {
            return when (request.method) {
                "GET" -> {
                    val files = daemon.visibleFiles(group)
                    ok(jobj(
                        "files" to jarr(files.map { daemon.fileJson(group, it) }),
                        "fileCount" to jnum(files.size.toLong()),
                    ))
                }
                "POST" -> addFile(request, group)
                else -> methodNotAllowed(request)
            }
        }
        // 磁力导入是集合级动作：/files/import-magnet（fileId 一律 40 位 hex，不会撞）。
        if (rest.size == 1 && rest[0] == "import-magnet") return importMagnet(request, group)
        val fileId = rest[0]
        // 本机移除过的条目按「不存在」处理：详情 / 评论 / 激活 / 限速 / 再移除一律 404。
        val entry = daemon.visibleFile(group, fileId) ?: return fail("E_NO_FILE", fileId, 404)
        if (rest.size == 1) {
            if (request.method != "GET") return methodNotAllowed(request)
            return ok(jobj(
                "file" to daemon.fileJson(group, entry),
                "comments" to daemon.commentsJson(group, fileId),
            ))
        }
        return when (rest[1]) {
            "comments" -> when (request.method) {
                "GET" -> ok(jobj("comments" to daemon.commentsJson(group, fileId)))
                "POST" -> addComment(request, group, fileId)
                else -> methodNotAllowed(request)
            }
            "active" -> activeRoute(request, group, fileId)
            "upload-limit" -> uploadLimitRoute(request, group, fileId)
            "remove" -> removeFile(request, group, fileId)
            else -> fail("E_NOT_FOUND", request.path, 404)
        }
    }

    private fun addFile(request: HttpRequest, group: GroupRuntime): HttpResponse {
        val body = request.json() ?: return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
        val raw = (body.str("path") ?: "").trim()
        if (raw.isEmpty()) return fail("E_BAD_PATH", "path 必填：要给 daemon 一个本机绝对路径", 400)
        val file = File(raw)
        if (!file.isAbsolute) return fail("E_BAD_PATH", "必须是绝对路径：" + raw, 400)
        if (!file.isFile) return fail("E_NO_SUCH_FILE", "找不到文件：" + raw, 404)
        if (!file.canRead()) return fail("E_UNREADABLE", "读不了这个文件：" + raw, 403)
        if (file.length() <= 0L) return fail("E_EMPTY_FILE", "空文件没法做种：" + raw, 400)

        val started = System.currentTimeMillis()
        val entry = group.addFile(file)
        daemon.settings.setSeedPath(entry.fileId, file.absolutePath)
        val limit = daemon.settings.uploadLimitOf(entry.fileId)
        if (limit > 0) group.setUploadLimitKbps(entry.fileId, limit)
        daemon.bump("file_added")
        daemon.log.event(
            "api_file_added",
            mapOf(
                "gid" to group.groupId,
                "file_id" to entry.fileId,
                "name" to entry.name,
                "size" to entry.size.toString(),
                "path" to file.absolutePath,
                "ms" to (System.currentTimeMillis() - started).toString(),
            ),
        )
        return ok(jobj("file" to daemon.fileJson(group, entry)), 201)
    }

    private fun addComment(request: HttpRequest, group: GroupRuntime, fileId: String): HttpResponse {
        val body = request.json() ?: return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
        val text = (body.str("body") ?: "").trim()
        if (text.isEmpty()) return fail("E_EMPTY_COMMENT", "评论不能为空", 400)
        val parent = body.str("parent")?.takeIf { it.isNotEmpty() }
        if (parent != null && group.ops(null).none { it.opId == parent }) {
            return fail("E_NO_PARENT", "回复的评论不见了：" + parent, 404)
        }
        val op = group.comment(fileId, text, parent)
        daemon.bump("comment_added")
        return ok(jobj("comment" to jobj(
            "id" to jstr(op.opId),
            "opId" to jstr(op.opId),
            "author" to jstr(daemon.settings.userName.ifEmpty { daemon.options.deviceName }),
            "body" to jstr(op.body),
            "ts" to jnum(op.ts),
            "parentId" to jstr(op.parent),
            "seq" to jnum(op.seq),
        )), 201)
    }

    private fun activeRoute(request: HttpRequest, group: GroupRuntime, fileId: String): HttpResponse {
        if (request.method != "POST") return methodNotAllowed(request)
        val body = request.json() ?: return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
        val active = body.bool("active") ?: return fail("E_BAD_ACTIVE", "active 必填（true=激活 / false=休眠）", 400)
        val entry = daemon.visibleFile(group, fileId) ?: return fail("E_NO_FILE", fileId, 404)
        val role = group.localRole(fileId)
        if (active) {
            if (role == GroupRuntime.LocalRole.NONE) {
                // 本机共享的文件：唤醒 = 重新做种，不能走下载路径 —— core 的 startDownload 固定落到
                // downloads 目录，会把自家文件当别人的下回来（docs/daemon-api.md 缺口 G7）。
                // 用原路径重新 addFile：同一个文件 → 同一个 infohash → 覆盖同一条目。
                val seedPath = daemon.settings.seedPathOf(fileId)
                val localFile = seedPath?.let { File(it) }
                val mine = localFile != null && localFile.isFile && localFile.length() == entry.size
                if (mine) {
                    val reseeded = group.addFile(localFile!!)
                    if (reseeded.fileId != fileId) {
                        return fail("E_FILE_CHANGED", "磁盘上的文件内容变了，infohash 不再是 " + fileId, 409)
                    }
                } else {
                    val started = group.startDownload(fileId)
                    if (!started) return fail("E_ACTIVATE_FAILED", "core 里没有这个文件的种子信息（torrent 为空）", 409)
                }
                val limit = daemon.settings.uploadLimitOf(fileId)
                if (limit > 0) group.setUploadLimitKbps(fileId, limit)
            }
        } else if (role != GroupRuntime.LocalRole.NONE) {
            group.stopFile(fileId)
        }
        daemon.bump("file_active")
        return ok(jobj("file" to daemon.fileJson(group, entry)))
    }

    private fun uploadLimitRoute(request: HttpRequest, group: GroupRuntime, fileId: String): HttpResponse {
        if (request.method != "POST") return methodNotAllowed(request)
        val body = request.json() ?: return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
        val kbps = (body.num("kbps") ?: return fail("E_BAD_LIMIT", "kbps 必填（0 = 不限）", 400)).toInt().coerceAtLeast(0)
        group.setUploadLimitKbps(fileId, kbps)
        daemon.settings.setUploadLimit(fileId, kbps)
        daemon.bump("upload_limit_changed")
        val entry = daemon.visibleFile(group, fileId)
        return ok(jobj("file" to if (entry == null) jstr("") else daemon.fileJson(group, entry)))
    }

    /**
     * 磁力导入（public-swarm §3）：把链接交给内核的公开会话，等元数据。
     *
     * 回包分两层看：
     *   - 请求层（HTTP 状态 + 顶层 code）：非法链接 400 / 重复 409 / 公开会话没起来 503；
     *   - 结果层（200 的 data）：内核对「条目已入库」的判定，字段与 core 的 PublicMagnetResult 同名。
     *     data.ok=false 表示**条目已经建好、文件信息还没到手**（core 的 no_source / timeout /
     *     bad_metadata），不是请求失败；条目照常出现在 state 里，内核继续找源并在到手后回填。
     *
     * timeoutMs 默认 0：daemon 不等元数据就回包（页面靠 WS 推送看后续变化）；要同步结果就显式给，
     * 上限 120 s。name 只用于列表显示，超长截断到 120 字。
     */
    private fun importMagnet(request: HttpRequest, group: GroupRuntime): HttpResponse {
        if (request.method != "POST") return methodNotAllowed(request)
        val body = request.json() as? J.Obj
            ?: return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
        val magnet = (body.str("magnet") ?: "").trim()
        val name = body.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_MAGNET_NAME)
        val timeoutMs = (body.num("timeoutMs") ?: DEFAULT_MAGNET_TIMEOUT_MS)
            .coerceIn(0L, MAX_MAGNET_TIMEOUT_MS)
        val kernel = daemon.kernelOrNull() ?: return fail("E_NO_KERNEL", "daemon 还没起好", 503)

        val started = System.currentTimeMillis()
        val result = kernel.addPublicMagnet(group.groupId, magnet, name, timeoutMs)
        val elapsed = System.currentTimeMillis() - started
        daemon.log.event(
            "api_magnet_import",
            mapOf(
                "gid" to group.groupId,
                "file_id" to result.fileId,
                "ok" to result.ok.toString(),
                "reason" to result.reason,
                "size" to result.size.toString(),
                "timeout_ms" to timeoutMs.toString(),
                "ms" to elapsed.toString(),
            ),
        )
        return when (result.reason) {
            PublicMagnetResult.INVALID_LINK ->
                magnetFail(PublicMagnetResult.INVALID_LINK, result.message, 400, result.fileId)
            PublicMagnetResult.DUPLICATE ->
                magnetFail(PublicMagnetResult.DUPLICATE, result.message, 409, result.fileId)
            // 路由已经先按 group 找过一次；这里只兜内核侧的竞态（群刚好被删）。
            PublicMagnetResult.NO_GROUP -> fail("E_NO_GROUP", result.message, 404)
            PublicMagnetResult.SESSION_ERROR ->
                magnetFail(PublicMagnetResult.SESSION_ERROR, result.message, 503, result.fileId)
            else -> {
                val fields = LinkedHashMap<String, J>()
                fields["ok"] = jbool(result.ok)
                fields["fileId"] = jstr(result.fileId)
                fields["code"] = jstr(result.reason)
                fields["message"] = jstr(result.message)
                // 条目一定在（core 的语义：除非法 / 重复外都入库）；file 是给页面省一次刷新用的。
                result.entry?.let { fields["file"] = daemon.fileJson(group, it) }
                ok(J.Obj(fields))
            }
        }
    }

    /**
     * 磁力导入的请求级失败：沿用全站 {ok:false, code, error}，并带上内核同名的
     * message / fileId（重复时 fileId 是撞上的那条 infohash）。见 docs/daemon-api.md。
     */
    private fun magnetFail(code: String, message: String, status: Int, fileId: String): HttpResponse =
        HttpResponse.json(
            jobj(
                "ok" to jbool(false),
                "code" to jstr(code),
                "error" to jstr(message),
                "message" to jstr(message),
                "fileId" to jstr(fileId),
            ),
            status,
        )

    /**
     * 本地移除（public-swarm §5.2）：只从本机列表消失，不广播、不删磁盘文件。
     *
     * 只对公开条目开放：P4 的「移除」菜单只长在公开条目上，私有条目的正确动作是「休眠」。
     * 正在跑的任务先休眠，否则移除之后公开会话还在替它上传；已下载到盘上的文件不动。
     * 隐藏标记落在 settings.json，重启后仍然隐藏（别的成员照旧能看到这个条目）。
     */
    private fun removeFile(request: HttpRequest, group: GroupRuntime, fileId: String): HttpResponse {
        if (request.method != "POST") return methodNotAllowed(request)
        val entry = daemon.visibleFile(group, fileId) ?: return fail("E_NO_FILE", fileId, 404)
        if (entry.scope != FileScope.PUBLIC) {
            return fail("E_NOT_PUBLIC", "私有条目不能移除，只能休眠", 409)
        }
        val wasActive = group.localRole(fileId) != GroupRuntime.LocalRole.NONE
        // 无条件摘掉会话里的任务：磁力导入的 torrent 在「激活」之前就已经挂在公开会话里找源
        // （不摘的话移除之后它还在后台拉元数据，公开会话也不会按需停）。
        group.stopFile(fileId)
        daemon.settings.hideFile(group.groupId, fileId)
        daemon.bump("file_removed")
        daemon.log.event(
            "api_file_remove_local",
            mapOf(
                "gid" to group.groupId,
                "file_id" to fileId,
                "scope" to entry.scope.wire,
                "was_active" to wasActive.toString(),
                "file_kept" to (group.savedFile(fileId)?.isFile == true).toString(),
            ),
        )
        return ok(jobj(
            "fileId" to jstr(fileId),
            "removed" to jbool(true),
            // 明确告诉调用方：这是本机动作，群里其他人不会跟着删（M2 才有 file.del）。
            "broadcast" to jbool(false),
        ))
    }

    private fun methodNotAllowed(request: HttpRequest): HttpResponse =
        fail("E_METHOD", request.method + " 不支持 " + request.path, 405)

    companion object {
        private const val DEFAULT_JOIN_TIMEOUT_MS = 25_000L

        /** `/api/settings` 里会触及内核会话的那几个字段。 */
        private val LIMIT_FIELDS = setOf("uploadLimitKbps", "downloadLimitKbps", "unlimited")

        /** `/api/settings/limits` 的两个 KB/s 字段：请求体名字 → settings.json 里的字段名。 */
        private val KPBS_FIELDS = listOf("uploadKbps" to "uploadLimitKbps", "downloadKbps" to "downloadLimitKbps")

        /** 与 core 的 saneKbps 同一个上限（Int.MAX_VALUE / 1024），超出直接拒绝而不是悄悄截断。 */
        private const val MAX_LIMIT_KBPS = Int.MAX_VALUE / 1024

        /**
         * 磁力导入默认不等元数据：daemon 是异步状态模型，条目入库即回包，元数据到手后由内核
         * 后台回填并推 WS。调用方要同步结果就显式给 timeoutMs（core 的默认是 60 s）。
         */
        private const val DEFAULT_MAGNET_TIMEOUT_MS = 0L

        /** 同步等待的上限：比 core 的默认 60 s 宽，但别让一个 HTTP 请求挂到天亮。 */
        private const val MAX_MAGNET_TIMEOUT_MS = 120_000L

        /** 条目名只用于列表显示；帧上限 65533 B，留足余量。 */
        private const val MAX_MAGNET_NAME = 120
    }
}

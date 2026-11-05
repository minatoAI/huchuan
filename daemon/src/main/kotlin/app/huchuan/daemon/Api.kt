package app.huchuan.daemon

import app.huchuan.core.m1.GroupRuntime
import app.huchuan.core.m1.J
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
                "settings" -> settingsRoute(request)
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

    private fun settingsRoute(request: HttpRequest): HttpResponse {
        return when (request.method) {
            "GET" -> ok(jobj("settings" to daemon.settingsJson()))
            "POST" -> {
                val body = request.json()
                if (body == null) return fail("E_BAD_JSON", "body 必须是 JSON 对象", 400)
                daemon.settings.applyPatch(body)
                daemon.bump("settings_changed")
                ok(jobj("settings" to daemon.settingsJson()))
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
                "GET" -> ok(jobj(
                    "files" to jarr(group.files().map { daemon.fileJson(group, it) }),
                    "fileCount" to jnum(group.files().size.toLong()),
                ))
                "POST" -> addFile(request, group)
                else -> methodNotAllowed(request)
            }
        }
        val fileId = rest[0]
        val entry = group.file(fileId) ?: return fail("E_NO_FILE", fileId, 404)
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
        val entry = group.file(fileId) ?: return fail("E_NO_FILE", fileId, 404)
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
        val entry = group.file(fileId)
        return ok(jobj("file" to if (entry == null) jstr("") else daemon.fileJson(group, entry)))
    }

    private fun methodNotAllowed(request: HttpRequest): HttpResponse =
        fail("E_METHOD", request.method + " 不支持 " + request.path, 405)

    companion object {
        private const val DEFAULT_JOIN_TIMEOUT_MS = 25_000L
    }
}

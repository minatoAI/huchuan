package app.huchuan.daemon

import java.io.File

/**
 * Web UI 静态资源。
 *
 * 两个来源，按顺序找：
 *   1. --web-root 指定的目录（开发时直接指 windows/resources/web，改完刷新页面即可）；
 *   2. jar 内的 web/ 目录（打包时由 :daemon:daemonJar 从同一个 windows/resources/web 抄进来）。
 *
 * 页面由 daemon 同源提供是有意的：WebView2 里页面如果从 https://huchuan.invalid 这类
 * 伪造源去 fetch http://127.0.0.1，会撞上 Chromium 的私有网络访问检查（PNA / Local Network
 * Access）。同源就没有这层麻烦，也免了 CORS 预检。见 docs/daemon-api.md。
 */
class WebAssets(private val root: File?, private val log: DaemonLogger) {

    fun get(path: String): Pair<ByteArray, String>? {
        val clean = sanitize(path)
        if (clean == null) {
            log.event("web_reject", mapOf("path" to path))
            return null
        }
        val effective = if (clean.isEmpty()) "index.html" else clean

        if (root != null) {
            val file = File(root, effective)
            if (file.isFile && file.canonicalPath.startsWith(root.canonicalPath)) {
                return file.readBytes() to contentType(effective)
            }
        }
        val stream = javaClass.getResourceAsStream("/web/" + effective) ?: return null
        val bytes = stream.use { it.readBytes() }
        return bytes to contentType(effective)
    }

    /** 只允许相对路径，挡掉 ../ 与绝对路径。 */
    private fun sanitize(path: String): String? {
        var p = path.replace('\\', '/').trim()
        while (p.startsWith("/")) p = p.substring(1)
        if (p.contains("..")) return null
        if (p.contains(':')) return null
        return p
    }

    companion object {
        fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "ico" -> "image/x-icon"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}

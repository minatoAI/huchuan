package app.huchuan.daemon

import java.io.File
import java.io.PrintStream
import java.text.SimpleDateFormat
import java.util.Date

/**
 * daemon 自己的日志：一行一件事，键值对形式，脚本可以 grep。
 *
 *   ts=2026-11-06T06:40:00.123 event=http_request method=GET path=/api/state status=200 ms=2
 *
 * 写文件（<data-dir>/daemon.log）同时抄一份到 stdout，方便壳/脚本直接接。
 */
class DaemonLogger(private val file: File?, private val echo: PrintStream) {

    private val lock = Any()
    private val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")

    fun event(name: String, fields: Map<String, String> = emptyMap()) {
        val sb = StringBuilder(160)
        sb.append("ts=").append(stamp.format(Date())).append(" event=").append(name)
        for ((k, v) in fields) {
            sb.append(' ').append(k).append('=').append(sanitize(v))
        }
        val line = sb.toString()
        synchronized(lock) {
            echo.println(line)
            echo.flush()
            if (file != null) {
                try {
                    file.appendText(line + "\n", Charsets.UTF_8)
                } catch (_: Throwable) {
                    // 日志写不进去不能把 daemon 拖死
                }
            }
        }
    }

    fun event(name: String, vararg fields: Pair<String, String>) {
        event(name, linkedMapOf(*fields))
    }

    fun info(message: String) = event("info", mapOf("msg" to message))

    /** 值里有空格/换行会把「一行一件事」打散，替换成下划线。 */
    private fun sanitize(value: String): String {
        if (value.isEmpty()) return "-"
        val sb = StringBuilder(value.length)
        for (c in value) sb.append(if (c == ' ' || c == '\n' || c == '\r' || c == '\t') '_' else c)
        return sb.toString()
    }

    companion object {
        /** 测试用：只写 stdout。 */
        fun console(): DaemonLogger = DaemonLogger(null, System.out)
    }
}

package app.huchuan.kernel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * SAF → 应用私有目录。
 *
 * libtorrent 只认文件系统路径，content:// 给不了它；所以选完文件先整份拷进
 * filesDir/m1/share/，再做种。拷完的副本就是做种源，原文件删了也不影响做种。
 */
object FileImporter {

    /** 显示名：OpenableColumns.DISPLAY_NAME，取不到退回 URI 末段。 */
    fun displayName(context: Context, uri: Uri): String {
        var name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index) ?: name
                }
            }
        }
        return sanitize(name.ifBlank { "文件" })
    }

    /** 把 URI 整份拷进 destDir，返回落盘的文件。 */
    fun copyTo(context: Context, uri: Uri, destDir: File): File {
        destDir.mkdirs()
        val target = uniqueTarget(destDir, displayName(context, uri))
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("打不开这个文件：" + uri)
        input.use { source ->
            target.outputStream().use { sink ->
                source.copyTo(sink, DEFAULT_BUFFER)
            }
        }
        return target
    }

    /** 文件名只留安全字符：不许路径分隔符、不许控制字符、不许以点开头。 */
    fun sanitize(raw: String): String {
        val cleaned = raw.map { ch ->
            if (ch.isISOControl() || ch == '/' || ch == '\\' || ch == ':' || ch == '*' ||
                ch == '?' || ch == '"' || ch == '<' || ch == '>' || ch == '|'
            ) {
                '_'
            } else {
                ch
            }
        }.joinToString("").trim().trimStart('.').trim()
        return cleaned.ifEmpty { "文件" }.take(120)
    }

    private fun uniqueTarget(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = File(dir, stem + " (" + index + ")" + ext)
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private const val DEFAULT_BUFFER = 256 * 1024
}

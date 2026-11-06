package app.huchuan.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import app.huchuan.HuchuanApplication
import app.huchuan.R
import app.huchuan.kernel.FileImporter
import app.huchuan.kernel.M1Runtime
import java.io.File
import java.util.concurrent.Executors

/**
 * 系统分享（ACTION_SEND / ACTION_SEND_MULTIPLE）的唯一入口（link-share §2.1）。
 *
 * 调用方是 MainActivity：冷启动走 onCreate，已经活着走 onNewIntent。为什么不做
 * 设计稿里那个透明的 ShareInActivity —— 真机实测（Mi MIX 2S / MIUI 13 / API 33）：
 * App 进程还活着时，系统把 SEND 直接递给栈顶的 MainActivity（am 报
 * "its current task has been brought to the front"），新声明的空壳 Activity 根本不会被创建，
 * 分享会静默丢掉。放在 MainActivity 上，冷热两条路都能收到。
 *
 * **收到即读**：FLAG_GRANT_READ_URI_PERMISSION 的授权挂在收到 Intent 的那个 Activity 上，
 * 随它销毁失效，所以这里当场把 content:// 整份拷进应用私有目录（§2.4）；
 * 之后的做种只认路径，不再碰 URI。
 *
 * 失败一律一句话退出，不弹窗、不崩：0 个文件、0 字节、单个 >1 GiB、超过 10 个、读不到。
 */
object ShareInTake {

    /** 一次分享最多 10 个（link-share §2.3 / §12-8）。 */
    const val MAX_FILES = 10

    /** 单文件 1 GiB 硬上限（link-share §11-3 / §12-4）。 */
    const val MAX_BYTES = 1024L * 1024L * 1024L

    private val main = Handler(Looper.getMainLooper())

    /** 同一时刻只消费一批：连点分享 / 多实例不会把一份文件拷两遍。 */
    private val worker = Executors.newSingleThreadExecutor { job ->
        Thread(job, "huchuan-share-in").apply { isDaemon = true }
    }

    /** 是 SEND / SEND_MULTIPLE 就接手；返回 true 表示这个 Intent 已被当作分享处理。 */
    fun handle(context: Context, intent: Intent?): Boolean {
        val action = intent?.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return false

        val app = context.applicationContext
        val uris = collectUris(intent)
        Log.i(
            M1Runtime.TAG,
            "event=share_in_receive action=" + action +
                " type=" + (intent.type ?: "-") +
                " count=" + uris.size,
        )
        if (uris.isEmpty()) {
            reject(app, "no_file", R.string.share_in_no_file)
            return true
        }
        if (uris.size > MAX_FILES) {
            reject(app, "too_many", R.string.share_in_too_many)
            return true
        }
        // 大小、拷贝都放到工作线程：这些都是 binder / 磁盘 IO，不能压在主线程上（入口是 onCreate/onNewIntent）。
        val dest = (app as? HuchuanApplication)?.runtime?.shareDir ?: File(app.filesDir, "m1/share")
        worker.execute { checkAndCopy(app, uris, dest) }
        return true
    }

    // ------------------------------------------------------------------ 收 → 拷

    /** ClipData 与 EXTRA_STREAM 都读（某些 ROM 只填其一），按出现顺序去重。 */
    private fun collectUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out.add(it) }
        }
        // am --esa 给的是 String[]，个别 ROM 也是这个形状：Uri 与字符串两种都收。
        @Suppress("DEPRECATION")
        val raw: Any? = intent.extras?.get(Intent.EXTRA_STREAM)
        fun add(item: Any?) {
            when (item) {
                is Uri -> out.add(item)
                is String -> Uri.parse(item).let { uri -> if (uri.scheme != null) out.add(uri) }
                else -> Unit
            }
        }
        when (raw) {
            is Uri, is String -> add(raw)
            is ArrayList<*> -> raw.forEach { add(it) }
            is Array<*> -> raw.forEach { add(it) }
            else -> Unit
        }
        return out.toList()
    }

    private fun querySize(context: Context, uri: Uri): Long? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            } else {
                null
            }
        }
    } catch (_: Throwable) {
        null
    }

    /** 先按 provider 报的大小挡掉明显不行的，再逐个整份拷；任一个失败整批放弃。 */
    private fun checkAndCopy(context: Context, uris: List<Uri>, dest: File) {
        // 问得到的先问：省得先拷 5 GiB 再拒绝。问不到的（provider 不报 SIZE）拷完再判。
        for (uri in uris) {
            val size = querySize(context, uri) ?: continue
            if (size == 0L) {
                reject(context, "empty", R.string.share_in_empty)
                return
            }
            if (size > MAX_BYTES) {
                reject(context, "too_large", R.string.share_in_too_large)
                return
            }
        }
        copyAll(context, uris, dest)
    }

    /** 逐个整份拷进应用私有目录；任一个失败就整批放弃并删掉本次已落盘的副本。 */
    private fun copyAll(context: Context, uris: List<Uri>, dest: File) {
        val copied = ArrayList<File>(uris.size)
        var reason = ""
        var message = R.string.share_in_read_failed
        for (uri in uris) {
            val file = try {
                FileImporter.copyTo(context, uri, dest)
            } catch (t: Throwable) {
                Log.w(M1Runtime.TAG, "event=share_in_copy_failed uri=" + uri + " error=" + t)
                reason = "read_failed"
                message = R.string.share_in_read_failed
                break
            }
            // provider 不报 SIZE 的那类：拷完才知道真实大小。
            if (file.length() == 0L) {
                file.delete()
                reason = "empty"
                message = R.string.share_in_empty
                break
            }
            if (file.length() > MAX_BYTES) {
                file.delete()
                reason = "too_large"
                message = R.string.share_in_too_large
                break
            }
            copied.add(file)
        }
        if (reason.isEmpty()) {
            main.post { ShareInRoute.offer(copied) }
        } else {
            copied.forEach { it.delete() }
            main.post {
                Log.i(M1Runtime.TAG, "event=share_in_reject reason=" + reason + " count=" + uris.size)
                toast(context, message)
            }
        }
    }

    /** 一句话拒绝：日志立即打，toast 投回主线程（工作线程没有 Looper，直接 show 会崩）。 */
    private fun reject(context: Context, reason: String, message: Int) {
        Log.i(M1Runtime.TAG, "event=share_in_reject reason=" + reason)
        main.post { toast(context, message) }
    }

    private fun toast(context: Context, message: Int) {
        Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
    }
}

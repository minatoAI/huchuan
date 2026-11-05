package app.huchuan.daemon

import java.io.File
import kotlin.system.exitProcess

/**
 * huchuan daemon：进程内嵌 core，对外只暴露 127.0.0.1 上的 HTTP + WebSocket。
 *
 *   java -jar daemon.jar --data-dir=<dir> --port=47200 [--web-root=<dir>]
 *
 * 端口约定：默认固定 47200（写入 <data-dir>/daemon.port 备查）；--port=0 时随机分配，
 * 实际端口同样写进 daemon.port 并打印在 daemon_ready 一行里。同机跑两个实例时，
 * 必须给不同的 --data-dir 与 --port（见 scripts/m1-windows.sh）。
 */
fun main(args: Array<String>) {
    val options = try {
        parseOptions(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("参数错误：" + e.message)
        System.err.println(USAGE)
        exitProcess(EXIT_CONFIG)
    }
    if (options == null) {
        println(USAGE)
        return
    }

    options.dataDir.mkdirs()
    val logFile = options.logPath?.let { File(it) }
    logFile?.parentFile?.mkdirs()
    val log = DaemonLogger(logFile, System.out)
    log.event("daemon_boot", mapOf("pid" to ProcessHandle.current().pid().toString(), "args" to args.joinToString(" ")))

    val native = try {
        prepareNative(options.dataDir, log)
    } catch (e: Throwable) {
        log.event("daemon_native_error", mapOf("error" to e.toString()))
        System.err.println("拿不到 libtorrent4j 的原生库：" + e.message)
        exitProcess(EXIT_NATIVE)
    }
    log.event("daemon_native", mapOf("path" to native))

    val daemon = Daemon(options, log)
    val server = LocalHttpServer(BIND_HOST, options.port, log)
    val assets = WebAssets(options.webRoot, log)

    try {
        daemon.start()
    } catch (e: Throwable) {
        log.event("daemon_kernel_start_failed", mapOf("error" to e.toString()))
        System.err.println("内核起不来：" + e.message)
        exitProcess(EXIT_KERNEL)
    }

    val api = Api(daemon)
    server.router = { request ->
        if (request.path == "/" || request.path == "/index.html" || request.path.startsWith("/js/") ||
            request.path.startsWith("/css/") || request.path.endsWith(".ico")
        ) {
            serveAsset(assets, request)
        } else if (request.path.startsWith("/api/")) {
            api.handle(request)
        } else {
            HttpResponse.json(apiError("E_NOT_FOUND", request.path), 404)
        }
    }
    server.webSocketHandler = { request, connection ->
        daemon.hub.add(connection)
        connection.onClose = { daemon.hub.remove(connection) }
        connection.send(
            "{\"type\":\"hello\",\"data\":{\"version\":\"" + Daemon.VERSION +
                "\",\"apiPort\":" + server.port +
                ",\"pid\":" + ProcessHandle.current().pid() +
                ",\"deviceName\":\"" + jsonEscape(options.deviceName) + "\"}}",
        )
        connection.send("{\"type\":\"state\",\"rev\":" + daemon.revision() + ",\"event\":\"hello\"}")
    }

    val port = try {
        server.start()
    } catch (e: Throwable) {
        log.event("daemon_bind_failed", mapOf("port" to options.port.toString(), "error" to e.toString()))
        System.err.println("E_PORT_IN_USE port=" + options.port + " (" + e.message + ")")
        daemon.stop()
        exitProcess(EXIT_BIND)
    }
    daemon.apiPort = port
    writePortFile(options.dataDir, port)
    log.event(
        "daemon_ready",
        mapOf(
            "api_port" to port.toString(),
            "msg_port" to daemon.snapshot().obj("daemon")?.num("msgPort").toString(),
            "bt_port" to daemon.snapshot().obj("daemon")?.num("btPort").toString(),
            "data_dir" to options.dataDir.absolutePath,
            "device_name" to options.deviceName,
        ),
    )
    println("daemon_ready api_port=" + port)

    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
        daemon.stop()
    })

    daemon.awaitStop(Long.MAX_VALUE)
    server.stop()
    daemon.stop()
    log.event("daemon_exit", mapOf("api_port" to port.toString()))
    exitProcess(0)
}

private fun serveAsset(assets: WebAssets, request: HttpRequest): HttpResponse {
    if (request.method != "GET" && request.method != "HEAD") {
        return HttpResponse.text("method not allowed", 405)
    }
    val hit = assets.get(request.path) ?: return HttpResponse.text("not found", 404)
    return HttpResponse.bytes(hit.first, hit.second)
}

private fun writePortFile(dataDir: File, port: Int) {
    try {
        File(dataDir, "daemon.port").writeText(port.toString() + "\n" + ProcessHandle.current().pid() + "\n", Charsets.UTF_8)
    } catch (_: Throwable) {
        // 端口文件只是给人看的，写不进去不影响服务
    }
}

/**
 * 把 libtorrent4j-windows 里的 x86_64 dll 释放到 <data-dir>/native/，
 * 并在 core 加载 JNI 之前设好 -Dlibtorrent4j.jni.path。
 * 外部已经用 -D 指定、且文件存在时优先用外部的（对应 core 的 m1Native 产物）。
 */
private fun prepareNative(dataDir: File, log: DaemonLogger): String {
    val existing = System.getProperty("libtorrent4j.jni.path")
    if (!existing.isNullOrEmpty() && File(existing).isFile) return existing
    val target = File(File(dataDir, "native"), "libtorrent4j.dll")
    if (target.isFile && target.length() > 0L) {
        System.setProperty("libtorrent4j.jni.path", target.absolutePath)
        return target.absolutePath
    }
    val stream = Daemon::class.java.getResourceAsStream("/lib/x86_64/libtorrent4j.dll")
        ?: throw IllegalStateException("jar 里没有 lib/x86_64/libtorrent4j.dll，也不是 Windows 原生库布局")
    target.parentFile?.mkdirs()
    stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    System.setProperty("libtorrent4j.jni.path", target.absolutePath)
    log.event("daemon_native_extracted", mapOf("path" to target.absolutePath, "bytes" to target.length().toString()))
    return target.absolutePath
}

private fun jsonEscape(text: String): String {
    val sb = StringBuilder(text.length + 8)
    for (c in text) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append(' ') else sb.append(c)
        }
    }
    return sb.toString()
}

private fun defaultDataDir(): String {
    System.getenv("HUCHUAN_DATA_DIR")?.let { if (it.isNotBlank()) return it }
    val local = System.getenv("LOCALAPPDATA")
    if (!local.isNullOrBlank()) return File(File(local, "Huchuan"), "daemon").absolutePath
    return File(System.getProperty("user.home") ?: ".", ".huchuan/daemon").absolutePath
}

private fun defaultDeviceName(): String {
    System.getenv("HUCHUAN_DEVICE_NAME")?.let { if (it.isNotBlank()) return it }
    val computer = System.getenv("COMPUTERNAME")
    if (!computer.isNullOrBlank()) return computer
    return "我的设备"
}

/** 解析 --key=value。返回 null 表示只要求打印用法。 */
fun parseOptions(args: Array<String>): DaemonOptions? {
    var dataDir = defaultDataDir()
    var port = 47200
    var msgPort = 47100
    var btPort = 51413
    var deviceName = defaultDeviceName()
    var webRoot: String? = null
    var logPath: String? = null
    var restoreSeeds = true

    for (arg in args) {
        when {
            arg == "--help" || arg == "-h" -> return null
            arg.startsWith("--data-dir=") -> dataDir = arg.substringAfter('=')
            arg.startsWith("--port=") -> port = arg.substringAfter('=').toIntOrNull()
                ?: throw IllegalArgumentException("--port 要是整数")
            arg.startsWith("--msg-port=") -> msgPort = arg.substringAfter('=').toIntOrNull()
                ?: throw IllegalArgumentException("--msg-port 要是整数")
            arg.startsWith("--bt-port=") -> btPort = arg.substringAfter('=').toIntOrNull()
                ?: throw IllegalArgumentException("--bt-port 要是整数")
            arg.startsWith("--device-name=") -> deviceName = arg.substringAfter('=').ifBlank { deviceName }
            arg.startsWith("--web-root=") -> webRoot = arg.substringAfter('=')
            arg.startsWith("--log=") -> logPath = arg.substringAfter('=').takeIf { it.isNotEmpty() && it != "-" }
            arg.startsWith("--restore-seeds=") -> restoreSeeds = arg.substringAfter('=') != "0"
            else -> throw IllegalArgumentException("未知参数 " + arg)
        }
    }
    if (port < 0 || port > 65535) throw IllegalArgumentException("--port 越界")

    val dataDirFile = File(dataDir).absoluteFile
    dataDirFile.mkdirs()
    require(dataDirFile.isDirectory) { "数据目录建不出来：" + dataDirFile }
    return DaemonOptions(
        dataDir = dataDirFile,
        port = port,
        msgPortHint = msgPort,
        btPortHint = btPort,
        deviceName = deviceName,
        logPath = logPath ?: File(dataDirFile, "daemon.log").absolutePath,
        webRoot = webRoot?.let { File(it).absoluteFile },
        restoreSeeds = restoreSeeds,
    )
}

data class DaemonOptions(
    val dataDir: File,
    val port: Int,
    val msgPortHint: Int,
    val btPortHint: Int,
    val deviceName: String,
    val logPath: String?,
    val webRoot: File?,
    /** 启动后是否把本机共享过的文件重新挂回做种（见 Daemon.restoreSeedsAsync）。 */
    val restoreSeeds: Boolean = true,
)

const val BIND_HOST = "127.0.0.1"
const val EXIT_CONFIG = 1
const val EXIT_KERNEL = 2
const val EXIT_BIND = 3
const val EXIT_NATIVE = 4

const val USAGE = """互传 daemon（M1）

  java -jar daemon.jar [选项]

  --data-dir=PATH    数据目录（默认 %LOCALAPPDATA%\Huchuan\daemon）
  --port=N           HTTP/WS 端口，默认 47200；0 = 随机
  --msg-port=N       消息层端口提示，默认 47100
  --bt-port=N        BT 端口提示，默认 51413
  --device-name=NAME 设备名（默认 COMPUTERNAME）
  --web-root=PATH    Web UI 目录（默认用 jar 内打包的 web/）
  --log=PATH         日志文件（默认 <data-dir>/daemon.log；- 表示不写文件）
  --restore-seeds=N  1 = 启动后把本机共享过的文件重新做种（默认），0 = 不碰
  -h, --help         打印本说明
"""

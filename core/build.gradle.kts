import java.io.File
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // libtorrent4j 的 Java 绑定是纯 Java（jar 里没有 .so），桌面与 Android 共用这一份；
    // 原生库两端各给各的：桌面用 libtorrent4j-windows 的 dll（m1Native 任务），
    // Android 用自编 arm64 .so（app/build.gradle.kts 的 prepareM1Natives，进 jniLibs）。
    // 不用 org.libtorrent4j:libtorrent4j-android-arm64 —— 官方 .so 在 Android 13 会 Scudo abort。
    implementation(libs.libtorrent4j)
    implementation(libs.jmdns)
}

/**
 * M1 无头夹具的运行期类路径：桌面 libtorrent4j + Windows 原生 jar + kotlin stdlib + 日志后端。
 * 单独的配置，保证这些制品不进 :app。
 */
val m1Libs: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    m1Libs(libs.libtorrent4j)
    m1Libs(libs.libtorrent4j.windows)
    m1Libs(libs.jmdns)
    m1Libs(libs.slf4j.nop)
    m1Libs(libs.kotlin.stdlib)
}

/** 把 m1Libs 解析出的 jar 拷到 core/build/m1-libs，供 scripts/m1-headless.sh 用。 */
val m1Deps = tasks.register<Copy>("m1Deps") {
    from(m1Libs)
    into(layout.buildDirectory.dir("m1-libs"))
}

/** 从 libtorrent4j-windows 里取出 libtorrent4j.dll，供 -Dlibtorrent4j.jni.path 加载。 */
val m1Native = tasks.register("m1Native") {
    dependsOn(m1Deps)
    // 路径在配置期取值，执行期只碰 File，避免 configuration cache 报「脚本对象」不可序列化。
    val libsDir = layout.buildDirectory.dir("m1-libs").get().asFile
    val outDir = layout.buildDirectory.dir("m1-native").get().asFile
    outputs.dir(outDir)
    doLast {
        val nativeJar = libsDir.listFiles { f -> f.isFile && f.name.startsWith("libtorrent4j-windows") }
            ?.firstOrNull()
            ?: error("libtorrent4j-windows jar not found in " + libsDir)
        outDir.mkdirs()
        ZipFile(nativeJar).use { zip ->
            val entry = zip.getEntry("lib/x86_64/libtorrent4j.dll")
                ?: error("lib/x86_64/libtorrent4j.dll missing in " + nativeJar.name)
            val target = File(outDir, "libtorrent4j.dll")
            zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
}

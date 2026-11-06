import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
// 必须显式 import：Gradle Kotlin DSL 里裸写 java.util.zip.ZipFile 会先撞上 project.java 扩展。
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // daemon 进程内嵌 core：只依赖 core 的公开 API，不碰 internal（core 由另一个 agent 在改，
    // 跨模块的 internal 本来就看不见，这条边界是编译器强制的）。
    implementation(project(":core"))
    implementation(libs.libtorrent4j)
    implementation(libs.libtorrent4j.windows)
    implementation(libs.jmdns)
    implementation(libs.slf4j.nop)

    // 链接分享的发送端（W1）：桌面 WebRTC。版本与 docs/spikes/webrtc-link.md 的验证版本一致。
    //
    // 主 POM 用 ${platform.classifier} 引原生包，Gradle 不认这个 Maven profile 占位符，
    // 所以关掉传递依赖、显式声明 windows-x86_64 原生 jar（spike 也是这么写的）。
    // 这里不写进 gradle/libs.versions.toml：本轮只改 daemon/，版本号就留在这条依赖旁边。
    implementation("dev.onvoid.webrtc:webrtc-java:1.0.0") { isTransitive = false }
    runtimeOnly("dev.onvoid.webrtc:webrtc-java:1.0.0:windows-x86_64")
}

application {
    mainClass.set("app.huchuan.daemon.MainKt")
    applicationName = "huchuan-daemon"
}

/**
 * 单文件可执行 jar：daemon.jar。
 * 除了自己与 core 的类，还把依赖（libtorrent4j 的 java 绑定、libtorrent4j-windows 里的
 * x86_64 dll、jmdns、kotlin-stdlib）与 Web UI 静态资源一起打进来 —— 壳只要一个 jar 就能拉起 daemon，
 * 页面也由同一个进程按同源提供（见 docs/daemon-api.md「为什么页面由 daemon 提供」）。
 *
 * Web 资源只有一个事实源：windows/resources/web（CMake 打进 exe 的也是这一份），
 * 这里只是在打包时再读一次，落进 jar 的 web/ 目录。
 */
val webRoot = rootProject.layout.projectDirectory.dir("windows/resources/web")

val daemonJar = tasks.register<Jar>("daemonJar") {
    group = "distribution"
    description = "打一个自带依赖与 Web 资源的 daemon.jar"
    archiveFileName.set("daemon.jar")
    destinationDirectory.set(layout.buildDirectory.dir("dist"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes(
            "Main-Class" to "app.huchuan.daemon.MainKt",
            "Implementation-Title" to "huchuan-daemon",
            "Implementation-Version" to "0.1.0",
        )
    }

    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        exclude("module-info.class")
    }
    from(webRoot) {
        into("web")
    }
}

// 只做一次字节码检查：daemon 不能引用 core 的 internal 成员（跨模块本来也不允许），
// 顺手确认 jar 里真的有 dll 与页面资源。
val verifyDaemonJar = tasks.register("verifyDaemonJar") {
    dependsOn(daemonJar)
    val jarFile = daemonJar.flatMap { it.archiveFile }
    doLast {
        val jar = jarFile.get().asFile
        require(jar.isFile) { "daemon.jar 不存在：" + jar }
        ZipFile(jar).use { zip ->
            val names = zip.entries().toList().map { it.name }
            val required = listOf(
                "web/index.html",
                "web/js/data.js",
                "web/js/app.js",
                "lib/x86_64/libtorrent4j.dll",
                // 链接分享（WebRTC）的原生库：缺了只能在运行期降级成「链接分享不可用」，
                // 打包时就必须失败，别等用户点了分享才发现。
                "webrtc-java-windows-x86_64.dll",
            )
            for (r in required) {
                require(names.contains(r)) { "daemon.jar 缺少 " + r }
            }
            println("[daemon] " + jar.name + " " + jar.length() + " 字节，条目 " + names.size + " 个（含 web/ 与两份 native dll）")
        }
    }
}

tasks.named("build") { dependsOn(daemonJar, verifyDaemonJar) }

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 自编 arm64 libtorrent4j.so（AGENTS.md §9）：不用 Maven 的 libtorrent4j-android-arm64 ——
 * 官方 .so 在 Android 13 触发 Scudo abort。产物由 tools/build-libtorrent-android 可复现构建，
 * 这里只做「拷进 jniLibs」，不提交二进制。
 */
// 只用纯字符串/File 常量：configuration cache 不允许闭包里引用脚本对象。
val m1NativeSoPath: String = rootProject.file("artifacts/build-libtorrent/run5-sde1/libtorrent4j.so").absolutePath

val prepareM1Natives = tasks.register<Sync>("prepareM1Natives") {
    description = "把自编 arm64 libtorrent4j.so 放进 jniLibs/arm64-v8a"
    from(m1NativeSoPath)
    into(layout.buildDirectory.dir("m1-jniLibs/arm64-v8a"))
}

/** 原生库缺失时在配置期就报错，免得打出一个没有 .so 的 APK 到真机上才发现。 */
require(rootProject.file(m1NativeSoPath).isFile) {
    "缺少自编原生库：" + m1NativeSoPath + "（先跑 tools/build-libtorrent-android 或取回 run5-sde1 产物）"
}

/**
 * release 签名（docs/release-signing.md）。keystore 与口令都在仓库外：
 * 默认读 E:/agentHome/huchuan-keys/keystore.properties，
 * 可用 -Phuchuan.keystoreProperties=<path> 或环境变量 HUCHUAN_KEYSTORE_PROPERTIES 指到别处。
 *
 * 文件 / 字段 / keystore 缺任何一样 → release 降级为不签名并打一条警告，绝不因为缺私钥让构建失败
 * （CI、别人 clone、新机器都跑得起来）。debug 构建完全不读这些，保持现状。
 *
 * 用 providers.fileContents 而不是 File().readText()：只有前者会进 configuration cache 的指纹，
 * 换 keystore 后才重新配置；裸读文件在缓存命中旧值时，会静默签成上一把 key 或者漏签。
 */
val releaseSigningPropertiesPath: String =
    providers.gradleProperty("huchuan.keystoreProperties").orNull
        ?: providers.environmentVariable("HUCHUAN_KEYSTORE_PROPERTIES").orNull
        ?: "E:/agentHome/huchuan-keys/keystore.properties"

val releaseSigning: Properties? = run {
    val text = providers
        .fileContents(objects.fileProperty().fileValue(file(releaseSigningPropertiesPath)).get())
        .asText.orNull
    if (text == null) return@run null
    val props = Properties().apply { text.reader().use { load(it) } }
    val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
        .filter { props.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) {
        logger.warn("[签名] $releaseSigningPropertiesPath 缺字段：${missing.joinToString()} → release 不签名")
        return@run null
    }
    val storeFile = file(props.getProperty("storeFile").trim())
    if (!storeFile.isFile) {
        logger.warn("[签名] keystore 不存在：$storeFile（来自 $releaseSigningPropertiesPath）→ release 不签名")
        return@run null
    }
    props
}

if (releaseSigning == null) {
    logger.warn(
        "[签名] 没有可用的 release 签名材料：读不到 $releaseSigningPropertiesPath。" +
            " release 构建将产出未签名 APK（app-release-unsigned.apk）；debug 构建不受影响。" +
            " 生成与放置见 docs/release-signing.md。"
    )
}

android {
    namespace = "app.huchuan"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.huchuan"
        minSdk = 26
        // Android 17（API 37）：本地网络（mDNS / 局域网 TCP-UDP）要运行时权限
        // android.permission.ACCESS_LOCAL_NETWORK，未授权时被系统静默拦。
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // 只有自编 arm64 这一个 ABI；混进别的 ABI 会让系统挑错 .so。
            abiFilters += "arm64-v8a"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("m1-jniLibs").get().asFile)

    packaging {
        jniLibs {
            // 自编 .so 已经是 strip 过的（符号在单独的 .debug 里），不让 AGP 再动它。
            keepDebugSymbols += "**/libtorrent4j.so"
        }
    }

    // release 用仓库外 keystore 签名；没有签名材料时这一块不建，release 保持 AGP 默认（不签名）。
    // debug 完全不碰：继续用默认 debug keystore（~/.android/debug.keystore）。
    signingConfigs {
        val signingMaterial = releaseSigning
        if (signingMaterial != null) {
            create("release") {
                storeFile = file(signingMaterial.getProperty("storeFile").trim())
                storePassword = signingMaterial.getProperty("storePassword")
                keyAlias = signingMaterial.getProperty("keyAlias").trim()
                keyPassword = signingMaterial.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            val signingMaterial = releaseSigning
            if (signingMaterial != null) signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

// jniLibs 目录由 prepareM1Natives 生成，合并原生库之前必须先跑它。
tasks.matching { it.name.startsWith("merge") && (it.name.endsWith("JniLibFolders") || it.name.endsWith("NativeLibs")) }
    .configureEach { dependsOn(prepareM1Natives) }

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    // 链接分享（P8/P9）：信令 WebSocket（OkHttp）+ 二维码（zxing）。
    // 不进版本目录：这两项只服务 app，不污染 core/daemon 的依赖表。
    //
    // 这里**没有** io.github.webrtc-sdk：分享端整条 WebRTC 链路改成系统 WebView 承载
    // （link/LinkWebSender + assets/link/sharer.js），APK 少掉 12.3 MB 的
    // libjingle_peerconnection_so.so 与约 12.3 MB 的 AAR 类。取舍见 docs/link-share-webview.md。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // zxing 只用来出 BitMatrix，二维码由 Compose Canvas 自己画（link-share.md §4.2）。
    implementation("com.google.zxing:core:3.5.3")
}

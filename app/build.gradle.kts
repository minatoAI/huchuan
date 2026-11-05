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

android {
    namespace = "app.huchuan"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.huchuan"
        minSdk = 26
        targetSdk = 36
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
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ============================================================
// marketwatch —— 独立小 App「市场/竞品监控」（与 Arix 分开，另一个 applicationId）。
// 纯 WebView：Kotlin 拉数据 → 拼出与电脑版 competitor_watch.py --html 一样的仪表盘 → 塞进 WebView。
// 无 Compose、无 androidx，只用 Android 框架 Activity + WebView，依赖最小、编译快。
// ============================================================
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.arix.marketwatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.arix.marketwatch"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

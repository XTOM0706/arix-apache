import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.arix.stt"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        testOptions {
            targetSdk = 34
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        targetSdk = 34
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // 语音通话的 barge-in 要求 TTS 走通话音频流(否则平台 AEC 拿不到参考流、消不掉回声)。
    // 音频路由开关放在 cloudapi(零本地依赖的底层)，stt 与 cloudapi 里的 TTS 引擎共用同一份。
    implementation(project(":cloudapi"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
}

// 语音模型**不再随包携带**：以前这里有个 downloadSttModel 任务挂在 preBuild 上，
// 每次构建都把 26MB 的 zipformer 中文模型灌进 assets（主 APK 白涨 26MB，而默认引擎是云端识别）。
// 现改为**运行时按需下载**——用户在「语音识别」页选本地引擎时才下，
// 直接从上游原厂拿（k2-fsa/sherpa-onnx release，带 ghfast/hf-mirror 回退），见 SttModelManager。

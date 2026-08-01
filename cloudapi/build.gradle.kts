import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.arix.cloudapi"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 34

        consumerProguardFiles("consumer-rules.pro")
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
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    // 不要加 org.json:json —— JSON License 带 "shall be used for Good, not Evil" 附加限制，
    // 与 AGPL-3.0 不兼容；且 org.json 在 Android 上是平台自带 API，这条依赖本就多余。
    implementation(libs.coroutines.android)
}

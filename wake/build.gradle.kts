/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Arix wake module build script. The module is being rewritten clean-room to
 * Apache-2.0 (see wake/LICENSE and wake/NOTICE). Some Operit-derived LGPL files
 * remain during the migration (removed through phases P3-P7).
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.arix.wake"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    // ⚠ 这个版本号不是随便挑的：整套 ORT 必须**符号版本一致**。
    // stt 模块的 `libsherpa-onnx-jni.so` 在 .gnu.version_r 里写死要 `libonnxruntime.so` 的
    // `VERS_1.24.3`，本 aar 带的 `libonnxruntime4j_jni.so` 也要同一个版本。对不上就是 dlopen
    // "cannot locate symbol OrtGetApiBase"（当年 1.17.1 配 1.24.3 就是这么炸的）。
    //
    // ⛔ **2026-07-28 试过换成"sherpa 自建的 1.27.0"省 3.95MB，真机炸了，已回退。别再试第二遍。**
    //   做法是：stt/jniLibs 换成 sherpa v1.13.4 的 jni + 他们自建的 libonnxruntime.so(20.68MB)，
    //   wake 这条升到 1.27.0。动手前静态核对全过：两边都定义 VERS_1.27.0、jni 导出的 131 个方法与
    //   原来完全一致、4j_jni 要的 OrtGetApiBase/CPU/Nnapi 三个符号都在、打包出来的 APK 里两个文件也配对。
    //   **但真机上一用语音就整个进程闪退**（native 层挂掉，Java 崩溃处理器抓不到、也没崩溃弹窗）。
    //   → 结论：符号表对得上 ≠ 能跑。ORT 的构建选项（算子集/EP 组合）不是符号级能验的东西，
    //     换 ORT 构建**只能靠真机验**。省这 4MB 不值当拿语音链去赌。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    // L2 KWS 判决器（microWakeWord INT8 tflite 流式模型）运行时。见 DESIGN-WAKE.md §4。
    // ⚠ compileOnly：**运行时不打进包**（`libtensorflowlite_jni.so` 一个就 3.24MB）。
    // 理由不是"用的人少"，而是**现在一行都跑不到**：`wake/src/main/assets/models/` 是空目录、
    // 全项目零个 .tflite，KwsDetector.create 必然失败、恒回退 EmbeddingPrototypeDetector。
    // hi_xtom.tflite 训出来的那天，模型和这个运行时**一起按需下载**（走 RemoteAssets 那条路），
    // 比现在空占 3.24MB 强。KwsDetector 的代码原样留着，编译照过。
    // ⚠ 类缺失时抛的是 NoClassDefFoundError（Error，不是 Exception）——接它的地方在 WakeEngines.detectorFor，
    //   catch 必须是 Throwable。改回 implementation 时这两处注释也一并改。
    compileOnly("org.tensorflow:tensorflow-lite:2.16.1")
    implementation(libs.androidx.core.ktx)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // 不要加 org.json:json —— 它的 JSON License 带 "shall be used for Good, not Evil" 附加限制，
    // 非 OSI 认证、与 Apache-2.0 不兼容（Debian/Fedora/Google 都封）。而且在 Android 上纯属多余：
    // org.json 是平台自带 API，编译期由 android.jar 提供，删掉这条依赖代码一个字都不用改。
    implementation(libs.kotlinx.serialization)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ============================================================
// 正式签名密钥（与 terminal/build.gradle.kts 同一套读法，**必须是同一把钥匙**）
//
// 密钥文件与口令一律不入库：从 local.properties（已 gitignore）或环境变量读，
// 读不到则回落 debug key —— 拿不到私钥的人照样能编译出 release 包。
//
// ⚠️ 终端的 `BIND_TERMINAL` 是 signature 级权限：主 App 与终端签名不一致就绑不上服务，
// 而这在编译期完全看不出来，只在运行时表现为「终端功能整个不工作」。
// ⚠️ 换钥匙会让已装的旧版无法覆盖升级，用户必须先卸载。
// ============================================================
private val arixKeyProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

private fun arixSecret(key: String, env: String): String? =
    (arixKeyProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

private val arixStoreFile = arixSecret("arix.storeFile", "ARIX_STORE_FILE")
    ?.let { rootProject.file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.arix.app"
    compileSdk = 36
    // AIDL：绑定独立终端 App 的 ITerminalService（跨进程驱动 proot 终端）
    buildFeatures { aidl = true }

    signingConfigs {
        if (arixStoreFile != null) create("arixRelease") {
            storeFile = arixStoreFile
            storePassword = arixSecret("arix.storePassword", "ARIX_STORE_PASSWORD")
            keyAlias = arixSecret("arix.keyAlias", "ARIX_KEY_ALIAS") ?: "arix"
            keyPassword = arixSecret("arix.keyPassword", "ARIX_KEY_PASSWORD")
            // v1 关掉：minSdk 26，v1(JAR 签名) 只有 API<24 才需要，留着白白拖慢打包、还多一份可被篡改的清单。
            enableV1Signing = false
            // v2 必须开：这是 Android 7+ 的整包完整性校验。
            enableV2Signing = true
            // ⭐ v3 必须开，理由不是"更安全"而是**留退路**：密钥轮换（rotation）只在 v3 里有。
            // 不开 v3，这把钥匙就是终身制——万一日后泄露或需要更替，除了让所有用户卸载重装之外没有别的路。
            // 实测默认没开（apksigner verify 显示 v3 scheme: false），所以这里显式打开。
            enableV3Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.arix.app"
        minSdk = 26
        // targetSdk 钉在 28：让 App 落在旧 SELinux 域(untrusted_app_28)，保留「execve 自己数据目录里的二进制」权限，
        // 从而能在进程内跑 proot + 完整 Ubuntu（Operit/Termux/ZeroTermux 同款做法）。代价仅是上不了 Google Play——本项目侧载分发，无所谓。
        // compileSdk 仍 36：照常编译新 API；只是运行时行为按 28（多为「解除限制」，利好本工具型 App）。
        targetSdk = 28
        // ⚠️ versionName 必须与 release 的 tag 用**同一套数字方案**（tag 写 v0.1.0）。
        // 检查更新是拿 tag 和它逐段比数字的（见 UpdateChecker.isNewer）。
        // 之前用过日期快照 tag(snapshot-2026-08-01)，比出来 2026 > 0.x 永远误报/版本对不上——
        // 已回到 semver：versionName=0.2.0，tag=v0.2.0，逐段相等才判「已是最新」。
        versionCode = 7
        versionName = "0.2.2"
        // 只打包 arm64-v8a 原生库：去掉 x86/x86_64/armeabi-v7a 三份 .so（约 -44MB）。
        // 不丢任何 App 功能；仅不支持 x86 模拟器与极老的 32 位设备。要支持模拟器就删掉这行。
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // 开 R8：主 App 此前从没打过 release，minify 一直关着——这是颗没拆的雷（R8 最爱炸反射/JNI/序列化，我们三样都有）。
            // 规则见 app/proguard-rules.pro，逐条核过"到底谁是按名字被找到的"，没有大面积 keep。
            // ⚠ 资源压缩(shrinkResources)没开：本项目有大量按名字取的 raw/assets（模型、脚本、字体），风险大于收益。
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 配了正式密钥就用它，没配则回落 debug（本地能装，但别拿去分发）。见文件顶部那段。
            signingConfig = signingConfigs.findByName("arixRelease") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // BouncyCastle 的**后量子算法**参数表：release 包里实打实占 7.79MB（lowmc 那几张表最大，
            // 还有 sike p751/p610/p503）。它是 PDFBox 依赖 BC 顺带拖进来的，而我们只用到 PDF 加密那条路
            // （RSA/AES），后量子算法一行都不会走到——纯白带。R8 只剪代码不剪资源，所以得在这儿排。
            // ⚠ 排的是 `org/bouncycastle/pqc/**` 下的**资源**，不是 BC 本身；PDF 解析/解密不受影响。
            excludes += "org/bouncycastle/pqc/**"
        }
        jniLibs {
            // 2026-07-28：`libsherpa-onnx-c-api.so`(4.20MB) 与 `libsherpa-onnx-cxx-api.so`(0.42MB) 已删。
            // 依据（读 .so 里的 soname 字符串核过）：我们唯一 dlopen 的是 `sherpa-onnx-jni`（LibraryUtils.LIB_NAME），
            // 而 **jni 只引用 libonnxruntime.so、不引用 c-api**；c-api/cxx-api 那两个是给 C/C++ 调用方用的另一套门面，
            // 整个项目没有任何 native 代码链它们。cxx-api 引用 c-api，两个一起走，不留悬空依赖。
            pickFirsts += setOf(
                "lib/arm64-v8a/libonnxruntime.so",
                "lib/arm64-v8a/libsherpa-onnx-jni.so"
            )
        }
    }

    lint {
        // targetSdk 28 是本项目刻意为之（旧 SELinux 域跑 proot），不是漏更新 → 关掉这条否则 release 被 lintVital 拦死。
        disable += "ExpiredTargetSdkVersion"
    }

    // 终端 App **不内置**进主包（否则主 APK 白涨 33MB）：终端页点安装时从 GitHub Release
    // 按需下载（走镜像回退），见 TerminalInstaller。
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Compose 稳定性配置：把跨模块 / 含 List·Map 字段的只读数据类（会话/记忆/角色卡实体、
// API 监控快照、Operit 市场包）强制标为稳定，让持有它们的列表行 composable 能按结构相等跳过重组。
// 对齐聊天页给 ChatBubble 标 @Immutable 的做法；用配置文件避免纯 data/cloudapi 模块凭空依赖 compose-runtime。
composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
}

dependencies {
    // 经典 Xposed API：仅编译期（桩模块），运行时由 LSPosed 提供真类，不打进 APK。
    // 用于 com.arix.app.xposed.FocusUnlockHook（解锁超级岛焦点通知自定义）。
    // 暂停(2026-07-23)：FocusUnlockHook 已逐行注释、settings 里 :xposed-api-stub include 也已注释；恢复时一起放开。
    // compileOnly(project(":xposed-api-stub"))
    implementation(project(":wake"))
    implementation(project(":cloudapi"))
    implementation(project(":stt"))
    implementation(project(":data"))
    implementation(project(":logic"))   // 纯逻辑（token 估算/模糊匹配）：无 Android 依赖，可在 JVM 上单测
    implementation(libs.work.runtime.ktx) // WorkManager：主动消息定时后台生成
    // Baseline Profile 运行时安装器：把打进 APK 的基线配置(src/main/baseline-prof.txt)在启动时装进 ART，
    // 让热点路径(启动/首帧/滚动)提前 AOT 编译 → 少 JIT 抖动。仅对 release 生效(debug 不走)。targetSdk=28 必须带它。
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit) // 隐身浏览器：文档加载前注入反检测脚本(addDocumentStartJavaScript)
    implementation(libs.androidx.appcompat)
    implementation(libs.activity.compose)
    implementation(libs.zxing.core) // QR 配置导入导出（编解码，纯 Java）
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.jlatexmath)
    // PDF 文本层提取（DocReadTool：含文本层的 PDF 直接抽字，跳过慢的逐页视觉OCR；扫描件才回退）
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Shizuku（ADB 级特权，无需 root）：用于把录音 appop 强制允许 → 后台持续持麦
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // Health Connect（健康连接）：统一读小米/vivo/Keep/Google Fit 等写入的步数/心率/睡眠，作为第三健康源
    implementation("androidx.health.connect:connect-client:1.1.0-rc02")
    // 视频：改走各站公开端点（B站 WBI 签名 playurl 拿直链、YouTube oembed 拿信息），
    // 不再用 yt-dlp（脱 GPL-3.0、瘦身 ~20M、去掉内置 Python 首次解压的慢/不稳）。

    // ── 单测（JVM，不需要设备）──
    // 只给**踩过的坑**补，不追覆盖率：262 个源文件 0 测试的现状下，先把那些"栽过一次、还会再栽"的
    // 纯函数钉住（工具消息配对、参数校验、上下文裁剪、token 估算、模糊匹配）。
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // JVM 单测里的 org.json 实现：AGP 那份 mockable android.jar 里 org.json 全是抛异常的桩，
    // 测 ToolArgValidator/MemEdges 这些吃 JSONObject 的东西必须有真实现。
    // 用 AOSP 那份(Apache-2.0)，**不是** org.json 官方包——后者的 "Good, not Evil" 条款与 AGPL-3.0 不兼容。
    // 仅测试期生效，不进 APK。
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
}

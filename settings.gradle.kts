pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://jitpack.io") }
        google()
    }
}

rootProject.name = "Arix"
include(":wake")
include(":stt")
include(":tts")
include(":cloudapi")
include(":data")
include(":logic")          // 纯 Kotlin/JVM：不依赖 Android 的逻辑（可单测、将来鸿蒙可复用）
// :tools / :mcp / :persona 三个模块已删（2026-07-28）：早先重构计划的残留，三个都是**一个 .kt 都没有**的空
// Android library 壳，没人依赖，只白占构建时间（Q49）。注意 `tools/` 目录本身还在——里面是 token_cost.py /
// i18n_*.py / competitor_watch.py 这些真在用的脚本，删掉的只是它的 gradle 模块身份。
include(":app")
include(":marketwatch")
include(":terminal")
include(":terminal-emulator")   // vendored Termux 终端组件(Apache-2.0)
include(":terminal-view")
// include(":xposed-api-stub")   // 暂停(2026-07-23)：连同 app 侧 FocusUnlockHook / manifest xposed meta 一并停用；恢复时三处一起放开
// include(":xposed")            // 暂停：stage-B root 增强层(LSPosed/按键唤醒)未完成——libxposed 依赖坐标(io.github.libxposed:api)
                                 // 是占位版本号解析不到、hook 常量还是 TODO，会挡整个构建/提交。做⭐3 时填对依赖再启用。独立 APK、与 :app 无编译依赖。

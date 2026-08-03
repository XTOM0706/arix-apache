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
// :marketwatch（竞品/产品监控小 App，独立 APK）在 Apache-2.0 版已移除：其用途是配合
// competitor_watch.py 监视竞品市场，属「监视他人」类工具，不纳入开源版。
// 终端线（terminal / terminal-emulator / terminal-view）在 Apache-2.0 版已移除：
// 其依赖 Termux bootstrap / proot(GPL) 等组件，与 Apache-2.0 目标冲突，整条线不纳入。

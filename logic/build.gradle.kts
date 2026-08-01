plugins {
    // ⚠ 不能用 alias(libs.plugins.kotlin.jvm)：Kotlin 插件已经被别的模块带上了 classpath，
    // 再按版本请求一次会报「already on the classpath with an unknown version」。不带版本地 apply 即可。
    kotlin("jvm")
}

// ============================================================
// :logic —— **纯 Kotlin/JVM** 模块（不是 Android library）。
//
// 这是「分层试点」的第一块：把不依赖 Android 的纯逻辑搬出来，验证两件事同时成立——
//  ① **可测**：262 个源文件 0 测试的根因之一是「什么都缠着 Context」。纯 JVM 模块里的东西
//     不需要设备、不需要 Robolectric，`./gradlew :logic:test` 秒级跑完。
//  ② **可移植**：将来做鸿蒙协同端时，这里的东西是唯一能原样复用的部分。
//     「为鸿蒙做的分层」和「为可测试性做的分层」本来就是同一件事。
//
// 准入门槛只有一条：**不 import 任何 android.\* / androidx.\***。做不到就别往这搬——
// 强搬进来再塞一堆接口抽象，只会把复杂度换个地方放。
// 包名保持 com.arix.tool 不变：搬家不改调用方一行代码。
// ============================================================
kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // org.json —— **只编译期可见，不进产物**。
    // ToolArgValidator 吃 JSONObject/JSONArray，而 org.json 在 Android 上是**平台自带 API**：
    // 真到运行时由系统提供实现。这里若写成 implementation，这份 jar 会被 :app 传递依赖打进 APK，
    // 跟平台自带的同名类撞车（重复类/行为漂移），所以必须是 compileOnly——给编译器看，不给打包器看。
    compileOnly("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")

    testImplementation(libs.junit)
    // 单测要真跑 JSON，而 compileOnly 不会进测试运行时类路径（testImplementation 只继承 implementation），
    // 所以这里再给测试期补一份实现。
    // ⚠ 用 AOSP 那份(Apache-2.0)，**不是** org.json 官方包 org.json:json —— 后者的 JSON License 带
    //   "shall be used for Good, not Evil" 附加限制，与本项目的 AGPL-3.0 不兼容（cloudapi 的注释里已拒过一次）。
    testImplementation("com.vaadin.external.google:android-json:0.0.20131108.vaadin1")
}

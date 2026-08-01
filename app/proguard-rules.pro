# :app 的 R8 规则
#
# 开 minify 的动机：主 App 从没打过 release（isMinifyEnabled 一直是 false），这是一颗没拆的雷——
# R8 最爱炸的是反射/JNI/序列化，而这三样我们都有。与其等发布那天现炸，不如现在拆。
#
# 判断原则：**只 keep 真正"按名字被找到"的东西**。keep 得太宽等于没开 R8。
# 逐条核过一遍：
#  - 反射（LiveCapsuleController / ShizukuMic / DeviceTools / XtomPrivilegedService）打的全是
#    **系统框架类**（android.app.Notification$ProgressStyle 等）与 Shizuku 自带库，R8 不动框架，不用 keep。
#  - 数据序列化全走手写 org.json（没有按字段名反射的 Gson/Moshi/kotlinx-serialization），所以实体不用 keep。
#  - Room 生成的是直接调用的代码，不走反射；DAO/Entity 由生成代码引用，R8 自然保活。

# ── JNI：native 方法名与其所在类被 C 侧按名字回调，改名即 UnsatisfiedLinkError ──
-keepclasseswithmembernames class * {
    native <methods>;
}
# sherpa-onnx（唤醒词/离线 STT/TTS）：Java 侧类与字段被 JNI 按签名访问
-keep class com.k2fsa.sherpa.onnx.** { *; }
# ONNX Runtime 的 Java API：native 侧会**按名字 new 出这些 Java 对象**（FindClass + GetMethodID），
# 不 keep 就会在 R8 改名后炸——而且炸法极难查：
#   `NoSuchMethodError: Lai/onnxruntime/NodeInfo;.<init>(Ljava/lang/String;Lai/onnxruntime/ValueInfo;)V`
#   → JNI 层 abort（SIGABRT），Java 崩溃处理器抓不到、系统也不弹「应用已停止」，
#     表现就是**一用语音整个进程闪退、日志里什么都没有**。2026-07-28 真机踩到过一次。
# ⚠ 上面那条 `-keepclasseswithmembernames native <methods>` 救不了：它只保住带 native 方法的类
#   （NodeInfo/OrtSession 恰好带，所以名字还在），但 TensorInfo/ValueInfo 这些**纯数据类**没有 native
#   方法，照样被改名（实测 mapping.txt 里 `ai.onnxruntime.TensorInfo -> b.k`）——而它们出现在
#   构造函数签名里，签名一变 GetMethodID 就找不到。整包 keep 才是对的，代价不到 100KB。
-keep class ai.onnxruntime.** { *; }

# ── AIDL：跨进程绑定终端 App 的服务，Stub/Proxy 的内部结构不能动 ──
-keep class com.arix.terminal.ITerminalService { *; }
-keep class com.arix.terminal.ITerminalService$* { *; }
-keep class com.arix.terminal.ITerminalCallback { *; }
-keep class com.arix.terminal.ITerminalCallback$* { *; }
# 我们自己暴露给外部的特权 AIDL 同理
-keep class com.arix.app.IXtomPrivileged { *; }
-keep class com.arix.app.IXtomPrivileged$* { *; }

# ── 系统按类名实例化的组件（清单里声明的 AGP 会自动 keep，这里补 WorkManager 这类不在清单里的）──
-keep class * extends androidx.work.ListenableWorker { <init>(...); }
-keep class * extends android.content.ContentProvider

# ── 三方 ──
# PDFBox-Android：靠资源/反射装配字体与解析器
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**
# Shizuku：provider 与 binder 回调按名字用
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
# Health Connect / ZXing / jlatexmath：静默 + 保留入口
-dontwarn androidx.health.connect.**
-keep class com.google.zxing.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**
# TFLite：wake 模块里是 compileOnly（KWS 模型还没训出来，运行时不打进包，见 wake/build.gradle.kts）。
# KwsDetector 引用它的类而包里没有 → R8 必然报 missing class，这里静默。
# ⚠ 只静默、不 keep：keep 一个不存在的类没有意义，缺类是**运行时**由 WakeEngines 兜 Throwable 回退的。
-dontwarn org.tensorflow.lite.**
# OkHttp/Okio/协程 的常规静默项
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn kotlinx.coroutines.**

# ── 崩溃栈要能读（否则 release 崩了只有混淆名，等于没有栈）──
-keepattributes SourceFile,LineNumberTable,Signature,*Annotation*
-renamesourcefileattribute SourceFile

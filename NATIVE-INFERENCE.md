# 内嵌原生推理（离线跑 GGUF 模型）装配说明

Arix 的 `local_infer` 工具能在**手机本地、完全离线**用 llama.cpp 跑 GGUF 模型。
代码层的脚手架（`NativeInference.kt` / `LocalModelPrefs` / `LocalInferTool`）已就位，但**真正的推理由一个原生库 `libxtomllm.so` 提供，本仓库不含**（需为 arm64-v8a 另编）。放好 `.so` + 模型即可离线跑。

## 两步装配

### 1) 原生库 `libxtomllm.so`
为 **arm64-v8a** 编译 llama.cpp + 下面这层 JNI 包装，产物放到：
```
app/src/main/jniLibs/arm64-v8a/libxtomllm.so
```
库名必须是 `xtomllm`（`System.loadLibrary("xtomllm")`）。必须导出这三个 JNI 符号（对应 `com.arix.tool.NativeInference` 的 external 方法）：

```c
// handle = 加载模型，失败返回 0
jlong Java_com_arix_tool_NativeInference_nativeInit(
    JNIEnv* env, jobject thiz, jstring modelPath, jint nCtx, jint nThreads);

// 同步生成，返回完整文本
jstring Java_com_arix_tool_NativeInference_nativeGenerate(
    JNIEnv* env, jobject thiz, jlong handle, jstring prompt, jint maxTokens, jfloat temperature);

// 释放
void Java_com_arix_tool_NativeInference_nativeFree(
    JNIEnv* env, jobject thiz, jlong handle);
```
`handle` 建议是 `reinterpret_cast<jlong>` 的 `llama_context*`（或自定义结构体指针）。
编译参考 llama.cpp 官方 Android 构建（NDK + CMake，`-DLLAMA_NATIVE=OFF`，arm64 开 `LLAMA_OPTIMIZE`/NEON）。也可直接复用 llama.cpp 自带的 `llama-android` JNI，把符号名改成上面的即可。

### 2) 模型文件 `.gguf`
把量化好的 GGUF 模型（如 Qwen2.5-1.5B-Instruct-Q4_K_M.gguf 这类手机能跑的小模型）放到应用私有目录：
```
<filesDir>/models/xxx.gguf
```
拷入方式：手机文件管理器 / Termux `cp` / 或让 AI 用 `http_request` 下载到该目录。
多个模型时，活动模型可由 `LocalModelPrefs.setActive(name)` 选（默认取目录里第一个）。

## 装好之后
- `local_infer` 工具即可用：完全离线、隐私、断网可用。
- 没装 `.so` → 工具提示放库的路径；没模型 → 提示放模型的路径。都不崩。

## 为什么不直接内置
`.so` + 模型体积大、且需针对 NDK/CPU 特性编译，塞进仓库不合适（同 Termux bootstrap、STT 模型的处理方式：脚手架进仓库，二进制装配时另放）。

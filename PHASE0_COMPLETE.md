# Phase 0: Foundation — Complete

**完成日期：** 2025-06-25

**状态：** ✅ 已完成，`:wake` 模块编译通过

## 交付物清单

| # | 交付物 | 文件 | 状态 |
|---|--------|------|------|
| 1 | Arix repo 创建 | `C:\Users\XTOM\OnyxAI\` | ✅ |
| 2 | Gradle 编译配置 | `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | ✅ |
| 3 | `:wake` 库 — ONNX Silero VAD | `wake/.../OnnxSileroVad.kt` | ✅ |
| 4 | `:wake` 库 — MFCC 特征提取 | `wake/.../PersonalWakeFeatureExtractor.kt` | ✅ |
| 5 | `:wake` 库 — DTW 唤醒监听 | `wake/.../PersonalWakeListener.kt` | ✅ |
| 6 | `:wake` 库 — 唤醒词注册 | `wake/.../PersonalWakeEnrollment.kt` | ✅ |
| 7 | SpeechService 接口 (STT/TTS) | `wake/.../SpeechService.kt` | ✅ |
| 8 | AIService 接口 (LLM 抽象) | `wake/.../AIService.kt` | ✅ |
| 9 | 验证清单 | `VERIFICATION_CHECKLIST.md` (52项，全部待真机) | ✅ |

## 依赖关系

- **ONNX Runtime:** `com.microsoft.onnxruntime:onnxruntime-android:1.17.1`
- **Coroutines:** `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3`
- **Serialization:** `libs.kotlinx.serialization` (version catalog)

## 提交记录 (11 commits)

```
ccf6330 [wake] Fix: add coroutines dependency
e468f03 [root] Add build.gradle.kts
b42ad3f [root] Add settings.gradle.kts
13dfe62 [wake] Add SpeechService interface
c8577f8 [wake] Add AIService interface
4cf239e [wake] Add build.gradle.kts
6be138d [wake] Add PersonalWakeEnrollment
31b83f0 [wake] Add PersonalWakeListener
2faf491 [wake] Add PersonalWakeFeatureExtractor
0eebe85 [wake] Add OnnxSileroVad
f81e302 Initial snapshot: gradle files + verification checklist
```

## 下一步

→ **Phase 1: Voice Core**
- 实现 `WakePipeline` (常驻 DTW + 激活态 Sherpa-ncnn STT)
- 实现 `XtomBackendBridge` (云端 API client)
- 实现 `VoiceInteractionActivity` (完整语音交互)
- 实现 `AudioFeedbackSystem`

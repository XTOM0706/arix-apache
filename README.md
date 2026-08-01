# Arix（Apache-2.0 精简版）

把 Arix 嵌进你自己的产品/系统的干净版本。**Apache License 2.0**，二次开发与内置无门槛。

## 这是什么

Arix 是一款手表/手机上的 AI 助手：对话、记忆、本地语音、文件、工具、MCP。

本仓库是 **Apache-2.0 精简版**，面向二次开发与内置：

- 只保留核心 AI 助手能力：对话、记忆（纯文件存储）、本地 STT/TTS、语音唤醒、
  文件工具、RAG、搜索、浏览器、工具系统、MCP、Home Assistant、S3/MinIO 备份等
- 网络接口只保留**自建可控端点**：OpenAI 兼容协议（可接本地 llama.cpp / Ollama / vLLM）、
  Home Assistant、MCP、S3/MinIO；第三方厂商闭源云 API 预设已移除
- 移除了部分独有能力：超级岛胶囊、语音通话、角色扮演、记忆图谱、云端市场、终端等

想要**完整功能**（超级岛、语音通话、角色扮演、记忆图谱、终端、云端市场等），
请使用 **GPL 满血版**：<https://github.com/XTOM0706/arix-app>

## 二次开发 / 内置

- 许可：Apache-2.0，可商用、可修改、可分发（保留版权声明与 NOTICE）
- 记忆存储在 `ai_workspace/memory.json`（纯文件，方便直接读写/迁移）
- 工具系统通过 `PackageManager` 注册功能包，AI 通过 `ToolManager` 调用
- 模块结构：`app`（UI/聊天）、`cloudapi`（OpenAI 兼容客户端）、`data`（Room DB）、
  `logic`（纯 JVM 逻辑）、`wake`（语音唤醒）、`stt`（本地语音识别）、`tts`（本地语音合成）

## 构建

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 配置模型

使用 OpenAI 兼容协议。自带本地部署预设（Ollama / LM Studio / llama.cpp / vLLM），
也可在配置页填任意 OpenAI 兼容的 base URL。

## 许可

Apache License 2.0，见 [`LICENSE`](LICENSE)。第三方组件见 [`NOTICE`](NOTICE)。

---

<sub>Arix · Apache-2.0 精简版 · 完整版见 GPL 满血仓库</sub>

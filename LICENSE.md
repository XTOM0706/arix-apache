# Arix 许可 / License

Arix is licensed under the **Apache License 2.0**. The full license text is in [`LICENSE`](LICENSE).

Arix 采用 **Apache 许可证 2.0**。完整条款见 [`LICENSE`](LICENSE)。

---

## 这是什么版本 / What this version is

这是 **Apache-2.0 精简版**，面向二次开发与内置（把 Arix 嵌进你自己的产品/系统）：

- 只保留核心的 AI 助手能力（对话、记忆、本地语音、文件、工具、MCP 等）
- 移除了部分独有能力（超级岛胶囊、语音通话、角色扮演、记忆图谱、云端市场、终端等）
- 网络接口只保留**自建可控端点**：OpenAI 兼容协议（可接本地/自建 llama.cpp / Ollama / vLLM）、
  Home Assistant、MCP、S3/MinIO 等；第三方厂商闭源云 API 预设已移除

想要**完整功能**（超级岛、语音通话、角色扮演、记忆图谱、终端、云端市场等），
请使用 **GPL 满血版**：`https://github.com/XTOM0706/arix-app`

This is the **Apache-2.0 slim edition**, aimed at secondary development and embedding:
- Keeps the core AI-assistant capabilities (chat, memory, local speech, files, tools, MCP, etc.)
- Removed some exclusive abilities (Super Island capsule, voice calls, role-play, memory graph,
  cloud marketplace, terminal, etc.)
- Network interfaces keep only self-hostable endpoints: OpenAI-compatible protocol
  (local llama.cpp / Ollama / vLLM), Home Assistant, MCP, S3/MinIO, etc.

For the **full-featured GPL edition**, see: `https://github.com/XTOM0706/arix-app`

---

## 第三方组件 / Third-party components

见 [`NOTICE`](NOTICE)。各组件版权与许可归其作者所有。

See [`NOTICE`](NOTICE). Third-party components remain under their own licenses.

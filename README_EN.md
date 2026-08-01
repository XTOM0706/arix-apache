# Arix (Apache-2.0 slim edition)

A clean build of Arix for embedding into your own product/system. **Apache License 2.0** —
no barriers to secondary development or integration.

## What this is

Arix is an AI assistant for watches and phones: chat, memory, local speech, files, tools, MCP.

This repository is the **Apache-2.0 slim edition**, aimed at secondary development and embedding:

- Keeps the core AI-assistant capabilities: chat, memory (plain-file storage), local STT/TTS,
  wake word, file tools, RAG, search, browser, tool system, MCP, Home Assistant, S3/MinIO backup, etc.
- Network interfaces keep only **self-hostable endpoints**: OpenAI-compatible protocol
  (local llama.cpp / Ollama / vLLM), Home Assistant, MCP, S3/MinIO. Third-party vendor
  closed-source cloud API presets are removed.
- Removed some exclusive abilities: Super Island capsule, voice calls, role-play, memory graph,
  cloud marketplace, terminal, etc.

For the **full-featured GPL edition** (Super Island, voice calls, role-play, memory graph,
terminal, cloud marketplace, etc.): <https://github.com/XTOM0706/arix-app>

## Development / embedding

- License: Apache-2.0 — use, modify, distribute freely (keep copyright notice and NOTICE).
- Memory is stored in `ai_workspace/memory.json` (plain file, easy to read/migrate).
- The tool system registers feature packages via `PackageManager`; AI invokes them via `ToolManager`.
- Modules: `app` (UI/chat), `cloudapi` (OpenAI-compatible client), `data` (Room DB),
  `logic` (pure JVM), `wake` (wake word), `stt` (local speech recognition), `tts` (local TTS).

## Build

```bash
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Model configuration

Uses the OpenAI-compatible protocol. Ships local-deployment presets (Ollama / LM Studio /
llama.cpp / vLLM); you can also enter any OpenAI-compatible base URL.

## License

Apache License 2.0, see [`LICENSE`](LICENSE). Third-party components see [`NOTICE`](NOTICE).

---

<sub>Arix · Apache-2.0 slim edition · Full version at the GPL repository</sub>

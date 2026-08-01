package com.arix.app

// 内置 API 服务商预设 —— Apache-2.0 精简版。
// 只保留**自建可控端点**：本地部署（Ollama / LM Studio / llama.cpp / vLLM 等 OpenAI 兼容服务）。
// 第三方厂商云 API 预设（DeepSeek/Kimi/GLM/Qwen/OpenAI/Anthropic/Groq/xAI/OpenRouter 等）已移除——
// 需要连云厂商时，在「自定义」配置里填自己的 base URL 即可（协议仍是 OpenAI 兼容）。
// base 已按 CloudApiClient 的拼接规则填好：
//  · 客户端会自动补 /chat/completions（含版本段则只补该段，否则补 /v1/chat/completions）。
//  · free = NO_KEY 表示无需 Key 直接用。

enum class FreeKind { NONE, FREE_KEY, NO_KEY }

data class ApiProvider(
    val name: String,
    val base: String,
    val model: String,
    val keyUrl: String = "",
    val free: FreeKind = FreeKind.NONE,
    val note: String = "",
)

// 分组展示：本地部署
data class ProviderGroup(val title: String, val emoji: String, val items: List<ApiProvider>)

object ApiProviders {

    val groups: List<ProviderGroup> = listOf(
        ProviderGroup("本地部署 · 无需 Key", "💻", listOf(
            ApiProvider("Ollama（本机）", "http://localhost:11434/v1", "llama3.2",
                "https://ollama.com", FreeKind.NO_KEY, "本机运行，需先装 Ollama 并拉模型。手机连电脑改成对应局域网 IP。"),
            ApiProvider("LM Studio（本机）", "http://localhost:1234/v1", "local-model",
                "https://lmstudio.ai", FreeKind.NO_KEY, "本机运行，开启本地服务器后使用。"),
            ApiProvider("llama.cpp server（本机）", "http://localhost:8080/v1", "",
                "https://github.com/ggml-org/llama.cpp", FreeKind.NO_KEY, "llama-server 起在本机 8080，localhost 直连=完全离线。"),
            ApiProvider("vLLM / 自建", "http://localhost:8000/v1", "",
                "", FreeKind.NO_KEY, "任何 OpenAI 兼容的自建服务。"),
        )),
    )

    val all: List<ApiProvider> get() = groups.flatMap { it.items }
}

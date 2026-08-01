package com.arix.app

// 内置 API 服务商预设。base 已按 CloudApiClient 的拼接规则填好：
//  · 客户端会自动补 /chat/completions（含版本段则只补该段，否则补 /v1/chat/completions）。
//  · free = NO_KEY 表示无需 Key 直接用；FREE_KEY 表示免费注册领 Key；NONE 为常规付费/需充值。
// 说明：厂商随时可能调整端点/免费政策，若连不上可自行改 Base/模型。model 只是「建议默认」，可改。

enum class FreeKind { NONE, FREE_KEY, NO_KEY }

data class ApiProvider(
    val name: String,
    val base: String,
    val model: String,
    val keyUrl: String = "",
    val free: FreeKind = FreeKind.NONE,
    val note: String = "",
)

// 分组展示：免费直用 / 免费额度 / 国内厂商 / 国外厂商 / 本地部署
data class ProviderGroup(val title: String, val emoji: String, val items: List<ApiProvider>)

object ApiProviders {

    val groups: List<ProviderGroup> = listOf(
        ProviderGroup("免费直用 · 无需 Key", "🆓", listOf(
            ApiProvider("Pollinations（开源·免费）", "https://text.pollinations.ai/openai#", "openai",
                "https://pollinations.ai", FreeKind.NO_KEY, "开源项目，无需注册、无需 Key，直接聊天。可把模型改成 mistral / llama / deepseek 等；连不上就换个模型或用下面免费额度项。"),
        )),
        ProviderGroup("免费额度 · 注册领 Key", "🎁", listOf(
            ApiProvider("OpenRouter（免费模型）", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat-v3-0324:free",
                "https://openrouter.ai/keys", FreeKind.FREE_KEY, "聚合平台，带 :free 后缀的模型免费。可换 meta-llama/llama-3.3-70b-instruct:free 等。"),
            ApiProvider("Groq（极快·免费额度）", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile",
                "https://console.groq.com/keys", FreeKind.FREE_KEY, "推理速度极快，免费额度大方。"),
            ApiProvider("Google AI Studio（Gemini·免费）", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.1-flash-lite",
                "https://aistudio.google.com/apikey", FreeKind.FREE_KEY, "Google AI Studio 免费额度充足，去 aistudio.google.com/apikey 领 Key。默认 gemini-3.1-flash-lite（免费、快），也可换 gemini-3.1-flash / gemini-3-pro 等。OpenAI 兼容端点。"),
            ApiProvider("智谱 GLM-4-Flash（免费）", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash",
                "https://open.bigmodel.cn", FreeKind.FREE_KEY, "glm-4-flash 模型免费。"),
            ApiProvider("硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct",
                "https://cloud.siliconflow.cn", FreeKind.FREE_KEY, "聚合国产开源模型，部分模型免费。"),
            ApiProvider("Cerebras（极快·免费）", "https://api.cerebras.ai/v1", "llama3.1-8b",
                "https://cloud.cerebras.ai", FreeKind.FREE_KEY, "硬件加速，速度极快，免费额度。"),
            ApiProvider("腾讯混元 hunyuan-lite（免费）", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-lite",
                "https://console.cloud.tencent.com/hunyuan", FreeKind.FREE_KEY, "hunyuan-lite 免费。"),
            ApiProvider("OpenCode Zen（免费网关）", "https://opencode.ai/zen/v1", "code-supernova",
                "https://opencode.ai/auth", FreeKind.FREE_KEY, "opencode 的模型网关，部分模型免费（如 code-supernova / grok-code）。"),
        )),
        ProviderGroup("国内厂商", "🌏", listOf(
            ApiProvider("DeepSeek 深度求索", "https://api.deepseek.com", "deepseek-chat",
                "https://platform.deepseek.com", FreeKind.NONE, "deepseek-chat 通用，deepseek-reasoner 为推理模型。"),
            ApiProvider("月之暗面 Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k",
                "https://platform.moonshot.cn", FreeKind.NONE, "长上下文强。海外站用 api.moonshot.ai。"),
            ApiProvider("阿里 通义千问 Qwen", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus",
                "https://bailian.console.aliyun.com", FreeKind.NONE, "百炼平台，兼容模式端点。"),
            ApiProvider("字节 豆包 Doubao", "https://ark.cn-beijing.volces.com/api/v3", "doubao-pro-32k",
                "https://console.volcengine.com/ark", FreeKind.NONE, "火山方舟。model 需填你的接入点(endpoint)或模型名。"),
            ApiProvider("百度 文心 ERNIE", "https://qianfan.baidubce.com/v2", "ernie-4.0-8k",
                "https://console.bce.baidu.com/qianfan", FreeKind.NONE, "千帆 v2 OpenAI 兼容。"),
            ApiProvider("零一万物 Yi", "https://api.lingyiwanwu.com/v1", "yi-lightning",
                "https://platform.lingyiwanwu.com", FreeKind.NONE, ""),
            ApiProvider("MiniMax", "https://api.minimaxi.com/v1", "abab6.5s-chat",
                "https://platform.minimaxi.com", FreeKind.NONE, ""),
            ApiProvider("阶跃星辰 Step", "https://api.stepfun.com/v1", "step-1-8k",
                "https://platform.stepfun.com", FreeKind.NONE, ""),
            ApiProvider("讯飞星火 Spark", "https://spark-api-open.xf-yun.com/v1", "generalv3.5",
                "https://console.xfyun.cn", FreeKind.NONE, ""),
        )),
        ProviderGroup("国外厂商", "🌍", listOf(
            ApiProvider("OpenAI", "https://api.openai.com", "gpt-4o-mini",
                "https://platform.openai.com/api-keys", FreeKind.NONE, ""),
            ApiProvider("Anthropic Claude", "https://api.anthropic.com/v1", "claude-3-5-sonnet-latest",
                "https://console.anthropic.com", FreeKind.NONE, "OpenAI 兼容端点。"),
            ApiProvider("xAI Grok", "https://api.x.ai/v1", "grok-2-latest",
                "https://console.x.ai", FreeKind.NONE, ""),
            ApiProvider("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest",
                "https://console.mistral.ai", FreeKind.NONE, ""),
            ApiProvider("Cohere", "https://api.cohere.ai/compatibility/v1", "command-r-plus",
                "https://dashboard.cohere.com", FreeKind.NONE, "OpenAI 兼容端点。"),
            ApiProvider("Perplexity", "https://api.perplexity.ai", "sonar",
                "https://www.perplexity.ai/settings/api", FreeKind.NONE, "带联网检索。"),
            ApiProvider("Together AI", "https://api.together.xyz/v1", "meta-llama/Llama-3.3-70B-Instruct-Turbo",
                "https://api.together.ai", FreeKind.NONE, "海量开源模型。"),
            ApiProvider("Fireworks", "https://api.fireworks.ai/inference/v1", "accounts/fireworks/models/llama-v3p3-70b-instruct",
                "https://fireworks.ai", FreeKind.NONE, ""),
            ApiProvider("NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct",
                "https://build.nvidia.com", FreeKind.NONE, ""),
        )),
        ProviderGroup("本地部署 · 无需 Key", "💻", listOf(
            ApiProvider("Ollama（本机）", "http://localhost:11434/v1", "llama3.2",
                "https://ollama.com", FreeKind.NO_KEY, "本机运行，需先装 Ollama 并拉模型。手机连电脑改成对应局域网 IP。"),
            ApiProvider("LM Studio（本机）", "http://localhost:1234/v1", "local-model",
                "https://lmstudio.ai", FreeKind.NO_KEY, "本机运行，开启本地服务器后使用。"),
            ApiProvider("llama.cpp server（本机）", "http://localhost:8080/v1", "",
                "https://github.com/ggml-org/llama.cpp", FreeKind.NO_KEY, "llama-server 起在本机 8080，localhost 直连=完全离线。"),
            ApiProvider("vLLM / 自建", "http://localhost:8000/v1", "",
                "", FreeKind.NO_KEY, "任何 OpenAI 兼容的自建服务。手机端可在 Termux 里起。"),
        )),
    )

    val all: List<ApiProvider> get() = groups.flatMap { it.items }
}

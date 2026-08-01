package com.arix.tool

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// WeChat/QQ Intent Sharing Tool
// ============================================================
class SocialShareTool(private val context: Context) : Tool {
    override val name = "social_share"
    override val description = "通过微信/QQ分享文本消息。使用系统 Intent 调起应用。"

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("platform", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("wechat", "qq", "wechat_moments")))
                put("description", "目标平台: wechat=微信, qq=QQ, wechat_moments=朋友圈")
            })
            put("message", JSONObject().apply {
                put("type", "string")
                put("description", "要发送的消息内容")
            })
        })
        put("required", JSONArray(listOf("platform", "message")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val platform = params.optString("platform", "wechat")
        val message = params.optString("message", "").trim()
        if (message.isBlank()) return@withContext ToolResult("请输入消息内容", isError = true)

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK

                when (platform) {
                    "wechat" -> setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI")
                    "qq" -> setClassName("com.tencent.mobileqq", "com.tencent.mobileqq.activity.JumpActivity")
                    "wechat_moments" -> {
                        setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareToTimeLineUI")
                        putExtra("Kdescription", message)
                    }
                }
            }
            context.startActivity(intent)
            ToolResult("已调起 ${when(platform){"wechat"->"微信";"qq"->"QQ";else->platform}} 分享")
        } catch (e: Exception) {
            ToolResult("${when(platform){"wechat"->"微信";"qq"->"QQ";else->platform}} 未安装或启动失败: ${e.message}", isError = true)
        }
    }
}

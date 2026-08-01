package com.arix.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

// ============================================================
// 间接改配置 —— AI 只能提交「修改设置申请」，用户在对话里点同意才由 app 执行。
// AI 全程碰不到写权限；白名单限死能申请的设置；key/角色卡/模型等身份基石不可申请。
// ============================================================
data class SettingProposal(
    val id: Long,
    val target: String,
    val label: String,
    val newValue: String,
    val oldValue: String,
    val reason: String
)

object SettingProposalBus {
    val pending = MutableStateFlow<List<SettingProposal>>(emptyList())
    private var seq = 0L
    fun add(target: String, label: String, newValue: String, oldValue: String, reason: String) {
        seq += 1
        pending.value = pending.value + SettingProposal(seq, target, label, newValue, oldValue, reason)
    }
    fun remove(id: Long) { pending.value = pending.value.filter { it.id != id } }
}

// 白名单：只允许 AI 申请改这些安全设置（名字/开关类）。key/角色卡/模型不在内。
object SettingApplier {
    private data class Entry(val label: String, val read: (Context) -> String, val write: (Context, String) -> Unit)

    private val table: Map<String, Entry> = linkedMapOf(
        "user_name" to Entry("我的名字", { IdentityPrefs.userName(it) }, { c, v -> IdentityPrefs.setUserName(c, v) }),
        "auto_hide_bars" to Entry("滚动自动隐藏顶栏/输入栏", { ConfigModePrefs.autoHideBars(it).toString() }, { c, v -> ConfigModePrefs.setAutoHideBars(c, v.toBoolean()) }),
        "auto_memory" to Entry("对话后自动记忆", { ConfigModePrefs.autoExtractMemory(it).toString() }, { c, v -> ConfigModePrefs.setAutoExtractMemory(c, v.toBoolean()) }),
        "ugc_search" to Entry("突破UGC盲区(社区搜索)", { SearchApiPrefs.ugcEnabled(it).toString() }, { c, v -> SearchApiPrefs.setUgcEnabled(c, v.toBoolean()) }),
        "state_card" to Entry("交互状态层(状态卡)", { InteractionState.isEnabled(it).toString() }, { c, v -> InteractionState.setEnabled(c, v.toBoolean()) }),
        "context_compress" to Entry("长对话自动压缩上下文", { ConfigModePrefs.contextCompress(it).toString() }, { c, v -> ConfigModePrefs.setContextCompress(c, v.toBoolean()) }),
        "enter_to_send" to Entry("回车发送", { ConfigModePrefs.enterToSend(it).toString() }, { c, v -> ConfigModePrefs.setEnterToSend(c, v.toBoolean()) }),
        "tools_enabled" to Entry("允许 AI 调用工具", { ConfigModePrefs.toolsEnabled(it).toString() }, { c, v -> ConfigModePrefs.setToolsEnabled(c, v.toBoolean()) }),
    )

    /**
     * 功能包开关走 `pkg:<id>`，不进上面的 table。
     *
     * 不进 table 是因为功能包是运行期注册的、还会增减，写死在表里必然漏；但它跟表里那些开关
     * 本质是同一种东西——所以同样走申请制：AI 只能提，用户点同意才生效。
     * AI 发现自己要用的工具所在包没开时由 request_permission 提交。
     */
    private const val PKG_PREFIX = "pkg:"
    private fun pkgId(target: String) = target.removePrefix(PKG_PREFIX)
    private fun pkgDef(target: String) =
        com.arix.tool.PackageManager.getAllPackages().firstOrNull { it.id == pkgId(target) }

    fun isAllowed(target: String) =
        table.containsKey(target) || (target.startsWith(PKG_PREFIX) && pkgDef(target) != null)

    fun label(target: String) = when {
        table.containsKey(target) -> table[target]!!.label
        target.startsWith(PKG_PREFIX) -> tr("功能包「%s」").format(pkgDef(target)?.name ?: pkgId(target))
        else -> target
    }

    fun current(context: Context, target: String): String? = when {
        table.containsKey(target) -> table[target]!!.read(context)
        target.startsWith(PKG_PREFIX) -> com.arix.tool.PackageManager.isEnabled(pkgId(target)).toString()
        else -> null
    }

    /** 只给 propose_setting_change 的 enum 用：功能包不在这里列（它们由 request_permission 申请）。 */
    fun targets(): List<Pair<String, String>> = table.map { it.key to it.value.label }

    fun apply(context: Context, target: String, value: String): Boolean {
        if (target.startsWith(PKG_PREFIX)) {
            val id = pkgId(target)
            if (pkgDef(target) == null) return false
            return try {
                if (value.toBoolean()) com.arix.tool.PackageManager.enable(id)
                else com.arix.tool.PackageManager.disable(id)
                true
            } catch (_: Exception) { false }
        }
        val e = table[target] ?: return false
        return try { e.write(context, value); true } catch (_: Exception) { false }
    }
}

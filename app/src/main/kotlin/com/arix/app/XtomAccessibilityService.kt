package com.arix.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

// ============================================================
// 界面自动化无障碍服务 —— 让 AI 能「读屏 + 代操作」而不用 root。
// 静态 instance 供 ui_control 工具调用；只在用户系统设置里手动开启后才生效。
// ============================================================

/**
 * 屏幕上一个可读元素的结构化描述。
 * [bounds] 是屏幕绝对坐标矩形——保留宽高而不是只留中心点，才画得出高亮框。
 */
data class UiNode(
    val bounds: Rect,
    val label: String,
    val clickable: Boolean,
    val className: String,
)

class XtomAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 技能录制期间才处理事件（点击/输入/滚动 → 步骤），平时零开销
        if (com.arix.tool.SkillRecorder.isRecording) com.arix.tool.SkillRecorder.onEvent(event)
    }
    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); if (instance === this) instance = null }

    /**
     * 读当前界面的可见文本 + 可点元素，返回**结构化**节点表。
     *
     * 这是读屏的唯一真源：以前只在 dump() 里就地拼字符串，把 getBoundsInScreen 拿到的完整矩形
     * 压成了一个中心点，宽高当场丢掉——想画高亮框时再也找不回来。
     * 现在先出结构，dump() 只是它的字符串投影，可视化和喂模型共用一次遍历。
     */
    fun dumpNodes(): List<UiNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<UiNode>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || out.size >= 120 || depth > 40) return   // 深度上限防超深/WebView 树栈溢出
            val txt = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val label = txt.ifBlank { desc }
            if (label.isNotBlank() && label.length <= 60) {
                val r = Rect(); n.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) {
                    out.add(UiNode(r, label, n.isClickable, n.className?.toString().orEmpty()))
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    /**
     * 读当前界面的可见文本 + 可点元素（文字 @屏幕坐标），给 AI 决策用。
     * 输出格式是模型读屏的既定契约，改一个字都可能让它认不出界面 —— 只做投影，别加料。
     */
    fun dump(): String {
        if (rootInActiveWindow == null) return "拿不到当前界面内容"
        val nodes = dumpNodes()
        if (nodes.isEmpty()) return "当前界面没有可读文本"
        val sb = StringBuilder()
        for (n in nodes) {
            sb.append(if (n.clickable) "[可点] " else "· ").append(n.label)
                .append("  @(${n.bounds.centerX()},${n.bounds.centerY()})\n")
        }
        return "当前界面（文字 @点按坐标）：\n$sb"
    }

    fun tapAt(x: Int, y: Int): Boolean = gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x + 1f, y + 1f) }, 60L)

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durMs: Long): Boolean =
        gesture(Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }, durMs.coerceIn(50L, 5000L))

    /** 长按：路径与 tap 相同，靠手势时长让系统判成长按。下限 400ms —— 低于系统长按阈值就只是一次普通点击。 */
    fun longPress(x: Int, y: Int, durMs: Long): Boolean =
        gesture(Path().apply { moveTo(x.toFloat(), y.toFloat()); lineTo(x + 1f, y + 1f) }, durMs.coerceIn(400L, 10000L))

    private fun gesture(path: Path, durMs: Long): Boolean = try {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durMs)).build()
        dispatchGesture(g, null, null)
    } catch (_: Exception) { false }

    /** 按文字点击：找到含该文字的节点，点它或它最近的可点祖先；找不到可点祖先就点它中心。 */
    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return false
        for (n in nodes) {
            var t: AccessibilityNodeInfo? = n
            var hops = 0
            while (t != null && hops++ < 8) {
                if (t.isClickable) return t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                t = t.parent
            }
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.width() > 0) return tapAt(r.centerX(), r.centerY())
        }
        return false
    }

    /** 往当前聚焦的输入框写文字。 */
    fun setText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 当前聚焦输入框的屏幕矩形，给可视化画「正在往这里输入」用。取不到就 null（不画即可）。 */
    fun focusedInputBounds(): Rect? {
        val root = rootInActiveWindow ?: return null
        val target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        val r = Rect(); target.getBoundsInScreen(r)
        return if (r.width() > 0 && r.height() > 0) r else null
    }

    fun global(name: String): Boolean {
        val a = when (name) {
            "home" -> GLOBAL_ACTION_HOME
            "back" -> GLOBAL_ACTION_BACK
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return false
        }
        return performGlobalAction(a)
    }

    companion object {
        @Volatile var instance: XtomAccessibilityService? = null
        val isConnected: Boolean get() = instance != null
    }
}

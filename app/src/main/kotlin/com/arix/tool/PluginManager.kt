package com.arix.tool

import android.content.Context
import dalvik.system.DexClassLoader
import java.io.File

interface Plugin {
    val id: String
    val name: String
    val version: String
    val description: String
    fun getTools(): List<Tool>
    fun onLoad(context: Context)
    fun onUnload()
}

object PluginManager {
    private val plugins = mutableMapOf<String, Plugin>()
    /** 插件 id → 它的工具**实际注册名**（撞内置名会被改成 ext_xxx，按 tool.name 反注册会漏）。 */
    private val registeredNames = mutableMapOf<String, List<String>>()
    private val pluginDir by lazy {
        File(appContext?.filesDir, "plugins").also { if (!it.exists()) it.mkdirs() }
    }
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun register(plugin: Plugin, context: Context) {
        if (plugins.containsKey(plugin.id)) return
        plugin.onLoad(context)
        plugins[plugin.id] = plugin
        registeredNames[plugin.id] = plugin.getTools().map { ToolManager.register(it) }
    }

    fun unregister(id: String) {
        val plugin = plugins.remove(id) ?: return
        (registeredNames.remove(id) ?: plugin.getTools().map { it.name })
            .forEach { ToolManager.unregister(it) }
        plugin.onUnload()
    }

    fun getPlugin(id: String): Plugin? = plugins[id]
    fun getAllPlugins(): List<Plugin> = plugins.values.toList()

    fun loadPluginFromJar(jarPath: String, pluginClassName: String) {
        val ctx = appContext ?: return
        try {
            val optimizedDir = File(ctx.cacheDir, "dex").also { it.mkdirs() }
            val classLoader = DexClassLoader(
                jarPath, optimizedDir.absolutePath, null, ctx.classLoader
            )
            val clazz = classLoader.loadClass(pluginClassName)
            val instance = clazz.newInstance()
            if (instance is Plugin) {
                register(instance, ctx)
            }
        } catch (_: Exception) {}
    }

    fun discoverAndLoadPlugins() {
        pluginDir.listFiles { f -> f.extension == "jar" || f.extension == "dex" }
            ?.forEach { jar ->
                try {
                    val clsName = jar.nameWithoutExtension
                    loadPluginFromJar(jar.absolutePath, "com.arix.plugin.$clsName.${clsName}Plugin")
                } catch (_: Exception) {}
            }
    }
}

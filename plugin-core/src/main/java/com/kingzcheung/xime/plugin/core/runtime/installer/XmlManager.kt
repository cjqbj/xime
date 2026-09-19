package com.kingzcheung.xime.plugin.core.runtime.installer

import android.app.Application
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import java.io.File

class XmlManager(private val context: Application) {
    companion object {
        private const val PLUGINS_XML = "plugins.xml"
    }

    private val pluginsFile: File by lazy {
        File(context.filesDir, PLUGINS_XML)
    }

    private val plugins = mutableMapOf<String, PluginInfo>()

    init {
        loadFromDisk()
    }

    fun getAllPlugins(): List<PluginInfo> = plugins.values.toList()

    fun getPluginById(id: String): PluginInfo? = plugins[id]

    fun addPlugin(plugin: PluginInfo) {
        plugins[plugin.id] = plugin
    }

    fun updatePlugin(plugin: PluginInfo) {
        plugins[plugin.id] = plugin
    }

    fun removePlugin(id: String) {
        plugins.remove(id)
    }

    fun flushToDisk() {
        try {
            pluginsFile.bufferedWriter().use { writer ->
                writer.write("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
                writer.write("<plugins>\n")
                for (plugin in plugins.values) {
                    writer.write("  <plugin>\n")
                    writer.write("    <id>${escapeXml(plugin.id)}</id>\n")
                    writer.write("    <name>${escapeXml(plugin.name)}</name>\n")
                    writer.write("    <description>${escapeXml(plugin.description)}</description>\n")
                    writer.write("    <versionCode>${plugin.versionCode}</versionCode>\n")
                    writer.write("    <versionName>${escapeXml(plugin.versionName)}</versionName>\n")
                    writer.write("    <path>${escapeXml(plugin.path)}</path>\n")
                    writer.write("    <type>${escapeXml(plugin.type)}</type>\n")
                    writer.write("    <enabled>${plugin.enabled}</enabled>\n")
                    writer.write("    <installTime>${plugin.installTime}</installTime>\n")
                    writer.write("    <source>${plugin.source.name}</source>\n")
                    writer.write("    <iconResId>${plugin.iconResId}</iconResId>\n")
                    writer.write("    <trustLevel>${plugin.trustLevel.name}</trustLevel>\n")
                    if (plugin.minHostVersion != null) {
                        writer.write("    <minHostVersion>${escapeXml(plugin.minHostVersion)}</minHostVersion>\n")
                    }
                    if (plugin.maxHostVersion != null) {
                        writer.write("    <maxHostVersion>${escapeXml(plugin.maxHostVersion)}</maxHostVersion>\n")
                    }
                    if (plugin.entryScript != null) {
                        writer.write("    <entryScript>${escapeXml(plugin.entryScript)}</entryScript>\n")
                    }
                    if (plugin.declaredHosts.isNotEmpty()) {
                        writer.write("    <networkHosts>${escapeXml(plugin.declaredHosts.joinToString(","))}</networkHosts>\n")
                    }
                    if (plugin.allowCustomHosts) {
                        writer.write("    <allowCustomHosts>true</allowCustomHosts>\n")
                    }
                    writer.write("  </plugin>\n")
                }
                writer.write("</plugins>\n")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadFromDisk() {
        if (!pluginsFile.exists()) return

        try {
            val content = pluginsFile.readText()
            parsePluginsXml(content)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parsePluginsXml(content: String) {
        val pluginRegex = Regex("<plugin>(.*?)</plugin>", RegexOption.DOT_MATCHES_ALL)
        val matches = pluginRegex.findAll(content)

        for (match in matches) {
            val pluginContent = match.groupValues[1]
            val id = extractTag(pluginContent, "id")
            val name = extractTag(pluginContent, "name")
            val description = extractTag(pluginContent, "description")
            val versionCode = extractTag(pluginContent, "versionCode")?.toLongOrNull() ?: 0
            val versionName = extractTag(pluginContent, "versionName") ?: ""
            val path = extractTag(pluginContent, "path")
            val type = extractTag(pluginContent, "type") ?: "unknown"
            val enabled = extractTag(pluginContent, "enabled")?.toBoolean() ?: true
            val installTime = extractTag(pluginContent, "installTime")?.toLongOrNull() ?: System.currentTimeMillis()
            val source = extractTag(pluginContent, "source")
                ?.let { runCatching { PluginSource.valueOf(it) }.getOrNull() }
                ?: PluginSource.SYSTEM
            val iconResId = extractTag(pluginContent, "iconResId")?.toIntOrNull() ?: 0
            val minHostVersion = extractTag(pluginContent, "minHostVersion")?.takeIf { it.isNotBlank() }
            val maxHostVersion = extractTag(pluginContent, "maxHostVersion")?.takeIf { it.isNotBlank() }
            val entryScript = extractTag(pluginContent, "entryScript")?.takeIf { it.isNotBlank() }
            val networkHosts = extractTag(pluginContent, "networkHosts")
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
            val allowCustomHosts = extractTag(pluginContent, "allowCustomHosts")?.toBoolean() ?: false
            val trustLevel = com.kingzcheung.xime.plugin.core.util.PluginSignatureUtil.classifyLuaPlugin(source)

            if (id != null && path != null) {
                plugins[id] = PluginInfo(
                    id = id,
                    name = name ?: "",
                    iconResId = iconResId,
                    description = description ?: "",
                    versionCode = versionCode,
                    versionName = versionName,
                    path = path,
                    type = type,
                    enabled = enabled,
                    installTime = installTime,
                    source = source,
                    minHostVersion = minHostVersion,
                    maxHostVersion = maxHostVersion,
                    trustLevel = trustLevel,
                    entryScript = entryScript,
                    declaredHosts = networkHosts,
                    allowCustomHosts = allowCustomHosts
                )
            }
        }
    }

    private fun extractTag(content: String, tagName: String): String? {
        val regex = Regex("<$tagName>(.*?)</$tagName>")
        return regex.find(content)?.groupValues?.get(1)
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}

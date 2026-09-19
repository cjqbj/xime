package com.kingzcheung.xime.plugin

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.core.api.AsrPlugin
import com.kingzcheung.xime.plugin.core.api.ClipboardSyncPlugin
import com.kingzcheung.xime.plugin.core.api.EmojiPlugin
import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.api.PluginIcon
import com.kingzcheung.xime.plugin.core.model.PluginCategory
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.data.EmojiCategory
import com.kingzcheung.xime.data.EmojiData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object ExtensionManager {
    private const val TAG = "ExtensionManager"
    
    private var initialized = false
    private var managerJob: Job = SupervisorJob()
    private val managerScope get() = CoroutineScope(managerJob + Dispatchers.IO)
    private val _emojiCategoriesFlow = MutableStateFlow<List<EmojiCategory>>(EmojiData.categories)
    val emojiCategoriesFlow: StateFlow<List<EmojiCategory>> = _emojiCategoriesFlow.asStateFlow()
    
    fun initialize(context: Context) {
        if (initialized) {
            return
        }
        if (!managerJob.isActive) {
            managerJob = SupervisorJob()
        }
        initialized = true
        
        managerScope.launch {
            PluginManager.pluginInstancesFlow.collect { _ ->
                loadEmojiDataFromPlugins(context)
            }
        }
    }
    
    fun extractPluginIcon(context: Context, pluginId: String, plugin: IPluginEntryClass, pluginInfo: PluginInfo?): PluginIcon? {
        val pluginIcon = try {
            plugin.getIcon()
        } catch (e: Exception) {
            Log.w(TAG, "getIcon not supported by ${pluginInfo?.name}")
            null
        }
        
        if (pluginIcon == null) return null
        
        if (pluginIcon.text != null) {
            return PluginIcon(text = pluginIcon.text)
        }
        
        val assetName = pluginIcon.assetName
        if (assetName == null) {
            return null
        }
        
        val iconDir = File(context.filesDir, "plugin_icons")
        if (!iconDir.exists()) iconDir.mkdirs()
        
        val iconFile = File(iconDir, "${pluginId}_$assetName")

        if (!iconFile.exists()) {
            // Lua 插件资源在 resources/ 目录（path 指向入口脚本，其父目录为插件目录）
            val resourceFile = pluginInfo?.path
                ?.let { File(it).parentFile }
                ?.let { File(it, "resources/$assetName") }
            if (resourceFile != null && resourceFile.exists()) {
                try {
                    resourceFile.copyTo(iconFile, overwrite = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract icon for $pluginId", e)
                }
            }
        }
        
        val result = if (iconFile.exists()) {
            PluginIcon(assetName = iconFile.absolutePath)
        } else {
            null
        }
        return result
    }
    
    suspend fun loadEmojiDataFromPlugins(context: Context) {
        val pluginCategories = mutableListOf<EmojiCategory>()
        
        try {
            val emojiPlugins = getEnabledEmojiPlugins(context)
            
            emojiPlugins.forEach { (pluginId, plugin) ->
                val pluginInfo = getAllInstalledPlugins().firstOrNull { it.id == pluginId }
                try {
                    val subCategoryNames = try {
                        plugin.getCategories()
                    } catch (e: Exception) {
                        Log.w(TAG, "getCategories not supported by ${pluginInfo?.name}")
                        listOf(pluginInfo?.name ?: "表情")
                    }

                    if (subCategoryNames.isEmpty()) {
                        Log.w(TAG, "No categories from ${pluginInfo?.name}, skipping")
                        return@forEach
                    }

                    val pluginIcon = extractPluginIcon(context, pluginId, plugin, pluginInfo)

                    for (subCatName in subCategoryNames) {
                        val emojiItems = plugin.getEmojis(
                            category = subCatName,
                            searchText = null,
                            topK = 100
                        )
                        if (emojiItems.isNotEmpty()) {
                            val layoutConfig = try {
                                plugin.getCategoryLayoutConfig(subCatName)
                            } catch (e: Exception) {
                                Log.w(TAG, "getCategoryLayoutConfig not supported by ${pluginInfo?.name}/$subCatName")
                                null
                            }
                            pluginCategories.add(
                                EmojiCategory(
                                    name = subCatName,
                                    icon = "🎭",
                                    pluginIcon = pluginIcon,
                                    emojis = emptyList(),
                                    isPlugin = true,
                                    pluginId = pluginId,
                                    emojiItems = emojiItems,
                                    layoutConfig = layoutConfig
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error preloading from ${pluginInfo?.name}", e)
                }
            }
            _emojiCategoriesFlow.value = pluginCategories + EmojiData.categories
        } catch (e: Exception) {
            Log.e(TAG, "Failed to preload emoji data", e)
        }
    }
    
    fun reload(context: Context): Boolean {
        return try {
            managerScope.launch {
                PluginManager.loadEnabledPlugins()
            }
            PluginManager.isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "reload failed", e)
            false
        }
    }
    
    fun getEmojiPlugins(): List<EmojiPlugin> {
        val all = PluginManager.getAllPluginInstances()
        return all.values.mapNotNull { instance ->
            if (instance is EmojiPlugin) instance else null
        }
    }
    
    fun getEnabledEmojiPlugins(context: Context): List<Pair<String, EmojiPlugin>> {
        return getEmojiPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }

    fun getAsrPlugins(): List<AsrPlugin> {
        val all = PluginManager.getAllPluginInstances()
        return all.values.mapNotNull { instance ->
            if (instance is AsrPlugin) instance else null
        }
    }

    fun getEnabledAsrPlugins(context: Context): List<Pair<String, AsrPlugin>> {
        return getAsrPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }

    fun getClipboardSyncPlugins(): List<ClipboardSyncPlugin> {
        val all = PluginManager.getAllPluginInstances()
        return all.values.mapNotNull { instance ->
            if (instance is ClipboardSyncPlugin) instance else null
        }
    }

    fun getEnabledClipboardSyncPlugins(context: Context): List<Pair<String, ClipboardSyncPlugin>> {
        return getClipboardSyncPlugins().mapNotNull { plugin ->
            val pluginId = getPluginId(plugin)
            if (pluginId.isNotEmpty() && SettingsPreferences.isPluginEnabled(context, pluginId)) {
                Pair(pluginId, plugin)
            } else null
        }
    }
    
    private fun getPluginId(plugin: Any): String {
        return PluginManager.getAllPluginInstances().entries
            .firstOrNull { it.value == plugin }?.key ?: ""
    }
    
    suspend fun getEmojis(context: Context, category: String? = null, searchText: String? = null, topK: Int = 100) =
        withContext(Dispatchers.Default) {
            getEnabledEmojiPlugins(context).flatMap { (_, plugin) ->
                try { plugin.getEmojis(category, searchText, topK) }
                catch (e: Exception) { Log.e(TAG, "Get emojis failed", e); emptyList() }
            }.take(topK)
        }
    
    fun getAllInstalledPlugins(): List<PluginInfo> = PluginManager.getAllInstallPlugins()

    fun getPluginsByCategory(category: PluginCategory): List<PluginInfo> =
        getAllInstalledPlugins().filter { it.category == category }

    fun getEnabledPluginsByCategory(context: Context, category: PluginCategory): List<PluginInfo> =
        getPluginsByCategory(category).filter { SettingsPreferences.isPluginEnabled(context, it.id) }
    
    fun getPluginById(id: String): Any? = PluginManager.getPluginInstance(id)
    
    fun isInitialized(): Boolean = initialized && PluginManager.isInitialized
    
    fun hasEmojiPlugins(context: Context): Boolean = getEnabledEmojiPlugins(context).isNotEmpty()
    
    fun release() {
        initialized = false
        managerJob.cancel()
    }
}
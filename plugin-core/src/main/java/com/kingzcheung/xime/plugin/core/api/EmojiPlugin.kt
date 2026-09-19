package com.kingzcheung.xime.plugin.core.api

import android.content.Context
import com.kingzcheung.xime.plugin.core.model.PluginContext

data class CategoryLayoutConfig(
    val columns: Int = 8,
    val itemHeightDp: Int = 40
)

data class PluginIcon(
    val text: String? = null,
    val assetName: String? = null
)

interface EmojiPlugin : IPluginEntryClass {
    
    override fun onLoad(context: PluginContext)
    
    override fun onUnload()
    
    suspend fun getEmojis(category: String?, searchText: String?, topK: Int): List<EmojiItem>
    
    suspend fun getCategories(): List<String>
    
    suspend fun getCategoryLayoutConfig(category: String): CategoryLayoutConfig? = null
    
    override fun hasSettings(): Boolean = false
    
    override fun openSettings(context: Context) {}
}

data class EmojiDisplayConfig(
    val span: Int = 1,
    val heightDp: Int = 40,
    val aspectRatio: Float? = null
)

data class EmojiItem(
    val id: String,
    val displayText: String,
    val insertText: String,
    val imageUrl: String?,
    val category: String,
    val displayConfig: EmojiDisplayConfig? = null
)
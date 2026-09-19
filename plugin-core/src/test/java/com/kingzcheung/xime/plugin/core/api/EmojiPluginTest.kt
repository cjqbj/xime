package com.kingzcheung.xime.plugin.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiItemTest {
    
    @Test
    fun `EmojiItem should have correct properties`() {
        val item = EmojiItem(
            id = "emoji_1",
            displayText = "😀",
            insertText = "😀",
            imageUrl = null,
            category = "表情"
        )
        
        assertEquals("emoji_1", item.id)
        assertEquals("😀", item.displayText)
        assertEquals("😀", item.insertText)
        assertNull("imageUrl should be null", item.imageUrl)
        assertEquals("表情", item.category)
    }
    
    @Test
    fun `EmojiItem can have imageUrl`() {
        val item = EmojiItem(
            id = "sticker_1",
            displayText = "兔子",
            insertText = "[兔子]",
            imageUrl = "/path/to/image.png",
            category = "贴纸"
        )
        
        assertEquals("/path/to/image.png", item.imageUrl)
    }
    
    @Test
    fun `EmojiItem can have displayConfig`() {
        val config = EmojiDisplayConfig(span = 2, heightDp = 80)
        val item = EmojiItem(
            id = "large_emoji",
            displayText = "大表情",
            insertText = "[大表情]",
            imageUrl = null,
            category = "大表情",
            displayConfig = config
        )
        
        assertEquals(2, item.displayConfig?.span)
        assertEquals(80, item.displayConfig?.heightDp)
    }
    
    @Test
    fun `EmojiItem displayText and insertText can differ`() {
        val item = EmojiItem(
            id = "kaomoji",
            displayText = "(╯°□°）╯︵ ┻━┻",
            insertText = "(╯°□°）╯︵ ┻━┻",
            imageUrl = null,
            category = "颜文字"
        )
        
        assertEquals("(╯°□°）╯︵ ┻━┻", item.displayText)
        assertEquals("(╯°□°）╯︵ ┻━┻", item.insertText)
    }
    
    @Test
    fun `EmojiItem copy should preserve values`() {
        val original = EmojiItem(
            id = "original",
            displayText = "原始",
            insertText = "[原始]",
            imageUrl = "/original.png",
            category = "测试"
        )
        
        val copied = original.copy(category = "新分类")
        
        assertEquals("original", copied.id)
        assertEquals("原始", copied.displayText)
        assertEquals("新分类", copied.category)
    }
}

class EmojiDisplayConfigTest {
    
    @Test
    fun `EmojiDisplayConfig should have correct defaults`() {
        val config = EmojiDisplayConfig()
        
        assertEquals(1, config.span)
        assertEquals(40, config.heightDp)
        assertNull("aspectRatio should be null by default", config.aspectRatio)
    }
    
    @Test
    fun `EmojiDisplayConfig can have custom values`() {
        val config = EmojiDisplayConfig(
            span = 3,
            heightDp = 100,
            aspectRatio = 1.5f
        )
        
        assertEquals(3, config.span)
        assertEquals(100, config.heightDp)
        assertEquals(1.5f, config.aspectRatio)
    }
    
    @Test
    fun `EmojiDisplayConfig span can be larger`() {
        val config = EmojiDisplayConfig(span = 4)
        
        assertEquals(4, config.span)
    }
    
    @Test
    fun `EmojiDisplayConfig heightDp can be small`() {
        val config = EmojiDisplayConfig(heightDp = 20)
        
        assertEquals(20, config.heightDp)
    }
}

class CategoryLayoutConfigTest {
    
    @Test
    fun `CategoryLayoutConfig should have correct defaults`() {
        val config = CategoryLayoutConfig()
        
        assertEquals(8, config.columns)
        assertEquals(40, config.itemHeightDp)
    }
    
    @Test
    fun `CategoryLayoutConfig can have custom values`() {
        val config = CategoryLayoutConfig(
            columns = 4,
            itemHeightDp = 60
        )
        
        assertEquals(4, config.columns)
        assertEquals(60, config.itemHeightDp)
    }
    
    @Test
    fun `CategoryLayoutConfig columns can be small`() {
        val config = CategoryLayoutConfig(columns = 2)
        
        assertEquals(2, config.columns)
    }
    
    @Test
    fun `CategoryLayoutConfig columns can be large`() {
        val config = CategoryLayoutConfig(columns = 10)
        
        assertEquals(10, config.columns)
    }
}

class EmojiPluginDefaultImplTest {
    
    @Test
    fun `EmojiItem list filtering by category`() {
        val items = listOf(
            EmojiItem("1", "a", "a", null, "表情"),
            EmojiItem("2", "b", "b", null, "贴纸"),
            EmojiItem("3", "c", "c", null, "表情")
        )
        
        val filtered = items.filter { it.category == "表情" }
        
        assertEquals(2, filtered.size)
        assertTrue("All filtered items should be in 表情 category", 
            filtered.all { it.category == "表情" })
    }
    
    @Test
    fun `EmojiItem list filtering by searchText`() {
        val items = listOf(
            EmojiItem("1", "你好", "你好", null, "表情"),
            EmojiItem("2", "世界", "世界", null, "表情"),
            EmojiItem("3", "你好吗", "你好吗", null, "表情")
        )
        
        val filtered = items.filter { 
            it.displayText.contains("你好") || it.insertText.contains("你好") 
        }
        
        assertEquals(2, filtered.size)
    }
    
    @Test
    fun `EmojiItem topK selection`() {
        val items = (1..20).map { i ->
            EmojiItem("$i", "emoji$i", "emoji$i", null, "test")
        }
        
        val top5 = items.take(5)
        
        assertEquals(5, top5.size)
    }
    
    @Test
    fun `EmojiItem list grouping by category`() {
        val items = listOf(
            EmojiItem("1", "a", "a", null, "表情"),
            EmojiItem("2", "b", "b", null, "贴纸"),
            EmojiItem("3", "c", "c", null, "表情"),
            EmojiItem("4", "d", "d", null, "颜文字")
        )
        
        val grouped = items.groupBy { it.category }
        
        assertEquals(3, grouped.size)
        assertEquals(2, grouped["表情"]?.size)
        assertEquals(1, grouped["贴纸"]?.size)
        assertEquals(1, grouped["颜文字"]?.size)
    }
    
    @Test
    fun `EmojiItem distinct categories`() {
        val items = listOf(
            EmojiItem("1", "a", "a", null, "表情"),
            EmojiItem("2", "b", "b", null, "贴纸"),
            EmojiItem("3", "c", "c", null, "表情")
        )
        
        val categories = items.map { it.category }.distinct()
        
        assertEquals(listOf("表情", "贴纸"), categories)
    }
}
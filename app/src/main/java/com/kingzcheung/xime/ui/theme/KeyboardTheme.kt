package com.kingzcheung.xime.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import com.kingzcheung.xime.settings.BackgroundConfig
import com.kingzcheung.xime.settings.ColorSchemeEntry
import com.kingzcheung.xime.settings.KeysConfigHelper
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.math.max

/**
 * 打开主题背景图片流。
 * 优先读取用户数据目录（context.filesDir/rime/<src>，用户可自行放入或通过分享导入），
 * 找不到再回退到内置 assets/<src>。
 */
fun openThemeImageStream(context: Context, src: String): InputStream? {
    val rimeBase = File(context.filesDir, "rime").canonicalFile
    val userFile = File(rimeBase, src).canonicalFile
    if (userFile.isFile && userFile.path.startsWith(rimeBase.path + File.separator)) {
        return FileInputStream(userFile)
    }
    return try {
        context.assets.open(src)
    } catch (e: Exception) {
        null
    }
}

data class KeyboardColorScheme(
    val id: String,
    val name: String,
    val specialKeyLight: Color,
    val specialKeyDark: Color,
    val accentLight: Color,
    val accentDark: Color,
    val primaryLight: Color = accentLight,
    val primaryDark: Color = accentDark,
    val primaryContainerLight: Color = specialKeyLight,
    val primaryContainerDark: Color = specialKeyDark,
    val surfaceLight: Color = Color.White,
    val surfaceDark: Color = Color(0xFF1C1B1F),
    // 键盘背景色
    val keyboardBgLight: Color = surfaceLight,
    val keyboardBgDark: Color = surfaceDark,
    // 按键颜色
    val keyBgLight: Color = Color.White,
    val keyBgDark: Color = Color(0xFF4A4A4A),
    // 候选栏颜色
    val candidateBarBgLight: Color = surfaceLight,
    val candidateBarBgDark: Color = surfaceDark,
    // 按键文字颜色
    val keyTextColorLight: Color = Color(0xFF202124),
    val keyTextColorDark: Color = Color(0xFFE8EAED),
    // 候选文字颜色
    val candidateTextColorLight: Color = Color(0xFF1A73E8),
    val candidateTextColorDark: Color = Color(0xFF8AB4F8),
    // 候选选中文字颜色（未配置时回退到主题强调色）
    val candidateSelectedTextColorLight: Color = Color(0xFF8F73E2),
    val candidateSelectedTextColorDark: Color = Color(0xFFD0BCFF),
    // 分隔线颜色
    val dividerColorLight: Color = Color(0xFFDADCE0),
    val dividerColorDark: Color = Color(0xFF3C4043),
    // 背景配置（纯色/渐变/图片），比上方 flat color 字段优先级更高
    val keyboardBackground: BackgroundConfig? = null,
    val keyBackground: BackgroundConfig? = null,
    val candidateBarBackground: BackgroundConfig? = null,
)

object KeyboardThemes {
    /** 从 xime.yaml 加载的配置覆盖项。 */
    private var configOverrides: Map<String, ColorSchemeEntry> = emptyMap()

    /** 预计算后的主题缓存（避免每次访问都重新计算颜色）。 */
    private var themesCache: List<KeyboardColorScheme> = emptyList()
    private var themesMapCache: Map<String, KeyboardColorScheme> = emptyMap()

    /** 硬编码的默认主题列表（兜底，其余主题由 xime.yaml color_schemes 提供）。 */
    private val defaultThemes = listOf(
        KeyboardColorScheme(
            id = "lavender_purple",
            name = "薰衣草紫",
            specialKeyLight = Color(0xFFE8DEF8),
            specialKeyDark = Color(0xFF6750A4),
            accentLight = Color(0xFF8F73E2),
            accentDark = Color(0xFFD0BCFF),
            primaryLight = Color(0xFF8F73E2),
            primaryDark = Color(0xFFD0BCFF),
            primaryContainerLight = Color(0xFFEADDFF),
            primaryContainerDark = Color(0xFF4F378B),
            surfaceLight = Color(0xFFFAF8FC),
            surfaceDark = Color(0xFF2B2930)
        )
    )

    init {
        themesCache = defaultThemes
        themesMapCache = defaultThemes.associateBy { it.id }
    }

    /** 从配置文件加载主题覆盖项。应在 Application.onCreate 中调用。 */
    fun initFromConfig(context: Context) {
        reload(context)
    }

    /** 重新加载 xime.yaml/xime.custom.yaml 中的配色方案并更新缓存。 */
    fun reload(context: Context) {
        configOverrides = KeysConfigHelper.loadColorSchemes(context)
        // 1) 对硬编码主题应用配置覆盖
        val overridden = defaultThemes.map { applyConfigOverrides(context, it) }
        // 2) 把配置中有但硬编码列表中没有的新主题也加入缓存
        val existingIds = overridden.map { it.id }.toSet()
        val newThemes = configOverrides
            .filterKeys { it !in existingIds }
            .map { (id, entry) -> buildSchemeFromConfig(context, id, entry) }
        themesCache = overridden + newThemes
        themesMapCache = themesCache.associateBy { it.id }
    }



    /** 根据配置项创建全新的 KeyboardColorScheme。 */
    private fun buildSchemeFromConfig(context: Context, id: String, entry: ColorSchemeEntry): KeyboardColorScheme {
        val primary = if (entry.primaryColor != 0L) entry.primaryColor
        else extractImageSeedColor(context, entry)
        val cfgColor = longToColor(primary)
        val lightened = lightenColor(cfgColor)
        val veryLight = lightenColor(cfgColor, 0.8f)
        val global = KeysConfigHelper.getKeyboardColors()

        val kbdBg = resolveBgColor(entry, isDark = false) ?: Color.White
        val kbdBgDark = resolveBgColor(entry, isDark = true) ?: Color(0xFF1C1B1F)
        val keyBg = resolveKeyBgColor(entry, isDark = false) ?: longToColor(global.keyBgColor)
        val keyBgDark = resolveKeyBgColor(entry, isDark = true) ?: longToColor(global.keyBgColorDark)
        val txtColor = entry.keyTextColor?.let { longToColor(it) } ?: longToColor(global.keyTextColor)
        val txtColorDark = entry.keyTextColorDark?.let { longToColor(it) }
            ?: longToColor(global.keyTextColorDark)
        val candColorLight = entry.candidateTextColor?.let { longToColor(it) }
            ?: longToColor(global.candidateTextColor)
        val candColorDark = entry.candidateTextColorDark?.let { longToColor(it) }
            ?: longToColor(global.candidateTextColorDark)
        val candSelectedLight = entry.candidateSelectedTextColor?.let { longToColor(it) } ?: cfgColor
        val candSelectedDark = entry.candidateSelectedTextColorDark?.let { longToColor(it) } ?: lightened

        return KeyboardColorScheme(
            id = id,
            name = entry.name.ifEmpty { id },
            specialKeyLight = veryLight,
            specialKeyDark = cfgColor,
            accentLight = cfgColor,
            accentDark = lightened,
            primaryLight = cfgColor,
            primaryDark = lightened,
            primaryContainerLight = veryLight,
            primaryContainerDark = cfgColor,
            surfaceLight = Color.White,
            surfaceDark = Color(0xFF1C1B1F),
            keyboardBgLight = kbdBg,
            keyboardBgDark = kbdBgDark,
            keyBgLight = keyBg,
            keyBgDark = keyBgDark,
            candidateBarBgLight = kbdBg,
            candidateBarBgDark = kbdBgDark,
            keyTextColorLight = txtColor,
            keyTextColorDark = txtColorDark,
            candidateTextColorLight = candColorLight,
            candidateTextColorDark = candColorDark,
            candidateSelectedTextColorLight = candSelectedLight,
            candidateSelectedTextColorDark = candSelectedDark,
            keyboardBackground = entry.keyboardBackground,
            keyBackground = entry.keyBackground,
            candidateBarBackground = entry.candidateBarBackground,
        )
    }

    /** 从 BackgroundConfig（solid/gradient fallback）或旧 keyboardBgColor 字段解析键盘背景色。 */
    private fun resolveBgColor(entry: ColorSchemeEntry, isDark: Boolean): Color? {
        val bg = entry.keyboardBackground
        if (bg != null) {
            when (bg.type) {
                "solid" -> {
                    val hex = if (isDark) bg.colorDark ?: bg.color else bg.color
                    if (hex != null) return longToColor(hex)
                }
                "gradient" -> {
                    val hexes = if (isDark) bg.colorsDark ?: bg.colors else bg.colors
                    if (!hexes.isNullOrEmpty()) return longToColor(hexes[0])
                }
            }
        }
        return entry.keyboardBgColor?.let {
            val c = longToColor(it)
            if (isDark) darkenColor(c) else c
        }
    }

    /** 从 BackgroundConfig 或旧 keyBgColor 字段解析按键背景色。 */
    private fun resolveKeyBgColor(entry: ColorSchemeEntry, isDark: Boolean): Color? {
        val bg = entry.keyBackground
        if (bg != null) {
            when (bg.type) {
                "solid" -> {
                    val hex = if (isDark) bg.colorDark ?: bg.color else bg.color
                    if (hex != null) return longToColor(hex)
                }
                "gradient" -> {
                    val hexes = if (isDark) bg.colorsDark ?: bg.colors else bg.colors
                    if (!hexes.isNullOrEmpty()) return longToColor(hexes[0])
                }
            }
        }
        if (isDark) {
            return entry.keyBgColorDark?.let { longToColor(it) }
        }
        return entry.keyBgColor?.let { longToColor(it) }
    }

    /** 将 hex long 转为 Color。0xRRGGBB 补上 FF alpha，0xAARRGGBB 保留 alpha。 */
    /**
     * 从图片背景提取主色作为种子色。参考 Material 3 动态配色思路：
     * 解码小图 → 转 HSL → 排除接近黑白灰的像素 → 分桶统计 → 取权重最高的桶平均色。
     * 取色结果按 src+文件大小/修改时间缓存到 SharedPreferences，
     * 避免每次冷启动在主线程重复解码（图片主题没有 primary_color 时）。
     * 取色失败返回默认薰衣草紫。
     */
    private fun extractImageSeedColor(context: Context, entry: ColorSchemeEntry): Long {
        val src = entry.keyboardBackground?.takeIf { it.type == "image" }?.src
        if (src.isNullOrBlank()) return 0xFF8F73E2
        val cacheKey = themeSeedCacheKey(context, src)
        val cached = context.getSharedPreferences("theme_seed_cache", Context.MODE_PRIVATE)
            .getLong(cacheKey, 0L)
        if (cached != 0L) return cached
        val result = doExtractImageSeedColor(context, src)
        if (result != 0xFF8F73E2) {
            context.getSharedPreferences("theme_seed_cache", Context.MODE_PRIVATE)
                .edit().putLong(cacheKey, result).apply()
        }
        return result
    }

    /** 生成种子色缓存键：src + 文件大小/修改时间，文件变化后自动失效。 */
    private fun themeSeedCacheKey(context: Context, src: String): String {
        val userFile = File(context.filesDir, "rime/$src")
        val file = if (userFile.isFile) userFile else null
        val size = file?.length() ?: -1L
        val mtime = file?.lastModified() ?: -1L
        return "seed_$src|$size|$mtime"
    }

    private fun doExtractImageSeedColor(context: Context, src: String): Long {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openThemeImageStream(context, src)?.use { BitmapFactory.decodeStream(it, null, options) }
            var sampleSize = 1
            var maxDim = max(options.outWidth, options.outHeight)
            while (maxDim / sampleSize > 64) sampleSize *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = openThemeImageStream(context, src)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
                ?: return 0xFF8F73E2
            try {
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                val hueBuckets = 16
                val satBuckets = 4
                val count = LongArray(hueBuckets * satBuckets)
                val sumR = DoubleArray(hueBuckets * satBuckets)
                val sumG = DoubleArray(hueBuckets * satBuckets)
                val sumB = DoubleArray(hueBuckets * satBuckets)
                val hsl = FloatArray(3)
                for (pixel in pixels) {
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    ColorUtils.RGBToHSL(r, g, b, hsl)
                    val hue = hsl[0]
                    val sat = hsl[1]
                    val light = hsl[2]
                    if (sat < 0.12f || light < 0.08f || light > 0.92f) continue
                    val hi = (hue / 360f * hueBuckets).toInt().coerceIn(0, hueBuckets - 1)
                    val si = (sat * satBuckets).toInt().coerceIn(0, satBuckets - 1)
                    val idx = hi * satBuckets + si
                    count[idx]++
                    sumR[idx] += r
                    sumG[idx] += g
                    sumB[idx] += b
                }
                var bestIdx = -1
                var bestScore = 0L
                for (i in count.indices) {
                    if (count[i] == 0L) continue
                    val sat = (i % satBuckets + 1) / satBuckets.toFloat()
                    val score = count[i] * (1L + (sat * 4).toLong())
                    if (score > bestScore) {
                        bestScore = score
                        bestIdx = i
                    }
                }
                if (bestIdx < 0) return 0xFF8F73E2
                val r = (sumR[bestIdx] / count[bestIdx]).toInt().toLong()
                val g = (sumG[bestIdx] / count[bestIdx]).toInt().toLong()
                val b = (sumB[bestIdx] / count[bestIdx]).toInt().toLong()
                (r shl 16) or (g shl 8) or b
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            0xFF8F73E2
        }
    }

    /** 将 hex long 转为 Color。0xRRGGBB 补上 FF alpha，0xAARRGGBB 保留 alpha。 */
    private fun longToColor(hex: Long): Color {
        return if (hex > 0xFFFFFF) Color(hex) else Color(0xFF000000 or (hex and 0xFFFFFF))
    }

    /** 将颜色调亮（向白色混合），用于生成暗色主题下的亮色变体。 */
    private fun lightenColor(color: Color, factor: Float = 0.45f): Color {
        val r = color.red + (1f - color.red) * factor
        val g = color.green + (1f - color.green) * factor
        val b = color.blue + (1f - color.blue) * factor
        return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /** 将颜色调暗（向黑色混合），用于从亮色生成暗色变体。 */
    private fun darkenColor(color: Color, factor: Float = 0.7f): Color {
        val r = color.red * factor
        val g = color.green * factor
        val b = color.blue * factor
        return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /** 应用配置覆盖，返回覆盖后的 KeyboardColorScheme。 */
    private fun applyConfigOverrides(context: Context, scheme: KeyboardColorScheme): KeyboardColorScheme {
        val entry = configOverrides[scheme.id] ?: return scheme
        val primary = if (entry.primaryColor != 0L) entry.primaryColor
        else extractImageSeedColor(context, entry)
        val cfgColor = longToColor(primary)
        val lightened = lightenColor(cfgColor)
        val global = KeysConfigHelper.getKeyboardColors()
        return scheme.copy(
            name = entry.name.ifEmpty { scheme.name },
            accentLight = cfgColor,
            accentDark = lightened,
            primaryLight = cfgColor,
            primaryDark = lightened,
            keyboardBgLight = resolveBgColor(entry, isDark = false) ?: scheme.keyboardBgLight,
            keyboardBgDark = resolveBgColor(entry, isDark = true) ?: scheme.keyboardBgDark,
            keyBgLight = resolveKeyBgColor(entry, isDark = false) ?: longToColor(global.keyBgColor),
            keyBgDark = resolveKeyBgColor(entry, isDark = true) ?: longToColor(global.keyBgColorDark),
            candidateBarBgLight = resolveBgColor(entry, isDark = false) ?: scheme.candidateBarBgLight,
            candidateBarBgDark = resolveBgColor(entry, isDark = true) ?: scheme.candidateBarBgDark,
            keyTextColorLight = entry.keyTextColor?.let { longToColor(it) } ?: longToColor(global.keyTextColor),
            keyTextColorDark = entry.keyTextColorDark?.let { longToColor(it) }
                ?: longToColor(global.keyTextColorDark),
            candidateTextColorLight = entry.candidateTextColor?.let { longToColor(it) }
                ?: longToColor(global.candidateTextColor),
            candidateTextColorDark = entry.candidateTextColorDark?.let { longToColor(it) }
                ?: longToColor(global.candidateTextColorDark),
            candidateSelectedTextColorLight = entry.candidateSelectedTextColor?.let { longToColor(it) }
                ?: cfgColor,
            candidateSelectedTextColorDark = entry.candidateSelectedTextColorDark?.let { longToColor(it) }
                ?: lightened,
            keyboardBackground = entry.keyboardBackground ?: scheme.keyboardBackground,
            keyBackground = entry.keyBackground ?: scheme.keyBackground,
            candidateBarBackground = entry.candidateBarBackground ?: scheme.candidateBarBackground,
        )
    }

    /** 预计算后的主题列表。 */
    val themes: List<KeyboardColorScheme>
        get() = themesCache

    fun getThemeById(id: String): KeyboardColorScheme {
        return themesMapCache[id] ?: themesCache[0]
    }

    fun getSpecialKeyColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.specialKeyDark else theme.specialKeyLight
    }

    fun getAccentColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.accentDark else theme.accentLight
    }

    /** 特殊键文字颜色：暗色用强调色，亮色用更深的版本以提高对比度。 */
    fun getSpecialKeyTextColor(themeId: String, isDark: Boolean): Color {
        val accent = getAccentColor(themeId, isDark)
        return if (isDark) accent else Color(
            red = accent.red * 0.6f, green = accent.green * 0.6f, blue = accent.blue * 0.6f
        )
    }

    fun getPrimaryColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.primaryDark else theme.primaryLight
    }

    fun getPrimaryContainerColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.primaryContainerDark else theme.primaryContainerLight
    }

    fun getSurfaceColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.surfaceDark else theme.surfaceLight
    }

    fun getKeyboardBackgroundColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.keyboardBgDark else theme.keyboardBgLight
    }

    fun getKeyBackgroundColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.keyBgDark else theme.keyBgLight
    }

    fun getCandidateBarBackgroundColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.candidateBarBgDark else theme.candidateBarBgLight
    }

    fun getKeyTextColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.keyTextColorDark else theme.keyTextColorLight
    }

    fun getCandidateTextColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.candidateTextColorDark else theme.candidateTextColorLight
    }

    /** 候选选中文字色，未显式配置时回退到按键文字色。 */
    fun getCandidateSelectedTextColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.candidateSelectedTextColorDark else theme.candidateSelectedTextColorLight
    }

    fun getDividerColor(themeId: String, isDark: Boolean): Color {
        val theme = getThemeById(themeId)
        return if (isDark) theme.dividerColorDark else theme.dividerColorLight
    }

    /** 返回 color_schemes 中显式定义的按键背景色，未定义返回 null。 */
    fun getKeyBgColorOverride(themeId: String, isDark: Boolean): Color? {
        val entry = configOverrides[themeId] ?: return null
        return resolveKeyBgColor(entry, isDark)
    }

    /** 返回 color_schemes 中显式定义的按键文字色，未定义返回 null。 */
    fun getKeyTextColorOverride(themeId: String, isDark: Boolean): Color? {
        val entry = configOverrides[themeId] ?: return null
        return if (isDark) {
            entry.keyTextColorDark?.let { longToColor(it) }
        } else {
            entry.keyTextColor?.let { longToColor(it) }
        }
    }

    /** 返回 color_schemes 中显式定义的候选文字色，未定义返回 null。 */
    fun getCandidateTextColorOverride(themeId: String, isDark: Boolean): Color? {
        val entry = configOverrides[themeId] ?: return null
        return if (isDark) {
            entry.candidateTextColorDark?.let { longToColor(it) }
        } else {
            entry.candidateTextColor?.let { longToColor(it) }
        }
    }

    /** 返回 color_schemes 中显式定义的候选选中文字色，未定义返回 null。 */
    fun getCandidateSelectedTextColorOverride(themeId: String, isDark: Boolean): Color? {
        val entry = configOverrides[themeId] ?: return null
        return if (isDark) {
            entry.candidateSelectedTextColorDark?.let { longToColor(it) }
        } else {
            entry.candidateSelectedTextColor?.let { longToColor(it) }
        }
    }
}

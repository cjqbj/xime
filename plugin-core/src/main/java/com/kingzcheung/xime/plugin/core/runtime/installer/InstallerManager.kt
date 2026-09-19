package com.kingzcheung.xime.plugin.core.runtime.installer

import android.app.Application
import android.net.Uri
import android.util.Log
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.kingzcheung.xime.plugin.core.model.PluginInfo
import com.kingzcheung.xime.plugin.core.model.PluginSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.util.zip.ZipFile

/** manifest.yaml 解析结果。 */
internal sealed class PluginParseResult {
    data class Success(val config: PluginConfig) : PluginParseResult()
    data class Failure(val reason: String) : PluginParseResult()
}

internal data class PluginConfig(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val type: String,
    val minHostVersion: String?,
    val maxHostVersion: String?,
    val entryScript: String?,
    val declaredHosts: List<String> = emptyList(),
    val allowCustomHosts: Boolean = false
)

/** manifest.yaml 的类型化模型，与宿主一起用 kaml 解析。 */
@Serializable
internal data class PluginManifest(
    val id: String,
    val name: String? = null,
    val type: String = "unknown",
    val entry: String = "main.lua",
    val version: String = "0.0.0",
    val description: String? = null,
    val minHostVersion: String? = null,
    val maxHostVersion: String? = null,
    val network: NetworkConfig? = null
)

@Serializable
internal data class NetworkConfig(
    val hosts: List<String> = emptyList(),
    val allowCustomHosts: Boolean = false
)

/**
 * 插件安装器（Lua 脚本插件）。
 *
 * 插件包为 zip（.xipk），结构：
 *   manifest.yaml   元数据（宿主解析）
 *   main.lua        入口脚本（宿主 Lua 沙箱执行）
 *   libs/           纯 Lua 依赖库
 *   resources/      资源文件
 *
 * 安装 = 解压到 files/plugins/<id>/ + 解析 manifest 写入注册表。
 */
class InstallerManager(
    private val context: Application,
    private val xmlManager: XmlManager
) {
    companion object {
        private const val PLUGINS_DIR = "plugins"
        private const val MANIFEST_YAML = "manifest.yaml"

        /** 插件 id 白名单：字母/数字/下划线/连字符，点号仅作命名空间分段（禁止 .. / 空段 / /），最长 64，杜绝路径穿越。 */
        private val PLUGIN_ID_REGEX = Regex("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*$")
        private const val PLUGIN_ID_MAX_LENGTH = 64

        /** 入口脚本：普通文件名，不允许路径分隔符与 ".."。 */
        private val ENTRY_SCRIPT_REGEX = Regex("^[A-Za-z0-9_.-]{1,128}$")

        internal fun isValidPluginId(id: String): Boolean =
            id.length <= PLUGIN_ID_MAX_LENGTH && PLUGIN_ID_REGEX.matches(id)

        private fun isValidEntryScript(name: String): Boolean =
            ENTRY_SCRIPT_REGEX.matches(name) && !name.contains("..")


        private val manifestYaml: Yaml by lazy {
            Yaml(configuration = YamlConfiguration(strictMode = false))
        }

        /** 解析 manifest.yaml 文本（kam 类型化解析），失败时携带可读的错误提示。 */
        internal fun parseManifestContent(content: String): PluginParseResult = try {
            val manifest = manifestYaml.decodeFromString(PluginManifest.serializer(), content)
            val declaredHosts = manifest.network?.hosts.orEmpty()
                .filter { it.isNotBlank() }

            PluginParseResult.Success(
                PluginConfig(
                    id = manifest.id,
                    name = manifest.name ?: manifest.id,
                    version = manifest.version,
                    description = manifest.description ?: "",
                    type = manifest.type,
                    minHostVersion = manifest.minHostVersion?.takeIf { it.isNotBlank() },
                    maxHostVersion = manifest.maxHostVersion?.takeIf { it.isNotBlank() },
                    entryScript = manifest.entry,
                    declaredHosts = declaredHosts,
                    allowCustomHosts = manifest.network?.allowCustomHosts ?: false
                )
            )
        } catch (e: Exception) {
            Log.e("InstallerManager", "parsePluginConfig yaml failed", e)
            PluginParseResult.Failure(manifestError(e))
        }

        /** 把 manifest 解析异常整理成可读的提示（kaml 消息含行号/字段）。 */
        private fun manifestError(e: Exception): String {
            val detail = e.message
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: e.javaClass.simpleName
            return "manifest.yaml 解析失败：$detail"
        }
    }

    sealed class InstallResult {
        data class Success(val pluginInfo: PluginInfo) : InstallResult()
        data class Failure(val reason: String, val exception: Throwable? = null) : InstallResult()
    }

    private val pluginsDir: File by lazy {
        File(context.filesDir, PLUGINS_DIR).apply { mkdirs() }
    }

    suspend fun installPlugin(
        pluginFile: File,
        forceOverwrite: Boolean = false,
        source: PluginSource = PluginSource.FILE
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!pluginFile.exists()) {
            return@withContext InstallResult.Failure("插件文件不存在")
        }

        val pluginConfig = when (val parsed = parsePluginConfig(pluginFile)) {
            is PluginParseResult.Failure -> return@withContext InstallResult.Failure(parsed.reason)
            is PluginParseResult.Success -> parsed.config
        }
        val pluginId = pluginConfig.id
        if (!isValidPluginId(pluginId)) {
            return@withContext InstallResult.Failure("非法插件 id: $pluginId（仅允许字母/数字/下划线/连字符，点号分段，最长 64）")
        }
        val entryScript = pluginConfig.entryScript ?: "main.lua"
        if (!isValidEntryScript(entryScript)) {
            return@withContext InstallResult.Failure("非法入口脚本: $entryScript")
        }
        val pluginDir = getPluginDirectory(pluginId)

        // 校验插件声明的宿主版本范围
        val hostVersion = com.kingzcheung.xime.plugin.core.util.VersionUtil.getHostVersionName(context)
        if (hostVersion != null &&
            !com.kingzcheung.xime.plugin.core.util.VersionUtil.isHostSupported(
                hostVersion, pluginConfig.minHostVersion, pluginConfig.maxHostVersion
            )
        ) {
            val range = buildString {
                append("当前主应用版本 v$hostVersion 不在插件支持范围内")
                if (!pluginConfig.minHostVersion.isNullOrBlank()) {
                    append("（最低 v${pluginConfig.minHostVersion}")
                    if (!pluginConfig.maxHostVersion.isNullOrBlank()) {
                        append(" - v${pluginConfig.maxHostVersion}")
                    }
                    append("）")
                }
            }
            return@withContext InstallResult.Failure(range)
        }

        val existingPlugin = xmlManager.getPluginById(pluginId)

        // Lua 插件无版本号概念：只有首次安装或强制覆盖才重新解压
        if (!forceOverwrite && existingPlugin != null) {
            return@withContext InstallResult.Success(existingPlugin)
        }

        if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        }
        pluginDir.mkdirs()

        try {
            extractPluginArchive(pluginFile, pluginDir)
            val entryFile = File(pluginDir, entryScript)
            if (!entryFile.exists()) {
                throw IllegalArgumentException("Lua 入口脚本不存在: $entryScript")
            }

            val pluginInfo = PluginInfo(
                id = pluginConfig.id,
                name = pluginConfig.name,
                iconResId = 0,
                description = pluginConfig.description,
                versionCode = 0,
                versionName = pluginConfig.version,
                path = entryFile.absolutePath,
                type = pluginConfig.type,
                enabled = existingPlugin?.enabled ?: true,
                installTime = existingPlugin?.installTime ?: System.currentTimeMillis(),
                source = source,
                minHostVersion = pluginConfig.minHostVersion,
                maxHostVersion = pluginConfig.maxHostVersion,
                trustLevel = com.kingzcheung.xime.plugin.core.util.PluginSignatureUtil.classifyLuaPlugin(source),
                entryScript = entryScript,
                declaredHosts = pluginConfig.declaredHosts,
                allowCustomHosts = pluginConfig.allowCustomHosts
            )

            if (existingPlugin != null) {
                xmlManager.updatePlugin(pluginInfo)
            } else {
                xmlManager.addPlugin(pluginInfo)
            }
            xmlManager.flushToDisk()

            InstallResult.Success(pluginInfo)
        } catch (e: Exception) {
            pluginDir.deleteRecursively()
            InstallResult.Failure("插件安装失败: ${e.message}", e)
        }
    }

    suspend fun uninstallPlugin(pluginId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isValidPluginId(pluginId)) {
            Log.e("InstallerManager", "uninstallPlugin 拒绝非法 id: $pluginId")
            return@withContext false
        }
        val pluginDir = getPluginDirectory(pluginId)
        if (pluginDir.exists()) {
            pluginDir.deleteRecursively()
        }
        xmlManager.removePlugin(pluginId)
        xmlManager.flushToDisk()
        true
    }

    suspend fun installPluginFromUri(uri: Uri): InstallResult = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return@withContext InstallResult.Failure("无法解析文件路径"))
            installPlugin(file, forceOverwrite = true, source = PluginSource.FILE)
        } else {
            val tempFile = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.xipk")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext InstallResult.Failure("无法读取文件")
                installPlugin(tempFile, forceOverwrite = true, source = PluginSource.FILE)
            } catch (e: Exception) {
                InstallResult.Failure("插件导入失败: ${e.message}", e)
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }

    internal fun getPluginDirectory(pluginId: String): File {
        require(isValidPluginId(pluginId)) { "非法插件 id: $pluginId" }
        return File(pluginsDir, pluginId)
    }

    /** 解压 Lua 插件包到插件目录（防 zip-slip 路径穿越）。 */
    private fun extractPluginArchive(archiveFile: File, pluginDir: File) {
        ZipFile(archiveFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                // Windows 打包工具可能产生 "\" 分隔的条目名，统一规范为 "/"
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("lib/")) continue
                if (name.contains("../") || name.startsWith("/")) {
                    throw IllegalArgumentException("非法路径: $name")
                }
                val outputFile = File(pluginDir, name)
                outputFile.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    outputFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun parsePluginConfig(pluginFile: File): PluginParseResult {
        val content = try {
            ZipFile(pluginFile).use { zip ->
                val entry = zip.getEntry(MANIFEST_YAML)
                    ?: return PluginParseResult.Failure("插件配置解析失败（缺少 manifest.yaml）")
                zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e("InstallerManager", "parsePluginConfig failed", e)
            return PluginParseResult.Failure("插件配置解析失败：${e.message}")
        }

        return parseManifestContent(content)
    }
}

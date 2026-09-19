package com.kingzcheung.xime.plugin.core.api

import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable

/**
 * 剪贴板条目（同步用）。与 ximed `Profile` JSON 同构（snake_case 序列化）：
 *
 * ```json
 * {
 *   "type": "text",
 *   "hash": "9f86d081884c7d65...",
 *   "text": "完整文本内容",
 *   "has_data": false,
 *   "data_name": null,
 *   "size": 12,
 *   "source": "device-a"
 * }
 * ```
 *
 * @param type    类型（首版仅 "text"）
 * @param hash    小写 hex SHA256(utf8(text))
 * @param text    文本内容
 * @param hasData 是否携带附件数据（图片/文件，首版 false）
 * @param dataName 附件文件名（首版 null）
 * @param size    内容字节大小
 * @param source  来源设备标识
 */
data class ClipboardProfile(
    val type: String = "text",
    val hash: String,
    val text: String,
    val hasData: Boolean = false,
    val dataName: String? = null,
    val size: Long = 0,
    val source: String? = null
) {
    companion object {
        /** 由文本构造 text 类型 profile，自动计算 hash 与 size。 */
        fun fromText(text: String, source: String? = null): ClipboardProfile {
            return ClipboardProfile(
                type = "text",
                hash = sha256Hex(text.toByteArray(Charsets.UTF_8)),
                text = text,
                hasData = false,
                dataName = null,
                size = text.toByteArray(Charsets.UTF_8).size.toLong(),
                source = source
            )
        }

        fun sha256Hex(data: ByteArray): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest(data).joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * 剪贴板同步插件能力接口（宿主侧，由 Lua 适配器实现，协议逻辑在 Lua）。
 *
 * 同步引擎（宿主 ClipboardSyncBridge）只依赖此接口做 push / pull / 连接测试，
 * 具体传输协议（WebDAV / S3 / ximed HTTP）由插件 Lua 用 `host.http` + `host.crypto`
 * 实现。
 */
interface ClipboardSyncPlugin : IPluginEntryClass, IPluginConfigurable {

    /**
     * 推送本地 profile 到远端（宿主在剪贴板变化时调用）。
     *
     * @param profile 本地剪贴板 profile（含 hash，引擎已做去重）
     * @return 成功 true；失败 false（引擎记录错误并退避重试）
     */
    suspend fun push(profile: ClipboardProfile): Boolean

    /**
     * 拉取远端 profile（宿主轮询调用）。
     *
     * ETag / If-None-Match 条件请求由插件 Lua 内部用 `host.config` 自行缓存管理：
     * 远端返回 304 或无变更时返回 null（宿主据此跳过写回）。
     *
     * @return 远端 profile；无变更/失败返回 null
     */
    suspend fun pull(): ClipboardProfile?

    /** 校验配置可用性（连接测试），返回错误消息（null 表示成功）。 */
    suspend fun testConnection(): String?
}

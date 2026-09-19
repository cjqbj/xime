package com.kingzcheung.xime.plugin.core.lua.sdk

/**
 * Lua 插件契约：入口脚本（main.lua）导出函数与数据格式的约定。
 *
 * ## 入口脚本
 * 插件包根目录的 main.lua（manifest.yaml 的 `entry` 字段指定）必须 `return` 一个
 * **导出表（table）**，宿主读取表中函数并按约定调用。
 *
 * ## 生命周期（可选）
 * - `onLoad()` 插件加载时调用（宿主已注入 host API）
 * - `onUnload()` 插件卸载时调用
 *
 * ## 分类能力（按 manifest.type 约定）
 * ### emoji 表情
 * - `getCategories()` -> string[]
 * - `getEmojis(category, searchText, topK)` -> EmojiItem[]
 *   每项: { id: string, text: string, imageUrl?: string, category: string }
 *   - text 同时作为显示文本与插入文本
 *   - imageUrl 可通过 host.resource.path() 获得（图片渲染由宿主完成）
 * - `getCategoryLayoutConfig(category)`（可选）-> { columns?: int, itemHeightDp?: int }
 *
 * ### speech 语音（规划中）
 * - `getSettingsSchema()` 配置字段声明（与 manifest.configSchema 等价）
 * - `getOptions(key)` 动态选项（如 ASR 模型列表）
 * - `start()` / `pushPcm()` / `stop()` 音频流式识别（网络 API 由宿主白名单提供）
 *
 * ## 数据格式
 * Lua 返回值一律使用 Lua table（数组或 map），宿主统一做 table -> Kotlin 转换；
 * 函数不存在或抛错时，宿主返回空结果（不崩溃）。
 */
object LuaPluginContract {

    /** SDK 版本（宿主注入的 host.sdkVersion）。插件 manifest 可声明 `sdkVersion` 声明所需 SDK 版本。 */
    const val SDK_VERSION = "0.1.0"

    // ---- 宿主注入的全局对象 ----
    const val GLOBAL_HOST = "host"

    // ---- 生命周期 ----
    const val FN_ON_LOAD = "onLoad"
    const val FN_ON_UNLOAD = "onUnload"

    // ---- emoji ----
    const val FN_GET_CATEGORIES = "getCategories"
    const val FN_GET_EMOJIS = "getEmojis"
    const val FN_GET_CATEGORY_LAYOUT = "getCategoryLayoutConfig"

    // ---- emoji item 字段 ----
    const val FIELD_ID = "id"
    const val FIELD_TEXT = "text"
    const val FIELD_IMAGE_URL = "imageUrl"
    const val FIELD_CATEGORY = "category"
}

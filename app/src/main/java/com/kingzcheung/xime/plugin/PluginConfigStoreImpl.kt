package com.kingzcheung.xime.plugin

import android.app.Application
import android.content.Context
import com.kingzcheung.xime.plugin.core.config.PluginConfigStore

class PluginConfigStoreImpl(
    private val app: Application,
    private val pluginId: String
) : PluginConfigStore {

    private val prefs: android.content.SharedPreferences =
        app.getSharedPreferences("plugin_cfg_$pluginId", Context.MODE_PRIVATE)

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun keys(): Set<String> = prefs.all.keys
}

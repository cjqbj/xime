package com.kingzcheung.xime.plugin.core.model

import android.app.Application
import com.kingzcheung.xime.plugin.core.api.IPluginEntryClass
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import com.kingzcheung.xime.plugin.core.runtime.installer.XmlManager
import com.kingzcheung.xime.plugin.core.runtime.lifecycle.PluginLifecycleManager
import com.kingzcheung.xime.plugin.core.runtime.loader.LoadedPluginInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

internal class PluginFrameworkContext(val application: Application) {

    val initState = MutableStateFlow(InitState.NOT_INITIALIZED)

    val xmlManager = XmlManager(application)
    val installerManager = InstallerManager(application, xmlManager)

    val loadedPlugins = ConcurrentHashMap<String, LoadedPluginInfo>()
    val pluginInstances = ConcurrentHashMap<String, IPluginEntryClass>()

    val loadedPluginsFlow: StateFlow<Map<String, LoadedPluginInfo>> =
        MutableStateFlow(loadedPlugins.toMap())
    val pluginInstancesFlow: StateFlow<Map<String, IPluginEntryClass>> =
        MutableStateFlow(pluginInstances.toMap())

    lateinit var lifecycleManager: PluginLifecycleManager

    fun initializeLifecycleManager() {
        lifecycleManager = PluginLifecycleManager(
            application = application,
            xmlManager = xmlManager,
            installerManager = installerManager,
            loadedPlugins = loadedPlugins,
            pluginInstances = pluginInstances
        )
    }
}

enum class InitState {
    NOT_INITIALIZED,
    INITIALIZING,
    INITIALIZED
}

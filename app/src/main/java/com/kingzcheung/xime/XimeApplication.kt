package com.kingzcheung.xime

import android.app.Application
import android.os.Build
import android.util.Log
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kingzcheung.xime.plugin.ExtensionManager
import com.kingzcheung.xime.plugin.PluginConfigStoreImpl
import com.kingzcheung.xime.util.FileLogger
import com.kingzcheung.xime.util.RpcUiController
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.rime.RimeConfigHelper
import com.kingzcheung.xime.model.ModelRuntime
import com.kingzcheung.xime.rime.RimeEngine
import com.kingzcheung.xime.settings.KeysConfigHelper
import com.kingzcheung.xime.ui.keyboard.AppFonts
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.ui.theme.KeyboardThemes
import com.kingzcheung.xime.util.LauncherIconController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class XimeApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(16 * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .build()
    }
    
    companion object {
        private const val TAG = "XimeApplication"

        /** 主进程 Application 单例，供 TaskScheduler 等无 Context 的静态桥使用。 */
        @JvmStatic
        lateinit var instance: XimeApplication
            private set
    }

    private val applicationScope = CoroutineScope(Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        instance = this
        FileLogger.init(this)
        RpcUiController.initialize(this)
        // 进程名守卫：:inference / :asr 侧进程不引导 Python，
        // 避免侧进程抢 HTTP 端口并以同一 MQTT topic 成为第二个应答者。
        if (isMainProcess()) {
            // 最早期：外置设置镜像恢复（外置为准），必须在任何业务读取 kime_settings 之前
            com.kingzcheung.xime.storage.SettingsMirror.reconcile(this)
            startPythonRpc()
        } else {
            FileLogger.i(TAG, "Skip python rpc bootstrap in side process")
        }
        AppFonts.initialize(this)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.e("CrashHandler", "Uncaught exception on thread: ${thread.name}", throwable)
                FileLogger.flush()
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        val isDebug = BuildConfig.DEBUG
        PluginManager.configStoreFactory =
            PluginManager.PluginConfigStoreFactory { app, pluginId ->
                PluginConfigStoreImpl(app, pluginId)
            }
        PluginManager.wsHostApiFactory = { pluginId ->
            com.kingzcheung.xime.plugin.ws.WsHostApiImpl(this, pluginId)
        }
        PluginManager.httpHostApiFactory = { pluginId ->
            com.kingzcheung.xime.plugin.http.HttpHostApiImpl(this, pluginId)
        }
        PluginManager.cryptoHostApiFactory = {
            com.kingzcheung.xime.plugin.crypto.CryptoHostApiImpl()
        }
        PluginManager.initialize(this) {
            if (isDebug) {
                PluginManager.installPluginsFromAssetsForDebug("plugins")
            }
            
            PluginManager.loadEnabledPlugins()
        }
        
        ExtensionManager.initialize(this)

        // 从 xime.yaml 加载配色方案
        KeyboardThemes.initFromConfig(this)

        // 从 xime.yaml 的 style 读取默认主题和显示模式
        SettingsPreferences.defaultKeyboardTheme = KeysConfigHelper.loadDefaultThemeId(this)
        SettingsPreferences.defaultDarkMode = KeysConfigHelper.loadDefaultDarkMode(this)

        // 默认隐藏桌面图标，并允许设置页在运行时切换显示状态。
        LauncherIconController.syncFromPreference(this)

        // 初始化模型运行时（内存管理 + 生命周期）
        ModelRuntime.attach(this)

        preInitializeRimeEngine()
    }
    
    private fun isMainProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            try {
                File("/proc/self/cmdline").inputStream().bufferedReader().readText()
                    .trim { it == '\u0000' || it.isWhitespace() }
            } catch (_: Exception) {
                ""
            }
        }
        return processName == packageName
    }

    private fun preInitializeRimeEngine() {
        if (RimeEngine.isInitialized()) {
            return
        }
        
        applicationScope.launch {
            try {
                val (userDataDir, sharedDataDir) = RimeConfigHelper.initializeRimeDataAsync(this@XimeApplication)
                val engine = RimeEngine.getInstance()
                engine.initialize(userDataDir, sharedDataDir)

                // 首次安装/升级后静默编译词库。ensureDeployment 内部带互斥且 hash 一致时跳过，
                // 统一负责 deploymentDone/hash 状态，避免与输入法服务的初始化重复触发全量编译。
                RimeConfigHelper.ensureDeployment(this@XimeApplication)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pre-initialize Rime engine", e)
            }
        }
    }

    private fun startPythonRpc() {
        applicationScope.launch {
            try {
                if (!Python.isStarted()) {
                    Python.start(AndroidPlatform(this@XimeApplication))
                }
                val logPath = if (SettingsPreferences.isRpcLogToDiskEnabled(this@XimeApplication)) {
                    File(filesDir, "rpc.log").absolutePath
                } else {
                    null
                }
                Python.getInstance()
                    .getModule("app")
                    .callAttr("start", logPath, filesDir.absolutePath)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to start Python RPC", e)
            }
        }
    }
}
package com.kingzcheung.xime.association

import android.content.Context
import com.kingzcheung.xime.model.ModelRuntime
import com.kingzcheung.xime.model.ModelStorage
import com.kingzcheung.xime.service.InferenceClient
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.io.File

object OnnxAssociationEngine {
    private const val TAG = "OnnxAssociationEngine"

    private var isInitialized = false
    private var warmupStarted = false
    private val warmupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var inferenceClient: InferenceClient? = null

    suspend fun initialize(context: Context): Boolean {
        if (isInitialized) {
            FileLogger.d(TAG, "Already initialized")
            return true
        }

        ModelRuntime.register(
            id = "predictive_text",
            loader = { initialize(context) },
            releaser = { release() },
            label = "智能联想模型"
        )

        try {
            // 模型 id 由设置决定，支持 small/base 等多版本共存切换
            val modelId = SettingsPreferences.getPredictionSelectedModel(context)
            val modelDir = ModelStorage.getModelDir(context, modelId)
            modelDir.mkdirs()

            // 兼容旧版：把旧路径模型迁移到统一目录
            ModelStorage.migrateLegacyForModel(context, modelId)

            val filesToCheck = listOf("vocab.json", "model_int8_dynamic.onnx")
            for (fileName in filesToCheck) {
                val file = File(modelDir, fileName)
                if (!file.exists()) {
                    FileLogger.e(TAG, "$fileName not found at ${file.absolutePath}")
                    return false
                }
                FileLogger.d(TAG, "$fileName exists: ${file.length()} bytes")
            }

            val modelFile = File(modelDir, "model_int8_dynamic.onnx")
            val vocabFile = File(modelDir, "vocab.json")
            FileLogger.d(TAG, "Using model: ${modelFile.name} (${modelFile.length()} bytes)")

            val client = InferenceClient(context)
            inferenceClient = client

            if (!client.ensureBound()) {
                FileLogger.e(TAG, "Failed to bind to InferenceService")
                return false
            }
            val ok = client.loadModel(
                InferenceClient.MODEL_PREDICTION,
                modelFile.absolutePath,
                vocabFile.absolutePath
            )
            if (!ok) {
                FileLogger.e(TAG, "Failed to load prediction model in inference process")
                return false
            }

            isInitialized = true
            FileLogger.i(TAG, "Prediction model loaded via IPC")
            ModelRuntime.markLoaded("predictive_text")
            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to initialize prediction: ${e.message}", e)
            return false
        }
    }

    suspend fun predict(inputText: String, topK: Int = 20): List<AssociationCandidate> = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            FileLogger.e(TAG, "Engine not initialized")
            return@withContext emptyList()
        }

        try {
            val client = inferenceClient ?: return@withContext emptyList()
            client.predict(inputText, topK)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Prediction failed: ${e.message}", e)
            emptyList()
        }
    }

    fun startWarmup() {
        if (!isInitialized || warmupStarted) return
        warmupStarted = true
        warmupScope.launch {
            try {
                val client = inferenceClient ?: return@launch
                client.predict("，", 5)
            } catch (e: Exception) {
                FileLogger.w(TAG, "Warmup prediction failed (non-fatal): ${e.message}")
            }
        }
    }

    fun release() {
        isInitialized = false
        inferenceClient?.apply {
            runBlocking { runCatching { unloadModel(InferenceClient.MODEL_PREDICTION) } }
            unbind()
        }
        inferenceClient = null
        ModelRuntime.markUnloaded("predictive_text")
        FileLogger.d(TAG, "Prediction model released")
    }

    fun isInitialized(): Boolean = isInitialized
}

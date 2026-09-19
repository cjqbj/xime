package com.kingzcheung.xime.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.kingzcheung.xime.association.AssociationCandidate
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class InferenceClient(private val context: Context) {

    companion object {
        private const val TAG = "InferenceClient"
        const val MODEL_PREDICTION = "predictive_text"
        const val MODEL_HANDWRITING = "handwriting"
    }

    private var service: IInferenceService? = null
    private var bound = false
    private val connectLatch = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IInferenceService.Stub.asInterface(binder)
            bound = true
            connectLatch.countDown()
            FileLogger.i(TAG, "Connected to InferenceService")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
            FileLogger.w(TAG, "InferenceService disconnected (crash?)")
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            bound = false
            connectLatch.countDown()
            FileLogger.e(TAG, "InferenceService binding died")
        }
    }

    suspend fun ensureBound(): Boolean = withContext(Dispatchers.IO) {
        if (bound && service != null) return@withContext true

        val intent = Intent(context, InferenceService::class.java)
        val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!ok) return@withContext false

        val connected = connectLatch.await(3, TimeUnit.SECONDS)
        connected
    }

    fun unbind() {
        try {
            context.unbindService(connection)
        } catch (_: Exception) {}
        bound = false
        service = null
    }

    private fun requireService(): IInferenceService {
        return service ?: throw IllegalStateException("InferenceService not bound")
    }

    suspend fun loadModel(modelId: String, modelPath: String, extraPath: String = ""): Boolean = withContext(Dispatchers.IO) {
        try {
            requireService().loadModel(modelId, modelPath, extraPath)
        } catch (e: Exception) {
            FileLogger.e(TAG, "loadModel($modelId) failed", e)
            false
        }
    }

    suspend fun unloadModel(modelId: String) = withContext(Dispatchers.IO) {
        try {
            requireService().unloadModel(modelId)
        } catch (_: Exception) {}
    }

    suspend fun isModelLoaded(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            requireService().isModelLoaded(modelId)
        } catch (_: Exception) {
            false
        }
    }

    suspend fun predict(text: String, topK: Int = 20): List<AssociationCandidate> = withContext(Dispatchers.IO) {
        try {
            val result = requireService().predict(MODEL_PREDICTION, text, topK)
            val candidates = mutableListOf<AssociationCandidate>()
            for (i in result.indices step 2) {
                val word = result[i]
                val score = result.getOrNull(i + 1)?.toFloatOrNull() ?: continue
                candidates.add(AssociationCandidate(word, score))
            }
            candidates
        } catch (e: Exception) {
            FileLogger.e(TAG, "predict failed", e)
            emptyList()
        }
    }

    suspend fun processAudioBytes(input: ByteArray, sampleRate: Int = 16000): ByteArray = withContext(Dispatchers.IO) {
        try {
            requireService().processAudioBytes(input, sampleRate)
        } catch (_: Exception) {
            input
        }
    }
}

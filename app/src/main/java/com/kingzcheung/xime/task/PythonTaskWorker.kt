package com.kingzcheung.xime.task

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chaquo.python.Python
import com.kingzcheung.xime.util.FileLogger
import java.util.concurrent.TimeUnit

/**
 * WorkManager 触发入口：把执行转交 Python task_manager.execute_task。
 *
 * 调度模型：Python 侧 task_manager 用 OneTimeWorkRequest 自接力实现任意秒级周期
 * （PeriodicWorkRequest 最小 15 分钟不满足需求）。WorkManager 保证系统级可靠触发，
 * 进程死亡/重启后依然会被拉起执行。
 */
class PythonTaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        return try {
            if (!Python.isStarted()) {
                FileLogger.w(TAG, "Python 未启动，task=$taskId 稍后重试")
                return Result.retry()
            }
            val r = Python.getInstance()
                .getModule("task_manager")
                .callAttr("execute_task", taskId)
                .toString()
            FileLogger.i(TAG, "task=$taskId result=$r")
            Result.success()
        } catch (e: Throwable) {
            FileLogger.e(TAG, "task=$taskId 执行失败", e)
            Result.failure()
        }
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        private const val TAG = "PythonTaskWorker"
    }
}

/**
 * Python 经 jclass 调用的静态调度桥。
 * 用唯一工作名 pytask_<id> + REPLACE，保证同 id 只有一个在排队。
 */
object TaskScheduler {

    @JvmStatic
    fun schedule(taskId: String, delaySeconds: Int) {
        val ctx = com.kingzcheung.xime.XimeApplication.instance
        val data = Data.Builder()
            .putString(PythonTaskWorker.KEY_TASK_ID, taskId)
            .build()
        val req = OneTimeWorkRequestBuilder<PythonTaskWorker>()
            .setInputData(data)
            .setInitialDelay(delaySeconds.toLong(), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork(workName(taskId), ExistingWorkPolicy.REPLACE, req)
    }

    @JvmStatic
    fun cancel(taskId: String) {
        val ctx = com.kingzcheung.xime.XimeApplication.instance
        WorkManager.getInstance(ctx).cancelUniqueWork(workName(taskId))
    }

    private fun workName(taskId: String) = "pytask_$taskId"
}

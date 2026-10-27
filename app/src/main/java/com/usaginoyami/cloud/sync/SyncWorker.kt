package com.usaginoyami.cloud.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Roda `rclone copy` direto (sem precisar do app aberto) para uma regra de sync. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val rule = SyncStore.load(applicationContext).firstOrNull { it.id == id && it.enabled }
            ?: return@withContext Result.success()

        val bin = File(applicationContext.applicationInfo.nativeLibraryDir, "librclone.so")
        if (!bin.exists()) return@withContext Result.failure()
        val home = File(applicationContext.filesDir, "rclone").apply { mkdirs() }
        val cache = File(applicationContext.cacheDir, "rclone-sync").apply { mkdirs() }

        val cmd = listOf(
            bin.absolutePath, "copy", rule.localPath, "${rule.account}:${rule.remotePath}",
            "--config", File(home, "rclone.conf").absolutePath,
            "--cache-dir", cache.absolutePath,
            "--exclude", ".thumbnails/**",
            "--exclude", ".trashed-*",
            "--exclude", ".pending-*",
        )
        try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["HOME"] = home.absolutePath
            pb.environment()["TMPDIR"] = cache.absolutePath
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim().takeLast(300)
            val code = p.waitFor()
            val ok = code == 0
            SyncStore.update(applicationContext, id) {
                it.copy(lastRun = System.currentTimeMillis(), lastResult = if (ok) "OK" else "Erro: $out")
            }
            if (ok) Result.success() else Result.retry()
        } catch (e: Exception) {
            SyncStore.update(applicationContext, id) {
                it.copy(lastRun = System.currentTimeMillis(), lastResult = "Erro: ${e.message}")
            }
            Result.retry()
        }
    }
}

object SyncScheduler {
    private fun name(id: String) = "usagi_sync_$id"

    fun schedule(ctx: Context, rule: SyncRule) {
        if (!rule.enabled) { cancel(ctx, rule.id); return }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (rule.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val req = PeriodicWorkRequestBuilder<SyncWorker>(rule.everyHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setInputData(workDataOf("id" to rule.id))
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(name(rule.id), ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(ctx: Context, id: String) {
        WorkManager.getInstance(ctx).cancelUniqueWork(name(id))
    }
}

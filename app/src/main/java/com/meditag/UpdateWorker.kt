package com.meditag

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Comprobación automática de actualizaciones una vez por semana (con internet).
 * Solo descarga el pequeño version.json de GitHub; no envía ningún dato.
 * Avisa con una notificación una sola vez por cada versión nueva.
 */
object AutoUpdate {
    private const val WORK = "update-check"
    private const val KEY_ENABLED = "auto_update"
    private const val KEY_NOTIFIED = "update_notified"
    const val CHANNEL = "updates"
    private const val NOTIF_ID = 3

    fun enabled(ctx: Context) = MedRepo.prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        MedRepo.prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply()
        schedule(ctx)
    }

    /** Programa (o cancela) la tarea semanal según el ajuste. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled(ctx)) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.DAYS)
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun createChannel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Aviso cuando hay una versión nueva de MediTag"
            }
        )
    }

    /** Muestra el aviso si esta versión no se había avisado ya. */
    fun notifyIfNew(ctx: Context, version: String, url: String) {
        val p = MedRepo.prefs(ctx)
        if (p.getString(KEY_NOTIFIED, null) == version) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            ctx, 4, Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Hay una versión nueva de MediTag: $version")
            .setContentText("Toca para descargarla e instálala encima (no se pierde nada).")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
            p.edit().putString(KEY_NOTIFIED, version).apply()
        } catch (_: SecurityException) { }
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!AutoUpdate.enabled(applicationContext)) return Result.success()
        return when (val r = UpdateCheck.check()) {
            is UpdateCheck.Available -> { AutoUpdate.notifyIfNew(applicationContext, r.name, r.url); Result.success() }
            is UpdateCheck.UpToDate -> Result.success()
            is UpdateCheck.Failed -> Result.retry()
        }
    }
}

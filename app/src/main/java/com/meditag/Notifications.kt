package com.meditag

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MediTagApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MedRepo.init(this)
        Notifications.createChannels(this)
    }
}

object Notifications {
    const val CH_ALARM = "alarm"
    const val CH_REMIND = "reminder"
    const val ALARM_NOTIF_ID = 1
    const val CH_ALARM_DND = "alarm_dnd"
    fun reminderId(medId: Int) = 1000 + medId
    fun missedId(medId: Int) = 2000 + medId

    /** Canal de la alarma: si hay acceso a No molestar, uno que se lo salta. */
    fun alarmChannel(ctx: Context): String =
        if (ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) CH_ALARM_DND else CH_ALARM

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Alarma de medicación", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alarma que suena si no te tomas la medicación"
                setSound(null, null) // el sonido lo genera el servicio
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        if (nm.isNotificationPolicyAccessGranted && nm.getNotificationChannel(CH_ALARM_DND) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH_ALARM_DND, "Alarma de medicación (No molestar)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Alarma que suena aunque esté activado No molestar"
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
        nm.createNotificationChannel(
            NotificationChannel(CH_REMIND, "Recordatorios", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Aviso al empezar el rango horario de una toma"
            }
        )
    }

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    fun showReminder(ctx: Context, med: Medication) {
        if (!canNotify(ctx)) return
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Hora de tomar ${med.name}")
            .setContentText("Escanea la etiqueta antes de las ${fmt(med.endMin)} o sonará la alarma")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(reminderId(med.id), n)
        } catch (_: SecurityException) { }
    }

    /** Aviso fijo de toma sin respuesta (se quita al escanear o cancelar la toma). */
    fun showMissed(ctx: Context, med: Medication, text: String) {
        if (!canNotify(ctx)) return
        // Pantalla de aviso: con la pantalla apagada se abre ya sobre el bloqueo (sin encenderla),
        // así es lo primero que se ve al coger el móvil.
        val pi = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MissedActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CH_REMIND)
            .setSmallIcon(R.drawable.ic_notif)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pi, true)
            .setContentTitle("✖ Sin tomar: ${med.name}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(missedId(med.id), n)
        } catch (_: SecurityException) { }
    }

    fun alarmNotification(ctx: Context, names: String): Notification {
        val pi = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(ctx, alarmChannel(ctx))
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("¡TOMA TU MEDICACIÓN!")
            .setContentText("$names — escanea la etiqueta NFC para parar la alarma")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
    }
}

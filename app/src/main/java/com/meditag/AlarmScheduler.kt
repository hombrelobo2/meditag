package com.meditag

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Por cada medicamento programa:
 *  - un recordatorio (notificación) al inicio del rango
 *  - la ALARMA al final del rango, que solo se ejecuta si no se ha tomado/cancelado
 */
object AlarmScheduler {
    const val ACTION_REMIND = "com.meditag.REMIND"
    const val ACTION_ALARM = "com.meditag.ALARM"
    const val EXTRA_ID = "med_id"

    private fun reqRemind(id: Int) = id * 2
    private fun reqAlarm(id: Int) = id * 2 + 1

    fun scheduleAll(ctx: Context) = MedRepo.all(ctx).forEach { schedule(ctx, it) }

    fun schedule(ctx: Context, med: Medication) {
        cancel(ctx, med.id)
        if (!med.enabled || med.mode == Mode.ON_DEMAND) return

        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val from: LocalDate = if (med.isDoneOn(today)) today.plusDays(1) else today
        val day = med.nextScheduled(from) ?: return // no quedan días con toma
        val startAt = day.atTime(med.start)
        var alarmAt = day.atTime(med.end)

        if (!alarmAt.isAfter(now)) {
            alarmAt = if (med.repeatPending()) {
                // Repetición programada tras una alarma sin respuesta
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(med.nextRepeatAt), ZoneId.systemDefault())
            } else {
                // Fuera de plazo y sin tomar (p.ej. tras reiniciar el móvil): suena ya.
                now.plusSeconds(3)
            }
        }

        if (startAt.isAfter(now)) setExact(ctx, startAt, pi(ctx, reqRemind(med.id), ACTION_REMIND, med.id))
        setAlarmClock(ctx, alarmAt, pi(ctx, reqAlarm(med.id), ACTION_ALARM, med.id))
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pi(ctx, reqRemind(id), ACTION_REMIND, id))
        am.cancel(pi(ctx, reqAlarm(id), ACTION_ALARM, id))
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pi(ctx: Context, req: Int, action: String, id: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, req,
            Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun setExact(ctx: Context, t: LocalDateTime, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(t), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(t), pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(t), pi)
        }
    }

    private fun setAlarmClock(ctx: Context, t: LocalDateTime, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(millis(t), show), pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(t), pi)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
        val med = MedRepo.get(ctx, id) ?: return
        val today = LocalDate.now()
        if (!med.enabled || med.isDoneOn(today) || !med.isScheduledOn(today)) {
            AlarmScheduler.schedule(ctx, med)
            return
        }
        when (intent.action) {
            AlarmScheduler.ACTION_REMIND -> Notifications.showReminder(ctx, med)
            AlarmScheduler.ACTION_ALARM -> AlarmService.start(ctx, id)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.scheduleAll(ctx)
    }
}

package com.meditag

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Por cada toma (franja) de cada medicamento programa:
 *  - un recordatorio (notificación) al inicio de la franja
 *  - la ALARMA al final de la franja, que solo se ejecuta si no se ha tomado/cancelado
 */
object AlarmScheduler {
    const val ACTION_REMIND = "com.meditag.REMIND"
    const val ACTION_ALARM = "com.meditag.ALARM"
    const val EXTRA_ID = "med_id"
    const val EXTRA_SLOT = "slot"
    const val EXTRA_KEY = "occ_key"

    private fun reqRemind(id: Int, slot: Int) = Occ.key(id, slot) * 2
    private fun reqAlarm(id: Int, slot: Int) = Occ.key(id, slot) * 2 + 1

    fun scheduleAll(ctx: Context) = MedRepo.all(ctx).forEach { schedule(ctx, it) }

    fun schedule(ctx: Context, med: Medication) {
        cancel(ctx, med.id)
        if (!med.enabled || med.mode == Mode.ON_DEMAND) return
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        med.slots.forEachIndexed { i, slot ->
            // Primer día (desde hoy) en que esta toma cuenta y no está hecha
            val day = generateSequence(today) { it.plusDays(1) }.take(400).firstOrNull { d ->
                med.counts(d, i) && !(d == today && med.state(i).done)
            } ?: return@forEachIndexed
            val startAt = day.atTime(slot.startTime)
            var alarmAt = day.atTime(slot.endTime)
            if (!alarmAt.isAfter(now)) {
                alarmAt = if (med.repeatPending(i)) {
                    // Repetición programada tras una alarma sin respuesta
                    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(med.state(i).nextRepeatAt), ZoneId.systemDefault())
                } else {
                    // Fuera de plazo y sin tomar (p.ej. tras reiniciar el móvil): suena ya.
                    now.plusSeconds(3)
                }
            }
            if (startAt.isAfter(now)) setExact(ctx, startAt, pi(ctx, reqRemind(med.id, i), ACTION_REMIND, med.id, i))
            setAlarmClock(ctx, alarmAt, pi(ctx, reqAlarm(med.id, i), ACTION_ALARM, med.id, i))
        }
    }

    fun cancel(ctx: Context, id: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (i in 0 until MAX_SLOTS) {
            am.cancel(pi(ctx, reqRemind(id, i), ACTION_REMIND, id, i))
            am.cancel(pi(ctx, reqAlarm(id, i), ACTION_ALARM, id, i))
        }
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pi(ctx: Context, req: Int, action: String, id: Int, slot: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, req,
            Intent(ctx, AlarmReceiver::class.java).setAction(action)
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_SLOT, slot),
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
        val med = MedRepo.get(ctx, intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)) ?: return
        val i = intent.getIntExtra(AlarmScheduler.EXTRA_SLOT, 0)
        if (i !in med.slots.indices) { AlarmScheduler.schedule(ctx, med); return }
        val s = med.slotStatus(i)
        when {
            intent.action == AlarmScheduler.ACTION_REMIND && (s == DoseStatus.PENDING || s == DoseStatus.DUE) ->
                Notifications.showReminder(ctx, med, i)
            intent.action == AlarmScheduler.ACTION_ALARM && (s == DoseStatus.OVERDUE || s == DoseStatus.DUE) ->
                AlarmService.start(ctx, Occ.key(med.id, i))
            else -> AlarmScheduler.schedule(ctx, med)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.scheduleAll(ctx)
    }
}

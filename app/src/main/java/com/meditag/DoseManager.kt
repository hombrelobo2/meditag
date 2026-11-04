package com.meditag

import android.app.NotificationManager
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ScanResult(val ok: Boolean, val message: String)

object Nfc {
    const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK

    /** UID de la etiqueta (NTAG215 = 7 bytes) en hex, p.ej. 04:A2:3B:1C:5D:80:00 */
    fun tagId(tag: Tag): String = tag.id.joinToString(":") { "%02X".format(it) }
}

/** Lógica de tomas: registrar, cancelar y decidir qué hacer al leer una etiqueta. */
object DoseManager {
    /** Minutos antes del inicio del rango en los que ya se acepta la toma. */
    private const val EARLY_MARGIN_MIN = 30

    fun onTagScanned(ctx: Context, tagId: String): ScanResult {
        // Alarma de prueba: cualquier pegatina la detiene y NO se registra ninguna toma.
        if (AlarmService.TEST_ID in AlarmService.ringing.value) {
            AlarmService.stop(AlarmService.TEST_ID)
            if (AlarmService.ringing.value.isEmpty()) {
                return ScanResult(true, "✔ Prueba superada: pegatina leída ($tagId). No se ha registrado ninguna toma.")
            }
        }
        val meds = MedRepo.all(ctx).filter { it.enabled && it.tagId.equals(tagId, ignoreCase = true) }
        if (meds.isEmpty()) return ScanResult(false, "Etiqueta no registrada ($tagId)")

        val now = LocalDateTime.now()
        val nowMin = now.hour * 60 + now.minute
        val ringing = AlarmService.ringing.value
        val taken = mutableListOf<String>()
        val tests = mutableListOf<String>()
        val already = mutableListOf<String>()
        val notYet = mutableListOf<String>()

        for (m in meds) {
            val s = m.status(now)
            when {
                s == DoseStatus.ON_DEMAND -> {
                    markOnDemand(ctx, m)
                    val n = m.todayCount() + 1
                    taken += "${m.name} (${n}ª de hoy)"
                }
                s == DoseStatus.DUE || s == DoseStatus.OVERDUE ||
                    (s == DoseStatus.PENDING && nowMin >= m.startMin - EARLY_MARGIN_MIN) -> {
                    markTaken(ctx, m); taken += m.name
                }
                s == DoseStatus.MISSED -> { markTaken(ctx, m, late = true); taken += "${m.name} (con retraso)" }
                m.id in ringing -> { AlarmService.stop(m.id); tests += m.name } // alarma de prueba
                s == DoseStatus.TAKEN || s == DoseStatus.SKIPPED -> already += m.name
                s == DoseStatus.NOT_TODAY -> {
                    val next = m.nextScheduled(now.toLocalDate().plusDays(1))
                    notYet += "${m.name} (hoy no toca; próxima ${next?.let { fmtDay(it) } ?: "sin fecha"})"
                }
                else -> notYet += "${m.name} (desde las ${fmt(m.startMin)})"
            }
        }
        val hhmm = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when {
            taken.isNotEmpty() -> ScanResult(true, "✔ Toma registrada: ${taken.joinToString()} a las $hhmm")
            tests.isNotEmpty() -> ScanResult(true, "Prueba superada: alarma detenida")
            notYet.isEmpty() -> ScanResult(false, "${already.joinToString()}: ya registrada hoy")
            else -> ScanResult(false, "Aún no: ${notYet.joinToString()}")
        }
    }

    fun markTaken(ctx: Context, med: Medication, late: Boolean = false) {
        val updated = med.copy(takenDate = LocalDate.now().toString(), takenAt = System.currentTimeMillis(), nextRepeatAt = 0L)
        MedRepo.upsert(ctx, updated)
        MedRepo.log(ctx, if (late) "Tomada con retraso: ${med.name}" else "Tomada: ${med.name}")
        finishDose(ctx, updated)
    }

    /** Medicamento a demanda: se registra cada vez que se escanea. */
    private fun markOnDemand(ctx: Context, med: Medication) {
        val today = LocalDate.now().toString()
        val updated = med.copy(
            takenDate = today,
            takenAt = System.currentTimeMillis(),
            countDate = today,
            count = med.todayCount() + 1,
        )
        MedRepo.upsert(ctx, updated)
        MedRepo.log(ctx, "Tomada (a demanda): ${med.name}")
        AlarmService.stop(med.id)
    }

    /** "Cancelar toma": se salta la toma de hoy, sin alarma, y queda en el historial. */
    fun skipToday(ctx: Context, med: Medication) {
        val updated = med.copy(skippedDate = LocalDate.now().toString(), nextRepeatAt = 0L)
        MedRepo.upsert(ctx, updated)
        MedRepo.log(ctx, "Toma cancelada: ${med.name}")
        finishDose(ctx, updated)
    }

    /** Desde la pantalla de alarma: si era una prueba solo se detiene; si no, se cancela la toma. */
    fun cancelFromAlarm(ctx: Context, med: Medication) {
        when (med.status()) {
            DoseStatus.DUE, DoseStatus.OVERDUE -> skipToday(ctx, med)
            else -> AlarmService.stop(med.id)
        }
    }

    /**
     * La alarma ha sonado el tiempo máximo sin respuesta: se para y, si quedan repeticiones,
     * se programa la siguiente; si no, la toma de hoy queda como "no tomada".
     */
    fun alarmTimedOut(ctx: Context, id: Int) {
        AlarmService.stop(id)
        val med = MedRepo.get(ctx, id) ?: return
        val s = med.status()
        if (s != DoseStatus.DUE && s != DoseStatus.OVERDUE) return
        val today = LocalDate.now().toString()
        val every = AlarmSettings.repeatEvery(ctx)
        val done = med.repeatsToday()
        val updated = if (every > 0 && done < AlarmSettings.repeatTimes(ctx)) {
            val next = System.currentTimeMillis() + every * 60_000L
            med.copy(repeatDate = today, repeatsDone = done + 1, nextRepeatAt = next).also {
                MedRepo.log(ctx, "Alarma sin respuesta: ${med.name} (volverá a sonar a las ${it.nextRepeatText()})")
                Notifications.showMissed(ctx, it, "Volverá a sonar a las ${it.nextRepeatText()}. Escanea la pegatina cuando la tomes.")
            }
        } else {
            med.copy(missedDate = today, nextRepeatAt = 0L).also {
                MedRepo.log(ctx, "Toma NO realizada (alarma sin respuesta): ${med.name}")
                Notifications.showMissed(ctx, it, "No se registró la toma de hoy. Si la tomas tarde, escanea la pegatina.")
            }
        }
        MedRepo.upsert(ctx, updated)
        AlarmScheduler.schedule(ctx, updated)
        // La pantalla de alarma aún está visible, así que se puede abrir el aviso directamente;
        // queda sobre la pantalla de bloqueo hasta que se vea.
        runCatching {
            ctx.startActivity(
                android.content.Intent(ctx, MissedActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun finishDose(ctx: Context, med: Medication) {
        AlarmService.stop(med.id)
        ctx.getSystemService(NotificationManager::class.java)?.apply {
            cancel(Notifications.reminderId(med.id))
            cancel(Notifications.missedId(med.id))
        }
        AlarmScheduler.schedule(ctx, med) // programa la siguiente
    }
}

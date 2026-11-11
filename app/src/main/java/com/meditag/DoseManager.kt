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
        val taken = mutableListOf<String>()
        val already = mutableListOf<String>()
        val notYet = mutableListOf<String>()

        for (m in meds) {
            if (m.mode == Mode.ON_DEMAND) {
                markOnDemand(ctx, m)
                taken += "${m.name} (${m.todayCount() + 1}ª de hoy)"
                continue
            }
            if (!m.isScheduledOn(now.toLocalDate())) {
                val next = m.nextScheduled(now.toLocalDate().plusDays(1))
                notYet += "${m.name} (hoy no toca; próxima ${next?.let { fmtDay(it) } ?: "sin fecha"})"
                continue
            }
            val st = m.slots.indices.map { m.slotStatus(it, now) }
            // Prioridad: la toma en curso; si no, la que empieza pronto; si no, una fuera de
            // plazo (para parar su alarma); si no, una que quedó sin tomar (con retraso).
            val i = st.indexOfFirst { it == DoseStatus.DUE }.takeIf { it >= 0 }
                ?: m.slots.indices.firstOrNull { st[it] == DoseStatus.PENDING && nowMin >= m.slots[it].start - EARLY_MARGIN_MIN }
                ?: st.indexOfFirst { it == DoseStatus.OVERDUE }.takeIf { it >= 0 }
            if (i != null) {
                markTaken(ctx, m, i); taken += m.name + m.slotSuffix(i)
                continue
            }
            val missed = st.indexOfLast { it == DoseStatus.MISSED }
            if (missed >= 0) {
                markTaken(ctx, m, missed, late = true); taken += "${m.name}${m.slotSuffix(missed)} con retraso"
                continue
            }
            val nextToday = m.slots.indices.firstOrNull { st[it] == DoseStatus.PENDING }
            when {
                nextToday != null -> notYet += "${m.name} (próxima toma a las ${fmt(m.slots[nextToday].start)})"
                else -> already += m.name
            }
        }
        val hhmmNow = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when {
            taken.isNotEmpty() -> ScanResult(true, "✔ Toma registrada: ${taken.joinToString()} a las $hhmmNow")
            notYet.isEmpty() -> ScanResult(false, "${already.joinToString()}: ya están registradas todas las tomas de hoy")
            else -> ScanResult(false, "Aún no: ${notYet.joinToString()}")
        }
    }

    fun markTaken(ctx: Context, med: Medication, i: Int, late: Boolean = false) {
        val nowMs = System.currentTimeMillis()
        val updated = med.withState(i) { it.copy(takenAt = nowMs, nextRepeatAt = 0L) }.copy(takenAt = nowMs)
        MedRepo.upsert(ctx, updated)
        MedRepo.log(ctx, (if (late) "Tomada con retraso: " else "Tomada: ") + med.name + med.slotSuffix(i))
        finishDose(ctx, updated, i)
    }

    /** Medicamento a demanda: se registra cada vez que se escanea. */
    private fun markOnDemand(ctx: Context, med: Medication) {
        val updated = med.copy(
            takenAt = System.currentTimeMillis(),
            countDate = LocalDate.now().toString(),
            count = med.todayCount() + 1,
        )
        MedRepo.upsert(ctx, updated)
        MedRepo.log(ctx, "Tomada (a demanda): ${med.name}")
    }

    /** "Cancelar toma": se salta la toma [i] de hoy, sin alarma, y queda en el historial. */
    fun skip(ctx: Context, med: Medication, i: Int) {
        val updated = med.withState(i) { it.copy(skipped = true, nextRepeatAt = 0L) }
        MedRepo.upsert(ctx, updated)
        MedRepo.log(ctx, "Toma cancelada: ${med.name}${med.slotSuffix(i)}")
        finishDose(ctx, updated, i)
    }

    /** Desde la pantalla de alarma: se cancela esa toma (si sigue pendiente) y se para. */
    fun cancelFromAlarm(ctx: Context, med: Medication, i: Int) {
        when (med.slotStatus(i)) {
            DoseStatus.DUE, DoseStatus.OVERDUE -> skip(ctx, med, i)
            else -> AlarmService.stop(Occ.key(med.id, i))
        }
    }

    /**
     * La alarma ha sonado el tiempo máximo sin respuesta: se para y, si quedan repeticiones
     * (y caben antes de medianoche), se programa la siguiente; si no, la toma queda "no tomada".
     */
    fun alarmTimedOut(ctx: Context, key: Int) {
        AlarmService.stop(key)
        val med = MedRepo.get(ctx, Occ.medId(key)) ?: return
        val i = Occ.slot(key)
        if (i !in med.slots.indices) return
        val s = med.slotStatus(i)
        if (s != DoseStatus.DUE && s != DoseStatus.OVERDUE) return
        val every = AlarmSettings.repeatEvery(ctx)
        val done = med.state(i).repeatsDone
        val next = System.currentTimeMillis() + every * 60_000L
        val sameDay = java.time.Instant.ofEpochMilli(next).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == LocalDate.now()
        val label = med.name + med.slotSuffix(i)
        val updated = if (every > 0 && done < AlarmSettings.repeatTimes(ctx) && sameDay) {
            med.withState(i) { it.copy(repeatsDone = done + 1, nextRepeatAt = next) }.also {
                MedRepo.log(ctx, "Alarma sin respuesta: $label (volverá a sonar a las ${hhmm(next)})")
                Notifications.showMissed(ctx, it, i, "Volverá a sonar a las ${hhmm(next)}. Escanea la pegatina cuando la tomes.")
            }
        } else {
            med.withState(i) { it.copy(missed = true, nextRepeatAt = 0L) }.also {
                MedRepo.log(ctx, "Toma NO realizada (alarma sin respuesta): $label")
                Notifications.showMissed(ctx, it, i, "No se registró esta toma. Si la tomas tarde, escanea la pegatina.")
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

    private fun finishDose(ctx: Context, med: Medication, i: Int) {
        AlarmService.stop(Occ.key(med.id, i))
        ctx.getSystemService(NotificationManager::class.java)?.apply {
            cancel(Notifications.reminderId(med.id, i))
            cancel(Notifications.missedId(med.id, i))
        }
        AlarmScheduler.schedule(ctx, med) // programa la siguiente
    }
}

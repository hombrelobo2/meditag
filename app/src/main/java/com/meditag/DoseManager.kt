package com.meditag

import android.app.NotificationManager
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Resultado de leer una pegatina. Si [measureItem] no es null, hay que abrir el formulario
 * de medición para ese elemento (y la toma [measureSlot], o -1 si es una medición extra).
 */
data class ScanResult(val ok: Boolean, val message: String, val measureItem: Int? = null, val measureSlot: Int = -1)

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
        val all = MedRepo.all(ctx).filter { it.enabled && it.tagId.equals(tagId, ignoreCase = true) }
        if (all.isEmpty()) return ScanResult(false, "Etiqueta no registrada ($tagId)")
        // Mediciones: no se registran al escanear, sino al guardar los valores en el formulario.
        all.firstOrNull { it.isMeasurement }?.let { m ->
            val pick = pickSlot(m, LocalDateTime.now())
            return ScanResult(true, "Introduce los valores de ${m.name}", measureItem = m.id, measureSlot = pick?.first ?: -1)
        }
        val meds = all

        val now = LocalDateTime.now()
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
            val pick = pickSlot(m, now)
            if (pick != null) {
                val (i, late) = pick
                markTaken(ctx, m, i, late)
                taken += m.name + m.slotSuffix(i) + if (late) " con retraso" else ""
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

    /**
     * Qué toma de hoy registra un escaneo (o una medición): la que está en curso; si no, la que
     * empieza en menos de 30 min; si no, una fuera de plazo (para parar su alarma); si no, una
     * que quedó sin hacer (con retraso). Devuelve (toma, conRetraso) o null.
     */
    fun pickSlot(m: Medication, now: LocalDateTime): Pair<Int, Boolean>? {
        if (m.mode == Mode.ON_DEMAND || !m.enabled || !m.isScheduledOn(now.toLocalDate())) return null
        val nowMin = now.hour * 60 + now.minute
        val st = m.slots.indices.map { m.slotStatus(it, now) }
        val i = st.indexOfFirst { it == DoseStatus.DUE }.takeIf { it >= 0 }
            ?: m.slots.indices.firstOrNull { st[it] == DoseStatus.PENDING && nowMin >= m.slots[it].start - EARLY_MARGIN_MIN }
            ?: st.indexOfFirst { it == DoseStatus.OVERDUE }.takeIf { it >= 0 }
        if (i != null) return i to false
        val missed = st.indexOfLast { it == DoseStatus.MISSED }
        return if (missed >= 0) missed to true else null
    }

    fun markTaken(ctx: Context, med: Medication, i: Int, late: Boolean = false, detail: String = "") {
        val nowMs = System.currentTimeMillis()
        val updated = med.withState(i) { it.copy(takenAt = nowMs, nextRepeatAt = 0L) }.copy(takenAt = nowMs)
        MedRepo.upsert(ctx, updated)
        MedRepo.logItem(ctx, med.kind.doneWord + if (late) " con retraso" else "", med, i, detail)
        finishDose(ctx, updated, i)
    }

    /**
     * Guarda una medición. Si [slot] es una toma de hoy aún abierta (o no realizada), cuenta
     * como hecha; si no, se guarda como medición extra.
     */
    fun saveMeasurement(ctx: Context, med: Medication, slot: Int, r: Reading) {
        val fresh = MedRepo.get(ctx, med.id) ?: med
        val st = if (slot in fresh.slots.indices) fresh.slotStatus(slot) else null
        val label = if (slot in fresh.slots.indices && fresh.multi) fresh.slots[slot].label else ""
        MedRepo.addReading(ctx, r.copy(itemId = fresh.id, itemName = fresh.name, slot = label))
        when (st) {
            DoseStatus.DUE, DoseStatus.OVERDUE, DoseStatus.PENDING ->
                markTaken(ctx, fresh, slot, late = false, detail = r.summary())
            DoseStatus.MISSED -> markTaken(ctx, fresh, slot, late = true, detail = r.summary())
            else -> {
                val updated = if (fresh.mode == Mode.ON_DEMAND)
                    fresh.copy(takenAt = r.ts, countDate = LocalDate.now().toString(), count = fresh.todayCount() + 1)
                else fresh.copy(takenAt = r.ts)
                MedRepo.upsert(ctx, updated)
                MedRepo.logItem(ctx, "Medición registrada", fresh, null, r.summary())
            }
        }
    }

    /** Medicamento a demanda: se registra cada vez que se escanea. */
    private fun markOnDemand(ctx: Context, med: Medication) {
        val updated = med.copy(
            takenAt = System.currentTimeMillis(),
            countDate = LocalDate.now().toString(),
            count = med.todayCount() + 1,
        )
        MedRepo.upsert(ctx, updated)
        MedRepo.logItem(ctx, med.kind.doneWord + " (a demanda)", med)
    }

    /** "Cancelar toma": se salta la toma [i] de hoy, sin alarma, y queda en el historial. */
    fun skip(ctx: Context, med: Medication, i: Int) {
        val updated = med.withState(i) { it.copy(skipped = true, nextRepeatAt = 0L) }
        MedRepo.upsert(ctx, updated)
        MedRepo.logItem(ctx, "Cancelada", med, i)
        finishDose(ctx, updated, i)
    }

    /**
     * Deshace la cancelación de hoy de la toma [i]: se borra del historial y la toma vuelve a
     * estar pendiente para poder registrarla escaneando. Si su franja ya pasó, queda como
     * "no realizada" (al escanear cuenta como "con retraso") y la alarma no suena de golpe.
     */
    fun uncancel(ctx: Context, med: Medication, i: Int) {
        val fresh = MedRepo.get(ctx, med.id) ?: return
        if (i !in fresh.slots.indices || !fresh.state(i).skipped) return
        val past = !LocalDateTime.now().toLocalTime().isBefore(fresh.slots[i].endTime)
        val updated = fresh.withState(i) { it.copy(skipped = false, missed = past, nextRepeatAt = 0L) }
        MedRepo.upsert(ctx, updated)
        MedRepo.removeCancellation(ctx, fresh, i)
        AlarmScheduler.schedule(ctx, updated)
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
        val updated = if (every > 0 && done < AlarmSettings.repeatTimes(ctx) && sameDay) {
            med.withState(i) { it.copy(repeatsDone = done + 1, nextRepeatAt = next) }.also {
                MedRepo.logItem(ctx, "Alarma sin respuesta", med, i, "volverá a sonar a las ${hhmm(next)}")
                Notifications.showMissed(ctx, it, i, "Volverá a sonar a las ${hhmm(next)}. Escanea la pegatina cuando la tomes.")
            }
        } else {
            med.withState(i) { it.copy(missed = true, nextRepeatAt = 0L) }.also {
                MedRepo.logItem(ctx, "NO realizada (alarma sin respuesta)", med, i)
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

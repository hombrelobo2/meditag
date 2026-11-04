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
                s == DoseStatus.DUE || s == DoseStatus.OVERDUE ||
                    (s == DoseStatus.PENDING && nowMin >= m.startMin - EARLY_MARGIN_MIN) -> {
                    markTaken(ctx, m); taken += m.name
                }
                m.id in ringing -> { AlarmService.stop(m.id); tests += m.name } // alarma de prueba
                s == DoseStatus.TAKEN || s == DoseStatus.SKIPPED -> already += m.name
                else -> notYet += "${m.name} (desde ${fmt(m.startMin)})"
            }
        }
        val hhmm = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        return when {
            taken.isNotEmpty() -> ScanResult(true, "✔ Toma registrada: ${taken.joinToString()} a las $hhmm")
            tests.isNotEmpty() -> ScanResult(true, "Prueba superada: alarma detenida")
            notYet.isEmpty() -> ScanResult(false, "${already.joinToString()}: ya registrada hoy")
            else -> ScanResult(false, "Aún no es la hora: ${notYet.joinToString()}")
        }
    }

    fun markTaken(ctx: Context, med: Medication) {
        val updated = med.copy(takenDate = LocalDate.now().toString(), takenAt = System.currentTimeMillis())
        MedRepo.upsert(ctx, updated)
        MedRepo.log(ctx, "Tomada: ${med.name}")
        finishDose(ctx, updated)
    }

    /** "Cancelar toma": se salta la toma de hoy, sin alarma, y queda en el historial. */
    fun skipToday(ctx: Context, med: Medication) {
        val updated = med.copy(skippedDate = LocalDate.now().toString())
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

    private fun finishDose(ctx: Context, med: Medication) {
        AlarmService.stop(med.id)
        ctx.getSystemService(NotificationManager::class.java)
            ?.cancel(Notifications.reminderId(med.id))
        AlarmScheduler.schedule(ctx, med) // programa la de mañana
    }
}

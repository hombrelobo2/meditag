package com.meditag

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class DoseStatus { PENDING, DUE, OVERDUE, TAKEN, SKIPPED, DISABLED }

/**
 * Un medicamento con su etiqueta NFC y su ventana horaria diaria.
 * startMin / endMin = minutos desde las 00:00 (p.ej. 8:30 -> 510).
 */
data class Medication(
    val id: Int,
    val name: String,
    val tagId: String,
    val startMin: Int,
    val endMin: Int,
    val enabled: Boolean = true,
    val takenDate: String? = null,   // yyyy-MM-dd del último día tomado
    val takenAt: Long = 0L,          // epoch millis de la última toma
    val skippedDate: String? = null, // yyyy-MM-dd del último día cancelado
) {
    val start: LocalTime get() = LocalTime.of(startMin / 60, startMin % 60)
    val end: LocalTime get() = LocalTime.of(endMin / 60, endMin % 60)

    fun isDoneOn(date: LocalDate): Boolean =
        takenDate == date.toString() || skippedDate == date.toString()

    fun status(now: LocalDateTime = LocalDateTime.now()): DoseStatus {
        val today = now.toLocalDate().toString()
        val t = now.toLocalTime()
        return when {
            !enabled -> DoseStatus.DISABLED
            takenDate == today -> DoseStatus.TAKEN
            skippedDate == today -> DoseStatus.SKIPPED
            t < start -> DoseStatus.PENDING
            t < end -> DoseStatus.DUE
            else -> DoseStatus.OVERDUE
        }
    }

    fun takenTimeText(): String =
        Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()).toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("tagId", tagId)
        .put("startMin", startMin).put("endMin", endMin).put("enabled", enabled)
        .put("takenDate", takenDate ?: "").put("takenAt", takenAt)
        .put("skippedDate", skippedDate ?: "")

    companion object {
        fun fromJson(o: JSONObject) = Medication(
            id = o.getInt("id"),
            name = o.getString("name"),
            tagId = o.getString("tagId"),
            startMin = o.getInt("startMin"),
            endMin = o.getInt("endMin"),
            enabled = o.optBoolean("enabled", true),
            takenDate = o.optString("takenDate").ifBlank { null },
            takenAt = o.optLong("takenAt", 0L),
            skippedDate = o.optString("skippedDate").ifBlank { null },
        )
    }
}

fun fmt(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

/** Almacenamiento simple en SharedPreferences (JSON) con estado observable. */
object MedRepo {
    private const val PREFS = "meditag"
    private const val KEY_MEDS = "meds"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 150

    private val _meds = MutableStateFlow<List<Medication>>(emptyList())
    val meds: StateFlow<List<Medication>> = _meds.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    @Volatile private var loaded = false

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(ctx)
            val arr = JSONArray(p.getString(KEY_MEDS, "[]"))
            _meds.value = (0 until arr.length()).map { Medication.fromJson(arr.getJSONObject(it)) }
            val h = JSONArray(p.getString(KEY_HISTORY, "[]"))
            _history.value = (0 until h.length()).map { h.getString(it) }
            loaded = true
        }
    }

    fun all(ctx: Context): List<Medication> { init(ctx); return _meds.value }

    fun get(ctx: Context, id: Int): Medication? = all(ctx).find { it.id == id }

    fun nextId(ctx: Context): Int = (all(ctx).maxOfOrNull { it.id } ?: 0) + 1

    @Synchronized
    fun upsert(ctx: Context, med: Medication) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == med.id }
        if (i >= 0) list[i] = med else list += med
        _meds.value = list.sortedBy { it.startMin }
        persist(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, id: Int) {
        _meds.value = all(ctx).filterNot { it.id == id }
        persist(ctx)
    }

    @Synchronized
    fun log(ctx: Context, text: String) {
        init(ctx)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))
        _history.value = (listOf("$stamp · $text") + _history.value).take(MAX_HISTORY)
        prefs(ctx).edit().putString(KEY_HISTORY, JSONArray(_history.value).toString()).apply()
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        _meds.value.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_MEDS, arr.toString()).apply()
    }
}

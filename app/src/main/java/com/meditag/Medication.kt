package com.meditag

import android.content.Context
import android.net.Uri
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
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class DoseStatus { PENDING, DUE, OVERDUE, TAKEN, SKIPPED, MISSED, NOT_TODAY, ON_DEMAND, DISABLED }

/** Pauta de toma. */
enum class Mode { DAILY, WEEKDAYS, INTERVAL, DATES, ON_DEMAND }

val ES: Locale = Locale.forLanguageTag("es-ES")
val WEEKDAY_LETTERS = listOf("L", "M", "X", "J", "V", "S", "D")

/**
 * Un medicamento con su etiqueta NFC, su pauta y su ventana horaria.
 * startMin / endMin = minutos desde las 00:00 (p.ej. 8:30 -> 510).
 */
data class Medication(
    val id: Int,
    val name: String,
    val tagId: String,
    val startMin: Int,
    val endMin: Int,
    val enabled: Boolean = true,
    val mode: Mode = Mode.DAILY,
    val weekdays: Int = 0b1111111,          // bit 0 = lunes … bit 6 = domingo
    val intervalDays: Int = 2,              // para Mode.INTERVAL
    val anchorDate: String = LocalDate.now().toString(), // primer día de la serie "cada N días"
    val dates: Set<String> = emptySet(),    // para Mode.DATES (yyyy-MM-dd)
    val activeFrom: String? = null,         // no hay tomas antes de este día
    val takenDate: String? = null,          // yyyy-MM-dd del último día tomado
    val takenAt: Long = 0L,                 // epoch millis de la última toma
    val skippedDate: String? = null,        // yyyy-MM-dd del último día cancelado
    val countDate: String? = null,          // a demanda: día del contador
    val count: Int = 0,                     // a demanda: tomas ese día
    val missedDate: String? = null,         // día en que la alarma acabó sin respuesta
    val repeatDate: String? = null,         // día al que se refieren las repeticiones
    val repeatsDone: Int = 0,               // repeticiones de alarma ya hechas ese día
    val nextRepeatAt: Long = 0L,            // epoch millis de la próxima repetición
) {
    val start: LocalTime get() = LocalTime.of(startMin / 60, startMin % 60)
    val end: LocalTime get() = LocalTime.of(endMin / 60, endMin % 60)

    fun isScheduledOn(d: LocalDate): Boolean {
        activeFrom?.let { if (d < LocalDate.parse(it)) return false }
        return when (mode) {
            Mode.DAILY -> true
            Mode.WEEKDAYS -> weekdays and (1 shl (d.dayOfWeek.value - 1)) != 0
            Mode.INTERVAL -> {
                val diff = ChronoUnit.DAYS.between(LocalDate.parse(anchorDate), d)
                diff >= 0 && diff % intervalDays.coerceAtLeast(1) == 0L
            }
            Mode.DATES -> d.toString() in dates
            Mode.ON_DEMAND -> false
        }
    }

    /** Primer día con toma a partir de [from] (incluido), o null si no hay ninguno en ~1 año. */
    fun nextScheduled(from: LocalDate): LocalDate? =
        generateSequence(from) { it.plusDays(1) }.take(400).firstOrNull { isScheduledOn(it) }

    fun isDoneOn(date: LocalDate): Boolean =
        takenDate == date.toString() || skippedDate == date.toString() || missedDate == date.toString()

    /** Hay una repetición de alarma programada para más tarde hoy. */
    fun repeatPending(now: Long = System.currentTimeMillis()): Boolean =
        repeatDate == LocalDate.now().toString() && nextRepeatAt > now

    fun repeatsToday(): Int = if (repeatDate == LocalDate.now().toString()) repeatsDone else 0

    fun nextRepeatText(): String =
        Instant.ofEpochMilli(nextRepeatAt).atZone(ZoneId.systemDefault()).toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))

    fun status(now: LocalDateTime = LocalDateTime.now()): DoseStatus {
        val todayDate = now.toLocalDate()
        val today = todayDate.toString()
        val t = now.toLocalTime()
        return when {
            !enabled -> DoseStatus.DISABLED
            mode == Mode.ON_DEMAND -> DoseStatus.ON_DEMAND
            takenDate == today -> DoseStatus.TAKEN
            skippedDate == today -> DoseStatus.SKIPPED
            missedDate == today -> DoseStatus.MISSED
            !isScheduledOn(todayDate) -> DoseStatus.NOT_TODAY
            t < start -> DoseStatus.PENDING
            t < end -> DoseStatus.DUE
            else -> DoseStatus.OVERDUE
        }
    }

    fun todayCount(): Int = if (countDate == LocalDate.now().toString()) count else 0

    fun takenTimeText(): String =
        Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault()).toLocalTime()
            .format(DateTimeFormatter.ofPattern("HH:mm"))

    fun takenDateTimeText(): String =
        Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))

    /** Texto corto de la pauta, p.ej. "L, X, V · 08:00–10:00". */
    fun describe(): String {
        val h = "${fmt(startMin)}–${fmt(endMin)}"
        return when (mode) {
            Mode.DAILY -> "Cada día · $h"
            Mode.WEEKDAYS ->
                if (weekdays == 0b1111111) "Cada día · $h"
                else WEEKDAY_LETTERS.filterIndexed { i, _ -> weekdays and (1 shl i) != 0 }.joinToString(", ") + " · $h"
            Mode.INTERVAL -> (if (intervalDays == 2) "Días alternos" else "Cada $intervalDays días") + " · $h"
            Mode.DATES -> "Según calendario (${dates.size} ${if (dates.size == 1) "día" else "días"}) · $h"
            Mode.ON_DEMAND -> "A demanda · sin alarma"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("tagId", tagId)
        .put("startMin", startMin).put("endMin", endMin).put("enabled", enabled)
        .put("mode", mode.name).put("weekdays", weekdays).put("intervalDays", intervalDays)
        .put("anchorDate", anchorDate).put("dates", JSONArray(dates.sorted()))
        .put("activeFrom", activeFrom ?: "")
        .put("takenDate", takenDate ?: "").put("takenAt", takenAt)
        .put("skippedDate", skippedDate ?: "")
        .put("countDate", countDate ?: "").put("count", count)
        .put("missedDate", missedDate ?: "").put("repeatDate", repeatDate ?: "")
        .put("repeatsDone", repeatsDone).put("nextRepeatAt", nextRepeatAt)

    companion object {
        fun fromJson(o: JSONObject): Medication {
            val d = o.optJSONArray("dates") ?: JSONArray()
            return Medication(
                id = o.getInt("id"),
                name = o.getString("name"),
                tagId = o.getString("tagId"),
                startMin = o.getInt("startMin"),
                endMin = o.getInt("endMin"),
                enabled = o.optBoolean("enabled", true),
                mode = runCatching { Mode.valueOf(o.optString("mode", "DAILY")) }.getOrDefault(Mode.DAILY),
                weekdays = o.optInt("weekdays", 0b1111111),
                intervalDays = o.optInt("intervalDays", 2),
                anchorDate = o.optString("anchorDate").ifBlank { LocalDate.now().toString() },
                dates = (0 until d.length()).map { d.getString(it) }.toSet(),
                activeFrom = o.optString("activeFrom").ifBlank { null },
                takenDate = o.optString("takenDate").ifBlank { null },
                takenAt = o.optLong("takenAt", 0L),
                skippedDate = o.optString("skippedDate").ifBlank { null },
                countDate = o.optString("countDate").ifBlank { null },
                count = o.optInt("count", 0),
                missedDate = o.optString("missedDate").ifBlank { null },
                repeatDate = o.optString("repeatDate").ifBlank { null },
                repeatsDone = o.optInt("repeatsDone", 0),
                nextRepeatAt = o.optLong("nextRepeatAt", 0L),
            )
        }
    }
}

fun fmt(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

fun fmtDay(d: LocalDate): String {
    val today = LocalDate.now()
    return when (d) {
        today -> "hoy"
        today.plusDays(1) -> "mañana"
        else -> d.format(DateTimeFormatter.ofPattern("EEE d MMM", ES))
    }
}

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

    fun prefs(ctx: Context) =
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
        _meds.value = list.sortedWith(compareBy({ it.mode == Mode.ON_DEMAND }, { it.startMin }))
        persist(ctx)
    }

    @Synchronized
    fun replaceAll(ctx: Context, meds: List<Medication>, history: List<String>) {
        init(ctx)
        _meds.value = meds.sortedWith(compareBy({ it.mode == Mode.ON_DEMAND }, { it.startMin }))
        _history.value = history.take(MAX_HISTORY)
        persist(ctx)
        prefs(ctx).edit().putString(KEY_HISTORY, JSONArray(_history.value).toString()).apply()
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

/** Sonido de alarma elegido por el usuario (null = pitido estridente por defecto). */
object AlarmSound {
    private const val KEY_URI = "sound_uri"
    private const val KEY_NAME = "sound_name"
    const val DEFAULT_NAME = "Pitido estridente (por defecto)"

    fun uri(ctx: Context): Uri? =
        MedRepo.prefs(ctx).getString(KEY_URI, null)?.takeIf { it.isNotBlank() }?.let(Uri::parse)

    fun name(ctx: Context): String =
        if (uri(ctx) == null) DEFAULT_NAME else MedRepo.prefs(ctx).getString(KEY_NAME, null) ?: "Sonido personalizado"

    fun set(ctx: Context, uri: Uri?, name: String?) {
        MedRepo.prefs(ctx).edit()
            .putString(KEY_URI, uri?.toString() ?: "")
            .putString(KEY_NAME, name ?: "")
            .apply()
    }
}

/** Duración y repeticiones de la alarma (minutos; 0 = sin límite / sin repetir). */
object AlarmSettings {
    private const val KEY_MAX = "alarm_max_min"
    private const val KEY_EVERY = "alarm_repeat_every"
    private const val KEY_TIMES = "alarm_repeat_times"

    fun maxMinutes(ctx: Context) = MedRepo.prefs(ctx).getInt(KEY_MAX, 30)
    fun repeatEvery(ctx: Context) = MedRepo.prefs(ctx).getInt(KEY_EVERY, 30)
    fun repeatTimes(ctx: Context) = MedRepo.prefs(ctx).getInt(KEY_TIMES, 2)

    fun set(ctx: Context, maxMin: Int, every: Int, times: Int) {
        MedRepo.prefs(ctx).edit()
            .putInt(KEY_MAX, maxMin.coerceIn(0, 1440))
            .putInt(KEY_EVERY, every.coerceIn(0, 1440))
            .putInt(KEY_TIMES, times.coerceIn(0, 50))
            .apply()
    }

    fun describe(ctx: Context): String {
        val max = maxMinutes(ctx)
        if (max == 0) return "Suena hasta que escanees la pegatina o canceles la toma"
        val every = repeatEvery(ctx); val times = repeatTimes(ctx)
        val rep = if (every == 0 || times == 0) "y no se repite"
        else "y vuelve a sonar cada $every min ($times ${if (times == 1) "vez" else "veces"} más)"
        return "Suena $max min $rep"
    }
}

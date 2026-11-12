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

enum class DoseStatus { PENDING, DUE, OVERDUE, TAKEN, SKIPPED, MISSED, INACTIVE, NOT_TODAY, ON_DEMAND, DISABLED }

/** Pauta de toma. */
enum class Mode { DAILY, WEEKDAYS, INTERVAL, DATES, ON_DEMAND }

/** Tipo de elemento. Cambia textos, iconos y cómo se exporta. */
enum class Kind(
    val label: String,
    val icon: String,
    val alarmTitle: String,
    val missedTitle: String,
    val doneWord: String,     // en el historial: "Tomada", "Hecho"…
    val doneState: String,    // en la tarjeta: "✔ Tomada hoy a las…"
) {
    MEDICATION("Medicamento", "💊", "¡TOMA TU MEDICACIÓN!", "No te has tomado la medicación", "Tomada", "Tomada"),
    SUPPLEMENT("Complemento", "🌿", "¡TOMA TU COMPLEMENTO!", "No te has tomado el complemento", "Tomado", "Tomado"),
    MEASUREMENT("Medición", "🩺", "¡HORA DE MEDIRTE!", "No has hecho la medición", "Medición registrada", "Medido"),
    HABIT("Hábito", "✅", "¡NO LO OLVIDES!", "Tienes algo pendiente", "Hecho", "Hecho");

    /** Texto del recordatorio, p.ej. "Hora de tomar Ibuprofeno". */
    fun reminder(name: String) = when (this) {
        MEDICATION, SUPPLEMENT -> "Hora de tomar $name"
        MEASUREMENT -> "Hora de medir: $name"
        HABIT -> "Hora de: $name"
    }
}

/** Qué se mide en un elemento de tipo Medición. */
enum class Measure(val label: String) {
    BP("Tensión y pulso"),
    SPO2("Saturación de oxígeno"),
    GLUCOSE("Glucemia"),
    WEIGHT("Peso"),
    TEMP("Temperatura"),
}

/** Momento de la glucemia. */
val GLUCOSE_CONTEXTS = listOf("En ayunas", "Antes de comer", "Después de comer", "Otro momento")

val ES: Locale = Locale.forLanguageTag("es-ES")
val WEEKDAY_LETTERS = listOf("L", "M", "X", "J", "V", "S", "D")

/** Máximo de tomas (franjas) por medicamento. */
const val MAX_SLOTS = 8

/** Una franja horaria de toma. start / end = minutos desde las 00:00 (8:30 -> 510). */
data class Slot(val start: Int, val end: Int) {
    val label: String get() = "${fmt(start)}–${fmt(end)}"
    val startTime: LocalTime get() = LocalTime.of(start / 60, start % 60)
    val endTime: LocalTime get() = LocalTime.of(end / 60, end % 60)
}

/** Estado de una toma concreta en el día [Medication.stateDate]. */
data class SlotState(
    val takenAt: Long = 0L,      // epoch millis si se tomó
    val skipped: Boolean = false, // cancelada
    val missed: Boolean = false,  // la alarma acabó sin respuesta
    val repeatsDone: Int = 0,     // repeticiones de alarma ya hechas
    val nextRepeatAt: Long = 0L,  // próxima repetición programada
) {
    val done: Boolean get() = takenAt > 0 || skipped || missed

    fun toJson(): JSONObject = JSONObject().put("takenAt", takenAt).put("skipped", skipped)
        .put("missed", missed).put("repeatsDone", repeatsDone).put("nextRepeatAt", nextRepeatAt)

    companion object {
        fun fromJson(o: JSONObject) = SlotState(
            o.optLong("takenAt"), o.optBoolean("skipped"), o.optBoolean("missed"),
            o.optInt("repeatsDone"), o.optLong("nextRepeatAt"),
        )
    }
}

/** Clave compacta (medicamento, toma) para alarmas y notificaciones. */
object Occ {
    fun key(medId: Int, slot: Int) = medId * 16 + slot
    fun medId(key: Int) = key / 16
    fun slot(key: Int) = key % 16
}

private fun millisOf(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun hhmm(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
        .format(DateTimeFormatter.ofPattern("HH:mm"))

/**
 * Un medicamento con su etiqueta NFC, su pauta (qué días) y una o varias tomas al día.
 * El estado de cada toma se guarda solo para el día [stateDate] (hoy).
 */
data class Medication(
    val id: Int,
    val name: String,
    val tagId: String,
    val slots: List<Slot>,
    val enabled: Boolean = true,
    val mode: Mode = Mode.DAILY,
    val weekdays: Int = 0b1111111,          // bit 0 = lunes … bit 6 = domingo
    val intervalDays: Int = 2,              // para Mode.INTERVAL
    val anchorDate: String = LocalDate.now().toString(), // primer día de la serie "cada N días"
    val dates: Set<String> = emptySet(),    // para Mode.DATES (yyyy-MM-dd)
    val activeSince: Long = 0L,             // las tomas que acaban antes de esto no cuentan
    val stateDate: String? = null,          // día al que se refieren [states]
    val states: List<SlotState> = emptyList(),
    val takenAt: Long = 0L,                 // última toma (cualquiera)
    val countDate: String? = null,          // a demanda: día del contador
    val count: Int = 0,                     // a demanda: tomas ese día
    val kind: Kind = Kind.MEDICATION,
    val measures: Set<Measure> = emptySet(), // solo para Kind.MEASUREMENT
    val pinned: Boolean = false,             // anclado: sale siempre arriba
) {
    val isMeasurement: Boolean get() = kind == Kind.MEASUREMENT

    /** Para ordenar la lista. */
    val startMin: Int get() = slots.minOfOrNull { it.start } ?: 0
    val multi: Boolean get() = slots.size > 1

    /** Texto que identifica una toma en mensajes ("" si solo hay una). */
    fun slotSuffix(i: Int): String = if (multi && i in slots.indices) " (${slots[i].label})" else ""

    fun isScheduledOn(d: LocalDate): Boolean = when (mode) {
        Mode.DAILY -> true
        Mode.WEEKDAYS -> weekdays and (1 shl (d.dayOfWeek.value - 1)) != 0
        Mode.INTERVAL -> {
            val diff = ChronoUnit.DAYS.between(LocalDate.parse(anchorDate), d)
            diff >= 0 && diff % intervalDays.coerceAtLeast(1) == 0L
        }
        Mode.DATES -> d.toString() in dates
        Mode.ON_DEMAND -> false
    }

    /** La toma [i] del día [d] cuenta (hay pauta ese día y no acabó antes de crearla/editarla). */
    fun counts(d: LocalDate, i: Int): Boolean =
        isScheduledOn(d) && millisOf(d.atTime(slots[i].endTime)) > activeSince

    /** Primer día con toma a partir de [from] (incluido), o null si no hay ninguno en ~1 año. */
    fun nextScheduled(from: LocalDate): LocalDate? =
        generateSequence(from) { it.plusDays(1) }.take(400).firstOrNull { isScheduledOn(it) }

    /** Estado de las tomas de hoy (vacío si [stateDate] es otro día). */
    fun todayStates(): List<SlotState> {
        val fresh = stateDate == LocalDate.now().toString()
        return List(slots.size) { i -> if (fresh) states.getOrNull(i) ?: SlotState() else SlotState() }
    }

    fun state(i: Int): SlotState = todayStates().getOrElse(i) { SlotState() }

    /** Copia con el estado de hoy de la toma [i] cambiado. */
    fun withState(i: Int, f: (SlotState) -> SlotState): Medication {
        val list = todayStates().toMutableList()
        list[i] = f(list[i])
        return copy(stateDate = LocalDate.now().toString(), states = list)
    }

    fun repeatPending(i: Int, now: Long = System.currentTimeMillis()): Boolean = state(i).nextRepeatAt > now

    fun slotStatus(i: Int, now: LocalDateTime = LocalDateTime.now()): DoseStatus {
        val today = now.toLocalDate()
        val st = state(i)
        val t = now.toLocalTime()
        return when {
            !enabled -> DoseStatus.DISABLED
            mode == Mode.ON_DEMAND -> DoseStatus.ON_DEMAND
            !isScheduledOn(today) -> DoseStatus.NOT_TODAY
            st.takenAt > 0 -> DoseStatus.TAKEN
            st.skipped -> DoseStatus.SKIPPED
            st.missed -> DoseStatus.MISSED
            !counts(today, i) -> DoseStatus.INACTIVE
            t < slots[i].startTime -> DoseStatus.PENDING
            t < slots[i].endTime -> DoseStatus.DUE
            else -> DoseStatus.OVERDUE
        }
    }

    /** Índice de la primera toma de hoy aún por hacer (pendiente, en curso o fuera de plazo). */
    fun currentOpenSlot(now: LocalDateTime = LocalDateTime.now()): Int? =
        slots.indices.firstOrNull {
            slotStatus(it, now).let { s -> s == DoseStatus.DUE || s == DoseStatus.OVERDUE || s == DoseStatus.PENDING }
        }

    fun todayCount(): Int = if (countDate == LocalDate.now().toString()) count else 0

    fun takenTimeText(): String = hhmm(takenAt)

    fun takenDateTimeText(): String =
        Instant.ofEpochMilli(takenAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM HH:mm"))

    /** Texto corto de la pauta, p.ej. "L, X, V · 08:00–10:00, 21:00–23:00". */
    fun describe(): String {
        val h = slots.joinToString(", ") { it.label }
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
        .put("slots", JSONArray().apply { slots.forEach { put(JSONObject().put("start", it.start).put("end", it.end)) } })
        .put("enabled", enabled)
        .put("mode", mode.name).put("weekdays", weekdays).put("intervalDays", intervalDays)
        .put("anchorDate", anchorDate).put("dates", JSONArray(dates.sorted()))
        .put("activeSince", activeSince)
        .put("stateDate", stateDate ?: "")
        .put("states", JSONArray().apply { states.forEach { put(it.toJson()) } })
        .put("takenAt", takenAt)
        .put("countDate", countDate ?: "").put("count", count)
        .put("kind", kind.name)
        .put("measures", JSONArray(measures.map { it.name }))
        .put("pinned", pinned)

    companion object {
        fun fromJson(o: JSONObject): Medication {
            val d = o.optJSONArray("dates") ?: JSONArray()
            val today = LocalDate.now().toString()
            val sa = o.optJSONArray("slots")
            val slots: List<Slot>
            var stateDate: String? = o.optString("stateDate").ifBlank { null }
            var states: List<SlotState>
            var activeSince = o.optLong("activeSince", 0L)
            if (sa != null && sa.length() > 0) {
                slots = (0 until sa.length()).map { sa.getJSONObject(it).let { j -> Slot(j.getInt("start"), j.getInt("end")) } }
                val st = o.optJSONArray("states") ?: JSONArray()
                states = (0 until st.length()).map { SlotState.fromJson(st.getJSONObject(it)) }
            } else {
                // Formato anterior a la 1.8: una sola franja y estado "de hoy" en campos sueltos.
                slots = listOf(Slot(o.optInt("startMin", 8 * 60), o.optInt("endMin", 10 * 60)))
                fun isToday(k: String) = o.optString(k) == today
                states = listOf(
                    SlotState(
                        takenAt = if (isToday("takenDate")) o.optLong("takenAt", 0L) else 0L,
                        skipped = isToday("skippedDate"),
                        missed = isToday("missedDate"),
                        repeatsDone = if (isToday("repeatDate")) o.optInt("repeatsDone", 0) else 0,
                        nextRepeatAt = if (isToday("repeatDate")) o.optLong("nextRepeatAt", 0L) else 0L,
                    )
                )
                stateDate = today
                o.optString("activeFrom").ifBlank { null }?.let {
                    activeSince = runCatching {
                        LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                }
            }
            return Medication(
                id = o.getInt("id"),
                name = o.getString("name"),
                tagId = o.getString("tagId"),
                slots = slots,
                enabled = o.optBoolean("enabled", true),
                mode = runCatching { Mode.valueOf(o.optString("mode", "DAILY")) }.getOrDefault(Mode.DAILY),
                weekdays = o.optInt("weekdays", 0b1111111),
                intervalDays = o.optInt("intervalDays", 2),
                anchorDate = o.optString("anchorDate").ifBlank { today },
                dates = (0 until d.length()).map { d.getString(it) }.toSet(),
                activeSince = activeSince,
                stateDate = stateDate,
                states = states,
                takenAt = o.optLong("takenAt", 0L),
                countDate = o.optString("countDate").ifBlank { null },
                count = o.optInt("count", 0),
                kind = runCatching { Kind.valueOf(o.optString("kind", "MEDICATION")) }.getOrDefault(Kind.MEDICATION),
                pinned = o.optBoolean("pinned", false),
                measures = (o.optJSONArray("measures") ?: JSONArray()).let { m ->
                    (0 until m.length()).mapNotNull { runCatching { Measure.valueOf(m.getString(it)) }.getOrNull() }.toSet()
                },
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
    private const val KEY_HISTORY = "history" // historial antiguo (antes de la 1.9), se migra a la base de datos
    /** El historial de eventos guarda un año. Las mediciones se guardan siempre. */
    private const val KEEP_DAYS = 365L
    private val OLD_STAMP = Regex("""^(\d{2})/(\d{2}) (\d{2}:\d{2}) · """)
    private val SLOT_RX = Regex("""^\d{2}:\d{2}–\d{2}:\d{2}$""")

    private val _meds = MutableStateFlow<List<Medication>>(emptyList())
    val meds: StateFlow<List<Medication>> = _meds.asStateFlow()

    /** Historial del último año, como texto, del más reciente al más antiguo. */
    private val _history = MutableStateFlow<List<String>>(emptyList())
    val history: StateFlow<List<String>> = _history.asStateFlow()

    /** Cambia cada vez que se guarda una medición (para refrescar la pantalla). */
    private val _readingsVersion = MutableStateFlow(0)
    val readingsVersion: StateFlow<Int> = _readingsVersion.asStateFlow()

    @Volatile private var loaded = false

    fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun yearAgo() = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L

    fun init(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(ctx)
            val arr = JSONArray(p.getString(KEY_MEDS, "[]"))
            _meds.value = sorted((0 until arr.length()).map { Medication.fromJson(arr.getJSONObject(it)) })
            // Historial antiguo en texto -> base de datos (una sola vez)
            val old = p.getString(KEY_HISTORY, null)
            if (old != null) {
                val h = JSONArray(old)
                Store.addEvents(ctx, (0 until h.length()).mapNotNull { parseLegacy(h.getString(it), _meds.value) })
                p.edit().remove(KEY_HISTORY).apply()
            }
            Store.pruneEvents(ctx, yearAgo())
            refreshHistory(ctx)
            loaded = true
        }
    }

    private fun refreshHistory(ctx: Context) {
        _history.value = Store.events(ctx, yearAgo()).map { it.display() }
    }

    /**
     * Convierte una línea del historial antiguo ("dd/MM/yyyy HH:mm · Tomada: X (08:00–10:00)")
     * en un evento. El tipo se deduce por el nombre del elemento.
     */
    fun parseLegacy(entry0: String, items: List<Medication>): Event? {
        var entry = entry0
        OLD_STAMP.find(entry)?.let { m -> // "dd/MM HH:mm" sin año
            val (d, mo, hm) = m.destructured
            val now = LocalDate.now()
            val date = runCatching { LocalDate.of(now.year, mo.toInt(), d.toInt()) }.getOrNull() ?: return null
            val year = if (date > now) now.year - 1 else now.year
            entry = "$d/$mo/$year $hm · " + entry.substring(m.range.last + 1)
        }
        val t = runCatching { LocalDateTime.parse(entry.substringBefore(" · "), Event.STAMP) }.getOrNull() ?: return null
        val text = entry.substringAfter(" · ")
        val event = text.substringBefore(": ", text)
        var name = if (": " in text) text.substringAfter(": ") else ""
        var slot = ""
        var detail = ""
        if (name.endsWith(")") && " (" in name) {
            val inner = name.substringAfterLast(" (").removeSuffix(")")
            name = name.substringBeforeLast(" (")
            if (SLOT_RX.matches(inner)) slot = inner else detail = inner
        }
        val item = items.find { it.name == name }
        return Event(
            ts = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            itemId = item?.id, itemName = name, kind = item?.kind ?: if (name.isNotEmpty()) Kind.MEDICATION else null,
            slot = slot, event = event, detail = detail,
        )
    }

    /** Orden de la lista: anclados primero; luego por hora (los "a demanda" al final). */
    private fun sorted(list: List<Medication>) =
        list.sortedWith(compareBy({ !it.pinned }, { it.mode == Mode.ON_DEMAND }, { it.startMin }))

    fun all(ctx: Context): List<Medication> { init(ctx); return _meds.value }

    fun get(ctx: Context, id: Int): Medication? = all(ctx).find { it.id == id }

    fun nextId(ctx: Context): Int = (all(ctx).maxOfOrNull { it.id } ?: 0) + 1

    @Synchronized
    fun upsert(ctx: Context, med: Medication) {
        val list = all(ctx).toMutableList()
        val i = list.indexOfFirst { it.id == med.id }
        if (i >= 0) list[i] = med else list += med
        _meds.value = sorted(list)
        persist(ctx)
    }

    /** Sustituye todo (importar copia de seguridad). */
    @Synchronized
    fun replaceAll(ctx: Context, meds: List<Medication>, events: List<Event>, readings: List<Reading>) {
        init(ctx)
        _meds.value = sorted(meds)
        persist(ctx)
        Store.replaceAll(ctx, events, readings)
        refreshHistory(ctx)
        _readingsVersion.value++
    }

    @Synchronized
    fun delete(ctx: Context, id: Int) {
        _meds.value = all(ctx).filterNot { it.id == id }
        persist(ctx)
    }

    /** Apunte general en el historial (sin elemento asociado). */
    @Synchronized
    fun log(ctx: Context, text: String) {
        init(ctx)
        Store.addEvent(ctx, Event(ts = System.currentTimeMillis(), event = text))
        refreshHistory(ctx)
    }

    /** Apunte de un elemento, p.ej. logItem(ctx, "Tomada", med, 1). */
    @Synchronized
    fun logItem(ctx: Context, event: String, med: Medication, slot: Int? = null, detail: String = "") {
        init(ctx)
        Store.addEvent(
            ctx,
            Event(
                ts = System.currentTimeMillis(), itemId = med.id, itemName = med.name, kind = med.kind,
                slot = if (slot != null && med.multi && slot in med.slots.indices) med.slots[slot].label else "",
                event = event, detail = detail,
            )
        )
        refreshHistory(ctx)
    }

    /** Guarda una medición. */
    fun addReading(ctx: Context, r: Reading) {
        init(ctx)
        Store.addReading(ctx, r)
        _readingsVersion.value++
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

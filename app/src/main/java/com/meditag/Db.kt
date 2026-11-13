package com.meditag

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Un apunte del historial (toma, cancelación, alarma sin respuesta, cambios…). */
data class Event(
    val id: Long = 0,
    val ts: Long,
    val itemId: Int? = null,
    val itemName: String = "",
    val kind: Kind? = null,
    val slot: String = "",     // franja, p.ej. "08:00–10:00" (vacío si no aplica)
    val event: String,         // "Tomada", "Toma cancelada"…
    val detail: String = "",
) {
    val time: LocalDateTime get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())

    /** Texto para la pantalla de historial. */
    fun display(): String {
        val sb = StringBuilder(time.format(STAMP)).append(" · ").append(event)
        if (itemName.isNotEmpty()) sb.append(": ").append(itemName)
        if (slot.isNotEmpty()) sb.append(" (").append(slot).append(")")
        if (detail.isNotEmpty()) sb.append(" — ").append(detail)
        return sb.toString()
    }

    fun toJson(): JSONObject = JSONObject().put("ts", ts).put("itemId", itemId ?: -1).put("itemName", itemName)
        .put("kind", kind?.name ?: "").put("slot", slot).put("event", event).put("detail", detail)

    companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

        fun fromJson(o: JSONObject) = Event(
            ts = o.getLong("ts"),
            itemId = o.optInt("itemId", -1).takeIf { it >= 0 },
            itemName = o.optString("itemName"),
            kind = o.optString("kind").ifBlank { null }?.let { runCatching { Kind.valueOf(it) }.getOrNull() },
            slot = o.optString("slot"),
            event = o.getString("event"),
            detail = o.optString("detail"),
        )
    }
}

/** Una medición (tensión, saturación, glucemia, peso, temperatura). Los campos no medidos van a null. */
data class Reading(
    val id: Long = 0,
    val ts: Long,
    val itemId: Int? = null,
    val itemName: String = "",
    val slot: String = "",
    val sys: Int? = null,
    val dia: Int? = null,
    val pulse: Int? = null,
    val spo2: Int? = null,
    val glucose: Int? = null,
    val glucoseCtx: String? = null,
    val weight: Double? = null,
    val temp: Double? = null,
    val note: String = "",
) {
    val time: LocalDateTime get() = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())

    /** Resumen legible, p.ej. "125/82 mmHg · 71 lpm · SatO₂ 98 %". */
    fun summary(): String = buildList {
        if (sys != null && dia != null) add("$sys/$dia mmHg")
        if (pulse != null) add("$pulse lpm")
        if (spo2 != null) add("SatO₂ $spo2 %")
        if (glucose != null) add("Glucemia $glucose mg/dL" + (glucoseCtx?.let { " ($it)" } ?: ""))
        if (weight != null) add("${dec(weight)} kg")
        if (temp != null) add("${dec(temp)} °C")
    }.joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().put("ts", ts).put("itemId", itemId ?: -1).put("itemName", itemName)
        .put("slot", slot).put("note", note).apply {
            sys?.let { put("sys", it) }; dia?.let { put("dia", it) }; pulse?.let { put("pulse", it) }
            spo2?.let { put("spo2", it) }; glucose?.let { put("glucose", it) }
            glucoseCtx?.let { put("glucoseCtx", it) }; weight?.let { put("weight", it) }; temp?.let { put("temp", it) }
        }

    companion object {
        fun fromJson(o: JSONObject): Reading {
            fun int(k: String) = if (o.has(k)) o.getInt(k) else null
            fun dbl(k: String) = if (o.has(k)) o.getDouble(k) else null
            return Reading(
                ts = o.getLong("ts"),
                itemId = o.optInt("itemId", -1).takeIf { it >= 0 },
                itemName = o.optString("itemName"), slot = o.optString("slot"),
                sys = int("sys"), dia = int("dia"), pulse = int("pulse"), spo2 = int("spo2"),
                glucose = int("glucose"), glucoseCtx = o.optString("glucoseCtx").ifBlank { null },
                weight = dbl("weight"), temp = dbl("temp"), note = o.optString("note"),
            )
        }
    }
}

/** Número con hasta 1 decimal y coma decimal: 72.5 -> "72,5", 80.0 -> "80". */
fun dec(v: Double): String =
    BigDecimal(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')

private class DbHelper(ctx: Context) : SQLiteOpenHelper(ctx, "meditag.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL,
               item_id INTEGER, item_name TEXT NOT NULL, kind TEXT, slot TEXT NOT NULL,
               event TEXT NOT NULL, detail TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX events_ts ON events(ts)")
        db.execSQL(
            """CREATE TABLE readings(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL,
               item_id INTEGER, item_name TEXT NOT NULL, slot TEXT NOT NULL,
               sys INTEGER, dia INTEGER, pulse INTEGER, spo2 INTEGER, glucose INTEGER, glucose_ctx TEXT,
               weight REAL, temp REAL, note TEXT NOT NULL)"""
        )
        db.execSQL("CREATE INDEX readings_ts ON readings(ts)")
        db.execSQL("CREATE INDEX readings_item ON readings(item_id, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
}

/** Base de datos local (SQLite) con el historial y las mediciones. */
object Store {
    @Volatile private var helper: DbHelper? = null

    private fun db(ctx: Context): SQLiteDatabase =
        (helper ?: synchronized(this) { helper ?: DbHelper(ctx.applicationContext).also { helper = it } }).writableDatabase

    private fun ContentValues.putOrNull(k: String, v: Int?) = if (v == null) putNull(k) else put(k, v)
    private fun ContentValues.putOrNull(k: String, v: Double?) = if (v == null) putNull(k) else put(k, v)
    private fun ContentValues.putOrNull(k: String, v: String?) = if (v == null) putNull(k) else put(k, v)
    private fun Cursor.intOrNull(i: Int) = if (isNull(i)) null else getInt(i)
    private fun Cursor.dblOrNull(i: Int) = if (isNull(i)) null else getDouble(i)
    private fun Cursor.strOrNull(i: Int) = if (isNull(i)) null else getString(i)

    // ---------------------------------------------------------------- eventos

    private fun insertEvent(db: SQLiteDatabase, e: Event): Long = db.insert("events", null, ContentValues().apply {
        put("ts", e.ts); putOrNull("item_id", e.itemId); put("item_name", e.itemName)
        putOrNull("kind", e.kind?.name); put("slot", e.slot); put("event", e.event); put("detail", e.detail)
    })

    fun addEvent(ctx: Context, e: Event): Long = insertEvent(db(ctx), e)

    /** Eventos desde [since] (epoch millis), del más reciente al más antiguo. */
    fun events(ctx: Context, since: Long = 0L): List<Event> =
        db(ctx).rawQuery(
            "SELECT id, ts, item_id, item_name, kind, slot, event, detail FROM events WHERE ts >= ? ORDER BY ts DESC, id DESC",
            arrayOf(since.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Event(
                        id = c.getLong(0), ts = c.getLong(1), itemId = c.intOrNull(2), itemName = c.getString(3),
                        kind = c.strOrNull(4)?.let { runCatching { Kind.valueOf(it) }.getOrNull() },
                        slot = c.getString(5), event = c.getString(6), detail = c.getString(7),
                    )
                )
            }
        }

    /** Borra el último evento [names] de un elemento y toma desde [since]. Devuelve si borró alguno. */
    fun deleteLastEvent(ctx: Context, itemId: Int, slot: String, names: List<String>, since: Long): Boolean {
        val db = db(ctx)
        val marks = names.joinToString(",") { "?" }
        val args = arrayOf(itemId.toString(), slot, since.toString()) + names.toTypedArray()
        val id = db.rawQuery(
            "SELECT id FROM events WHERE item_id = ? AND slot = ? AND ts >= ? AND event IN ($marks) ORDER BY ts DESC, id DESC LIMIT 1",
            args
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return false
        return db.delete("events", "id = ?", arrayOf(id.toString())) > 0
    }

    fun pruneEvents(ctx: Context, before: Long) {
        db(ctx).delete("events", "ts < ?", arrayOf(before.toString()))
    }

    // ---------------------------------------------------------------- mediciones

    private fun insertReading(db: SQLiteDatabase, r: Reading): Long = db.insert("readings", null, ContentValues().apply {
        put("ts", r.ts); putOrNull("item_id", r.itemId); put("item_name", r.itemName); put("slot", r.slot)
        putOrNull("sys", r.sys); putOrNull("dia", r.dia); putOrNull("pulse", r.pulse); putOrNull("spo2", r.spo2)
        putOrNull("glucose", r.glucose); putOrNull("glucose_ctx", r.glucoseCtx)
        putOrNull("weight", r.weight); putOrNull("temp", r.temp); put("note", r.note)
    })

    fun addReading(ctx: Context, r: Reading): Long = insertReading(db(ctx), r)

    private const val READING_COLS =
        "id, ts, item_id, item_name, slot, sys, dia, pulse, spo2, glucose, glucose_ctx, weight, temp, note"

    private fun Cursor.toReading() = Reading(
        id = getLong(0), ts = getLong(1), itemId = intOrNull(2), itemName = getString(3), slot = getString(4),
        sys = intOrNull(5), dia = intOrNull(6), pulse = intOrNull(7), spo2 = intOrNull(8),
        glucose = intOrNull(9), glucoseCtx = strOrNull(10), weight = dblOrNull(11), temp = dblOrNull(12),
        note = getString(13),
    )

    /** Mediciones desde [since], de la más reciente a la más antigua. */
    fun readings(ctx: Context, since: Long = 0L): List<Reading> =
        db(ctx).rawQuery("SELECT $READING_COLS FROM readings WHERE ts >= ? ORDER BY ts DESC, id DESC", arrayOf(since.toString()))
            .use { c -> buildList { while (c.moveToNext()) add(c.toReading()) } }

    fun lastReading(ctx: Context, itemId: Int): Reading? =
        db(ctx).rawQuery("SELECT $READING_COLS FROM readings WHERE item_id = ? ORDER BY ts DESC, id DESC LIMIT 1", arrayOf(itemId.toString()))
            .use { c -> if (c.moveToFirst()) c.toReading() else null }

    fun readingCount(ctx: Context): Int =
        db(ctx).rawQuery("SELECT COUNT(*) FROM readings", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Sustituye todo (al importar una copia de seguridad). */
    fun replaceAll(ctx: Context, events: List<Event>, readings: List<Reading>) {
        val db = db(ctx)
        db.beginTransaction()
        try {
            db.delete("events", null, null)
            db.delete("readings", null, null)
            events.forEach { insertEvent(db, it) }
            readings.forEach { insertReading(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Inserta muchos eventos de una vez (migración del historial antiguo). */
    fun addEvents(ctx: Context, events: List<Event>) {
        val db = db(ctx)
        db.beginTransaction()
        try {
            events.forEach { insertEvent(db, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

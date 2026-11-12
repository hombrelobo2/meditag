package com.meditag

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Informe en PDF para el médico: cumplimiento, incidencias y mediciones con resumen,
 * gráfica y tabla. Se dibuja a mano con PdfDocument (sin librerías externas).
 */
object Report {
    fun fileName() = "meditag-informe-${LocalDate.now()}.pdf"

    private val D = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val DS = DateTimeFormatter.ofPattern("dd/MM")
    private val T = DateTimeFormatter.ofPattern("HH:mm")
    private fun ldt(ts: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault())

    private const val RED = 0xFFC62828.toInt()
    private const val BLUE = 0xFF1565C0.toInt()
    private const val ORANGE = 0xFFEF6C00.toInt()
    private const val TEAL = 0xFF00695C.toInt()

    /** Clasificación de un evento del historial para el cumplimiento. */
    private enum class Cls { DONE, LATE, ON_DEMAND, CANCELLED, MISSED, OTHER }

    private val DONE_WORDS = Kind.entries.map { it.doneWord } + listOf("Tomada", "Tomado", "Hecho")

    private fun classify(e: Event): Cls = when {
        e.event.contains("a demanda") -> Cls.ON_DEMAND
        e.event.contains("NO realizada") -> Cls.MISSED
        e.event.contains("ancelada") -> Cls.CANCELLED
        DONE_WORDS.any { e.event.startsWith(it) } -> if (e.event.contains("con retraso")) Cls.LATE else Cls.DONE
        else -> Cls.OTHER
    }

    fun write(ctx: Context, uri: Uri, o: ExportOptions): String {
        val since = Export.since(o)
        val events = Store.events(ctx, since).reversed()
        val readings = if (Kind.MEASUREMENT in o.kinds) Store.readings(ctx, since).reversed() else emptyList()
        val doc = PdfDocument()
        val w = PdfWriter(doc)
        w.newPage()

        // ---------------------------------------------------------------- portada
        w.text("Informe de seguimiento", w.title)
        w.gap(2f)
        w.text("Generado con MediTag el ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'a las' HH:mm"))}", w.small)
        val periodo = if (o.days <= 0) "Todo el historial disponible"
        else "Del ${LocalDate.now().minusDays(o.days.toLong()).format(D)} al ${LocalDate.now().format(D)}"
        w.gap(6f)
        w.text("Periodo: $periodo", w.bold)
        w.text(
            "Incluye: " + Kind.entries.filter { it in o.kinds }.joinToString {
                when (it) {
                    Kind.MEDICATION -> "medicación"; Kind.SUPPLEMENT -> "complementos"
                    Kind.MEASUREMENT -> "mediciones"; Kind.HABIT -> "hábitos"
                }
            },
            w.body
        )
        w.gap(6f)
        w.note(
            "Datos introducidos por el propio paciente con la app MediTag. Las tomas se registran al acercar " +
                "el móvil a una pegatina NFC del envase; las mediciones las teclea el paciente a partir de su aparato. " +
                "La app no interpreta los valores."
        )

        var anything = false

        // ---------------------------------------------------------------- cumplimiento
        val tracked = events.filter { it.kind != null && it.kind in o.kinds && it.itemName.isNotEmpty() }
        val groups = tracked.groupBy { it.kind!! to it.itemName }
            .mapValues { (_, list) -> list.groupingBy { classify(it) }.eachCount() }
            .filterValues { c -> c.keys.any { it != Cls.OTHER } }
        if (groups.isNotEmpty()) {
            anything = true
            w.h2("Cumplimiento")
            val rows = groups.entries.sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second })).map { (key, c) ->
                val done = (c[Cls.DONE] ?: 0) + (c[Cls.LATE] ?: 0)
                val missed = c[Cls.MISSED] ?: 0
                val pct = if (done + missed > 0) "${(done * 100.0 / (done + missed)).roundToInt()} %" else "—"
                listOf(
                    "${key.first.label}: ${key.second}",
                    (done + (c[Cls.ON_DEMAND] ?: 0)).toString(),
                    (c[Cls.LATE] ?: 0).toString(),
                    (c[Cls.CANCELLED] ?: 0).toString(),
                    missed.toString(),
                    if ((c[Cls.ON_DEMAND] ?: 0) > 0 && done == 0) "a demanda" else pct,
                )
            }
            w.table(
                listOf("Elemento", "Hechas", "Con retraso", "Canceladas", "No realizadas", "Cumplimiento"),
                listOf(0.34f, 0.11f, 0.13f, 0.13f, 0.14f, 0.15f), rows,
                rightFrom = 1
            )
            w.text(
                "Cumplimiento = hechas (incluidas con retraso) ÷ (hechas + no realizadas). " +
                    "Las canceladas por el paciente no cuentan como incumplimiento.",
                w.small
            )

            val incid = tracked.filter { classify(it).let { c -> c == Cls.MISSED || c == Cls.CANCELLED || c == Cls.LATE } }
            if (incid.isNotEmpty()) {
                w.h3("Incidencias (no realizadas, canceladas o con retraso)")
                w.table(
                    listOf("Fecha", "Hora", "Elemento", "Toma", "Incidencia"),
                    listOf(0.15f, 0.09f, 0.32f, 0.16f, 0.28f),
                    incid.map {
                        val t = ldt(it.ts)
                        listOf(t.format(D), t.format(T), it.itemName, it.slot, it.event)
                    }
                )
            }
        }

        // ---------------------------------------------------------------- mediciones
        fun section(
            title: String, sel: (Reading) -> Boolean, summary: (List<Reading>) -> List<String>,
            series: List<Series>, unit: String, headers: List<String>, widths: List<Float>, row: (Reading) -> List<String>,
        ) {
            val rs = readings.filter(sel)
            if (rs.isEmpty()) return
            anything = true
            w.h2(title)
            summary(rs).forEach { w.text(it, w.body) }
            w.gap(4f)
            w.chart(series.filter { it.points.isNotEmpty() }, unit)
            w.table(headers, widths, rs.map(row), rightFrom = 2)
        }

        fun stats(v: List<Double>, fmt: (Double) -> String) =
            "media ${fmt(v.average())}, mínimo ${fmt(v.min())}, máximo ${fmt(v.max())}"
        fun i0(d: Double) = d.roundToInt().toString()
        fun pts(rs: List<Reading>, f: (Reading) -> Number?) = rs.mapNotNull { r -> f(r)?.let { r.ts to it.toDouble() } }

        val bp = readings.filter { it.sys != null && it.dia != null }
        section(
            "Tensión arterial", { it.sys != null && it.dia != null },
            { rs ->
                listOfNotNull(
                    "${rs.size} mediciones · media ${i0(rs.mapNotNull { it.sys?.toDouble() }.average())}/" +
                        "${i0(rs.mapNotNull { it.dia?.toDouble() }.average())} mmHg",
                    "Sistólica: ${stats(rs.mapNotNull { it.sys?.toDouble() }, ::i0)} mmHg",
                    "Diastólica: ${stats(rs.mapNotNull { it.dia?.toDouble() }, ::i0)} mmHg",
                    rs.mapNotNull { it.pulse?.toDouble() }.takeIf { it.isNotEmpty() }?.let { "Pulso: ${stats(it, ::i0)} lpm" },
                )
            },
            listOf(
                Series("Sistólica", RED, pts(bp) { it.sys }),
                Series("Diastólica", BLUE, pts(bp) { it.dia }),
                Series("Pulso", ORANGE, pts(bp) { it.pulse }),
            ),
            "mmHg / lpm",
            listOf("Fecha", "Hora", "Sistólica", "Diastólica", "Pulso", "Nota"),
            listOf(0.15f, 0.09f, 0.12f, 0.12f, 0.1f, 0.42f),
        ) { r -> val t = r.time; listOf(t.format(D), t.format(T), "${r.sys}", "${r.dia}", r.pulse?.toString() ?: "", r.note) }

        val sp = readings.filter { it.spo2 != null }
        section(
            "Saturación de oxígeno", { it.spo2 != null },
            { rs -> listOf("${rs.size} mediciones · SatO₂: ${stats(rs.mapNotNull { it.spo2?.toDouble() }, ::i0)} %") },
            listOf(Series("SatO₂", TEAL, pts(sp) { it.spo2 })),
            "%",
            listOf("Fecha", "Hora", "SatO₂ (%)", "Pulso", "Nota"),
            listOf(0.15f, 0.09f, 0.13f, 0.1f, 0.53f),
        ) { r -> val t = r.time; listOf(t.format(D), t.format(T), "${r.spo2}", r.pulse?.toString() ?: "", r.note) }

        val gl = readings.filter { it.glucose != null }
        section(
            "Glucemia", { it.glucose != null },
            { rs ->
                listOf("${rs.size} mediciones · ${stats(rs.mapNotNull { it.glucose?.toDouble() }, ::i0)} mg/dL") +
                    rs.groupBy { it.glucoseCtx ?: "Sin indicar" }.map { (c, l) ->
                        "$c: ${l.size} · media ${i0(l.mapNotNull { it.glucose?.toDouble() }.average())} mg/dL"
                    }
            },
            listOf(Series("Glucosa", BLUE, pts(gl) { it.glucose })),
            "mg/dL",
            listOf("Fecha", "Hora", "mg/dL", "Momento", "Nota"),
            listOf(0.15f, 0.09f, 0.1f, 0.22f, 0.44f),
        ) { r -> val t = r.time; listOf(t.format(D), t.format(T), "${r.glucose}", r.glucoseCtx ?: "", r.note) }

        val wt = readings.filter { it.weight != null }
        section(
            "Peso", { it.weight != null },
            { rs ->
                val first = rs.first().weight!!; val last = rs.last().weight!!
                val diff = last - first
                listOf(
                    "${rs.size} mediciones · primera ${dec(first)} kg, última ${dec(last)} kg " +
                        "(${if (diff >= 0) "+" else "−"}${dec(kotlin.math.abs(diff))} kg)"
                )
            },
            listOf(Series("Peso", TEAL, pts(wt) { it.weight })),
            "kg",
            listOf("Fecha", "Hora", "Peso (kg)", "Nota"),
            listOf(0.15f, 0.09f, 0.13f, 0.63f),
        ) { r -> val t = r.time; listOf(t.format(D), t.format(T), dec(r.weight!!), r.note) }

        val tp = readings.filter { it.temp != null }
        section(
            "Temperatura", { it.temp != null },
            { rs -> listOf("${rs.size} mediciones · ${stats(rs.mapNotNull { it.temp }, ::dec)} °C") },
            listOf(Series("Temperatura", RED, pts(tp) { it.temp })),
            "°C",
            listOf("Fecha", "Hora", "°C", "Nota"),
            listOf(0.15f, 0.09f, 0.1f, 0.66f),
        ) { r -> val t = r.time; listOf(t.format(D), t.format(T), dec(r.temp!!), r.note) }

        if (!anything) {
            w.gap(10f)
            w.text("No hay datos en el periodo elegido.", w.bold)
        }

        w.finish()
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { doc.writeTo(it) }
        doc.close()
        return if (anything) "Informe PDF creado (${w.pages} ${if (w.pages == 1) "página" else "páginas"})"
        else "Informe creado, pero no hay datos en el periodo elegido"
    }

    class Series(val name: String, val color: Int, val points: List<Pair<Long, Double>>)

    /** Escritura secuencial en páginas A4 con salto automático. */
    private class PdfWriter(private val doc: PdfDocument) {
        val PW = 595f; val PH = 842f; val M = 42f
        val contentW = PW - 2 * M
        var pages = 0
        private var page: PdfDocument.Page? = null
        private lateinit var c: Canvas
        var y = 0f

        private fun paint(size: Float, color: Int = 0xFF222222.toInt(), bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        val title = paint(22f, TEAL, true)
        val h2p = paint(14f, TEAL, true)
        val h3p = paint(11.5f, 0xFF222222.toInt(), true)
        val body = paint(10f)
        val bold = paint(10f, bold = true)
        val small = paint(8.3f, 0xFF666666.toInt())
        private val cell = paint(8.6f)
        private val cellB = paint(8.6f, bold = true)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD5DEDB.toInt(); strokeWidth = 0.7f }
        private val fill = Paint().apply { style = Paint.Style.FILL }

        fun newPage() {
            page?.let { doc.finishPage(it) }
            pages++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PW.toInt(), PH.toInt(), pages).create())
            page = p; c = p.canvas
            // pie de página
            c.drawLine(M, PH - 30f, PW - M, PH - 30f, line)
            c.drawText("MediTag · Informe de seguimiento", M, PH - 18f, small)
            val pn = "Página $pages"
            c.drawText(pn, PW - M - small.measureText(pn), PH - 18f, small)
            y = M
        }

        fun finish() { page?.let { doc.finishPage(it) }; page = null }

        fun ensure(h: Float) { if (y + h > PH - 44f) newPage() }

        fun gap(h: Float) { y += h }

        private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
            if (s.isEmpty()) return listOf("")
            val out = mutableListOf<String>()
            s.split("\n").forEach { para ->
                var cur = ""
                para.split(" ").forEach { word ->
                    val cand = if (cur.isEmpty()) word else "$cur $word"
                    if (p.measureText(cand) <= maxW || cur.isEmpty()) cur = cand
                    else { out += cur; cur = word }
                }
                out += cur
            }
            // palabras más largas que la columna: se cortan
            return out.flatMap { l ->
                if (p.measureText(l) <= maxW) listOf(l) else {
                    val parts = mutableListOf<String>(); var cur = ""
                    l.forEach { ch -> if (p.measureText(cur + ch) > maxW) { parts += cur; cur = "$ch" } else cur += ch }
                    parts + cur
                }
            }
        }

        private fun lh(p: Paint) = p.textSize * 1.35f

        fun text(s: String, p: Paint, x: Float = M, maxW: Float = contentW) {
            wrap(s, p, maxW).forEach { l ->
                ensure(lh(p))
                y += p.textSize
                c.drawText(l, x, y, p)
                y += lh(p) - p.textSize
            }
        }

        fun h2(s: String) { ensure(60f); gap(14f); text(s, h2p); c.drawLine(M, y + 1f, PW - M, y + 1f, line); gap(6f) }
        fun h3(s: String) { ensure(40f); gap(8f); text(s, h3p); gap(2f) }

        fun note(s: String) {
            val lines = wrap(s, small, contentW - 16f)
            val h = lines.size * lh(small) + 12f
            ensure(h)
            fill.color = 0xFFE0F2F1.toInt()
            c.drawRoundRect(RectF(M, y, PW - M, y + h), 5f, 5f, fill)
            var yy = y + 6f
            lines.forEach { l -> yy += small.textSize; c.drawText(l, M + 8f, yy, small); yy += lh(small) - small.textSize }
            y += h + 4f
        }

        /** Tabla con cabecera repetida en cada página. [rightFrom]: columnas desde esta alineadas a la derecha. */
        fun table(headers: List<String>, widths: List<Float>, rows: List<List<String>>, rightFrom: Int = Int.MAX_VALUE) {
            val ws = widths.map { it * contentW }
            val pad = 4f
            fun drawRow(cells: List<String>, p: Paint, bg: Int?) {
                val wrapped = cells.mapIndexed { i, s -> wrap(s, p, ws[i] - 2 * pad) }
                val h = wrapped.maxOf { it.size } * lh(p) + 2 * pad
                if (bg != null) { fill.color = bg; c.drawRect(M, y, PW - M, y + h, fill) }
                var x = M
                wrapped.forEachIndexed { i, ls ->
                    var yy = y + pad
                    ls.forEach { l ->
                        yy += p.textSize
                        val tx = if (i >= rightFrom) x + ws[i] - pad - p.measureText(l) else x + pad
                        c.drawText(l, tx, yy, p)
                        yy += lh(p) - p.textSize
                    }
                    x += ws[i]
                }
                y += h
                c.drawLine(M, y, PW - M, y, line)
            }
            fun rowH(cells: List<String>, p: Paint) =
                cells.mapIndexed { i, s -> wrap(s, p, ws[i] - 2 * pad).size }.max() * lh(p) + 2 * pad

            ensure(rowH(headers, cellB) + rowH(rows.firstOrNull() ?: headers, cell))
            drawRow(headers, cellB, 0xFFE8F1EF.toInt())
            rows.forEachIndexed { idx, r ->
                if (y + rowH(r, cell) > PH - 44f) { newPage(); drawRow(headers, cellB, 0xFFE8F1EF.toInt()) }
                drawRow(r, cell, if (idx % 2 == 1) 0xFFF7FAF9.toInt() else null)
            }
            gap(6f)
        }

        /** Gráfica de líneas con varias series sobre el tiempo. */
        fun chart(series: List<Series>, unit: String) {
            val all = series.flatMap { it.points }
            if (all.isEmpty()) return
            val h = 150f
            ensure(h + 46f)
            val left = M + 34f; val right = PW - M - 6f
            val top = y + 8f; val bottom = top + h
            var t0 = all.minOf { it.first }; var t1 = all.maxOf { it.first }
            if (t1 - t0 < 3_600_000L) { t0 -= 43_200_000L; t1 += 43_200_000L }
            var v0 = all.minOf { it.second }; var v1 = all.maxOf { it.second }
            val padV = max((v1 - v0) * 0.12, if (v1 > 50) 5.0 else 0.5)
            v0 -= padV; v1 += padV
            fun px(t: Long) = left + (right - left) * ((t - t0).toFloat() / (t1 - t0).toFloat())
            fun py(v: Double) = bottom - (bottom - top) * ((v - v0) / (v1 - v0)).toFloat()

            val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9AA5A1.toInt(); strokeWidth = 0.8f }
            val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE6ECEA.toInt(); strokeWidth = 0.6f }
            val lbl = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 7.5f; color = 0xFF666666.toInt() }
            val decimals = v1 - v0 < 10
            for (k in 0..4) {
                val v = v0 + (v1 - v0) * k / 4
                val yy = py(v)
                c.drawLine(left, yy, right, yy, grid)
                val s = if (decimals) dec(v) else v.roundToInt().toString()
                c.drawText(s, left - 4f - lbl.measureText(s), yy + 2.5f, lbl)
            }
            c.drawLine(left, bottom, right, bottom, axis)
            c.drawLine(left, top, left, bottom, axis)
            c.drawText(unit, M, top - 1f, lbl)
            listOf(t0, (t0 + t1) / 2, t1).forEachIndexed { i, t ->
                val s = ldt(t).format(DS)
                val x = px(t) - when (i) { 0 -> 0f; 1 -> lbl.measureText(s) / 2; else -> lbl.measureText(s) }
                c.drawText(s, x, bottom + 10f, lbl)
            }
            series.forEach { s ->
                val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = s.color; strokeWidth = 1.4f; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
                }
                val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = s.color; style = Paint.Style.FILL }
                val pts = s.points.sortedBy { it.first }
                if (pts.size > 1) {
                    val path = Path()
                    pts.forEachIndexed { i, (t, v) -> if (i == 0) path.moveTo(px(t), py(v)) else path.lineTo(px(t), py(v)) }
                    c.drawPath(path, lp)
                }
                if (pts.size <= 60) pts.forEach { (t, v) -> c.drawCircle(px(t), py(v), 1.8f, dp) }
            }
            // leyenda
            var lx = left
            val ly = bottom + 24f
            if (series.size > 1) series.forEach { s ->
                fill.color = s.color
                c.drawRect(lx, ly - 6f, lx + 8f, ly + 2f, fill)
                c.drawText(s.name, lx + 11f, ly + 1f, lbl)
                lx += 11f + lbl.measureText(s.name) + 14f
            }
            y = bottom + if (series.size > 1) 34f else 20f
        }
    }
}


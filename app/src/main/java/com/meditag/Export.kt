package com.meditag

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId

/** Qué exportar y de qué periodo. [days] = 0 para todo. */
data class ExportOptions(val days: Int, val kinds: Set<Kind>)

/** Exportación a Excel pensada para entregar al médico: una hoja por tipo. */
object Export {
    val PERIODS = listOf(30 to "30 días", 90 to "3 meses", 365 to "12 meses", 0 to "Todo")

    fun fileName() = "meditag-seguimiento-${LocalDate.now()}.xlsx"

    private fun date(ts: Long) = java.time.Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())

    private fun dt(ts: Long): List<Xlsx.Cell> {
        val z = date(ts)
        return listOf(Xlsx.Date(z.toLocalDate()), Xlsx.Time(z.toLocalTime().withSecond(0).withNano(0)))
    }

    private fun num(v: Number?): Xlsx.Cell = v?.let { Xlsx.Num(it.toDouble()) } ?: Xlsx.Empty
    private fun txt(v: String?): Xlsx.Cell = if (v.isNullOrEmpty()) Xlsx.Empty else Xlsx.Text(v)

    fun write(ctx: Context, uri: Uri, o: ExportOptions): String {
        val since = if (o.days <= 0) 0L
        else LocalDate.now().minusDays(o.days.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val sheets = mutableListOf<Xlsx.Sheet>()
        var total = 0

        // Tomas y eventos de medicación, complementos y hábitos (de más antiguo a más reciente)
        val events = Store.events(ctx, since).reversed()
        listOf(Kind.MEDICATION to "Medicación", Kind.SUPPLEMENT to "Complementos", Kind.HABIT to "Hábitos").forEach { (k, title) ->
            if (k !in o.kinds) return@forEach
            val rows = events.filter { it.kind == k }.map { e ->
                dt(e.ts) + listOf(Xlsx.Text(e.event), txt(e.itemName), txt(e.slot), txt(e.detail))
            }
            if (rows.isNotEmpty()) {
                sheets += Xlsx.Sheet(title, listOf("Fecha", "Hora", "Evento", "Nombre", "Toma", "Detalle"), listOf(12, 7, 30, 26, 13, 40), rows)
                total += rows.size
            }
        }

        // Mediciones: una hoja por tipo de medida
        if (Kind.MEASUREMENT in o.kinds) {
            val rs = Store.readings(ctx, since).reversed()
            fun add(title: String, header: List<String>, widths: List<Int>, rows: List<List<Xlsx.Cell>>) {
                if (rows.isNotEmpty()) { sheets += Xlsx.Sheet(title, header, widths, rows); total += rows.size }
            }
            add(
                "Tensión", listOf("Fecha", "Hora", "Sistólica (mmHg)", "Diastólica (mmHg)", "Pulso (lpm)", "Nota", "Elemento"),
                listOf(12, 7, 16, 17, 12, 30, 20),
                rs.filter { it.sys != null || it.dia != null }.map {
                    dt(it.ts) + listOf(num(it.sys), num(it.dia), num(it.pulse), txt(it.note), txt(it.itemName))
                }
            )
            add(
                "Saturación", listOf("Fecha", "Hora", "SatO₂ (%)", "Pulso (lpm)", "Nota", "Elemento"),
                listOf(12, 7, 11, 12, 30, 20),
                rs.filter { it.spo2 != null }.map {
                    dt(it.ts) + listOf(num(it.spo2), num(it.pulse), txt(it.note), txt(it.itemName))
                }
            )
            add(
                "Glucemia", listOf("Fecha", "Hora", "Glucosa (mg/dL)", "Momento", "Nota", "Elemento"),
                listOf(12, 7, 15, 18, 30, 20),
                rs.filter { it.glucose != null }.map {
                    dt(it.ts) + listOf(num(it.glucose), txt(it.glucoseCtx), txt(it.note), txt(it.itemName))
                }
            )
            add(
                "Peso", listOf("Fecha", "Hora", "Peso (kg)", "Nota", "Elemento"), listOf(12, 7, 11, 30, 20),
                rs.filter { it.weight != null }.map { dt(it.ts) + listOf(num(it.weight), txt(it.note), txt(it.itemName)) }
            )
            add(
                "Temperatura", listOf("Fecha", "Hora", "Temperatura (°C)", "Nota", "Elemento"), listOf(12, 7, 16, 30, 20),
                rs.filter { it.temp != null }.map { dt(it.ts) + listOf(num(it.temp), txt(it.note), txt(it.itemName)) }
            )
            // Cumplimiento de las mediciones (no realizadas, canceladas…)
            val ev = events.filter { it.kind == Kind.MEASUREMENT && it.event != "Medición registrada" }
                .map { e -> dt(e.ts) + listOf(Xlsx.Text(e.event), txt(e.itemName), txt(e.slot), txt(e.detail)) }
            add("Mediciones (registro)", listOf("Fecha", "Hora", "Evento", "Nombre", "Toma", "Detalle"), listOf(12, 7, 30, 20, 13, 40), ev)
        }

        if (sheets.isEmpty()) {
            sheets += Xlsx.Sheet("Sin datos", listOf("No hay datos en el periodo elegido"), listOf(40), emptyList())
        }
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { Xlsx.write(it, sheets) }
        return if (total == 0) "No hay datos en el periodo elegido" else "Exportados $total registros en ${sheets.size} hojas"
    }
}

/** Diálogo para elegir qué exportar y de qué periodo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportDialog(onExport: (ExportOptions) -> Unit, onDismiss: () -> Unit) {
    var days by remember { mutableStateOf(90) }
    var kinds by remember { mutableStateOf(Kind.entries.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exportar para el médico") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Se crea un Excel con una hoja para cada cosa: medicación, complementos, hábitos y cada tipo de medición.", style = MaterialTheme.typography.bodySmall)
                Text("Periodo", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Export.PERIODS.forEach { (d, label) ->
                        FilterChip(selected = days == d, onClick = { days = d }, label = { Text(label) })
                    }
                }
                Text("Qué incluir", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Kind.entries.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().clickable { kinds = if (k in kinds) kinds - k else kinds + k },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = k in kinds, onCheckedChange = { kinds = if (k in kinds) kinds - k else kinds + k })
                        Text(
                            "${k.icon} " + when (k) {
                                Kind.MEDICATION -> "Medicación"
                                Kind.SUPPLEMENT -> "Complementos"
                                Kind.MEASUREMENT -> "Mediciones (tensión, glucemia…)"
                                Kind.HABIT -> "Hábitos"
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = kinds.isNotEmpty(), onClick = { onExport(ExportOptions(days, kinds)); onDismiss() }) { Text("Exportar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}


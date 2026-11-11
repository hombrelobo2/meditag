package com.meditag

import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle

private val MODE_LABELS = listOf(
    Mode.DAILY to "Cada día",
    Mode.WEEKDAYS to "Días de la semana",
    Mode.INTERVAL to "Cada N días",
    Mode.DATES to "Fechas concretas",
    Mode.ON_DEMAND to "A demanda",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorDialog(initial: Medication?, act: MainActivity, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val today = LocalDate.now()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var tag by remember { mutableStateOf(initial?.tagId ?: "") }
    // Tomas del día (franjas horarias)
    val slots = remember { mutableStateListOf<Slot>().apply { addAll(initial?.slots ?: listOf(Slot(8 * 60, 10 * 60))) } }
    var mode by remember { mutableStateOf(initial?.mode ?: Mode.DAILY) }
    var weekdays by remember { mutableIntStateOf(initial?.weekdays ?: 0b0011111) }
    var interval by remember { mutableIntStateOf(initial?.intervalDays ?: 2) }
    var anchor by remember { mutableStateOf(initial?.anchorDate?.let(LocalDate::parse) ?: today) }
    var dates by remember { mutableStateOf(initial?.dates ?: emptySet()) }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var error by remember { mutableStateOf<String?>(null) }
    var timeMsg by remember { mutableStateOf<Pair<Int, String>?>(null) } // (toma, mensaje)
    var confirmDelete by remember { mutableStateOf(false) }
    val waiting = act.tagCapture != null

    DisposableEffect(Unit) { onDispose { act.tagCapture = null } }

    // Vista previa: qué días tendrían toma con la pauta actual
    val preview = Medication(
        id = 0, name = "", tagId = "", slots = slots.toList(), mode = mode,
        weekdays = weekdays, intervalDays = interval, anchorDate = anchor.toString(), dates = dates,
    )

    fun save() {
        val sorted = slots.sortedBy { it.start }
        val overlap = (1 until sorted.size).firstOrNull { sorted[it].start < sorted[it - 1].end }
        error = when {
            name.isBlank() -> "Escribe el nombre del medicamento"
            tag.isBlank() -> "Escanea la pegatina NFC del medicamento"
            mode != Mode.ON_DEMAND && sorted.any { it.end - it.start < MIN_RANGE } ->
                "Cada toma debe durar al menos $MIN_RANGE minutos"
            mode != Mode.ON_DEMAND && overlap != null ->
                "Las tomas de ${sorted[overlap - 1].label} y ${sorted[overlap].label} se solapan. Cambia sus horas."
            mode == Mode.WEEKDAYS && weekdays == 0 -> "Elige al menos un día de la semana"
            mode == Mode.DATES && dates.none { it >= today.toString() } -> "Marca en el calendario al menos un día (de hoy en adelante)"
            else -> null
        }
        if (error != null) return
        val base = initial ?: Medication(id = MedRepo.nextId(ctx), name = "", tagId = "", slots = sorted)
        val newDates = dates.filter { it >= today.minusDays(30).toString() }.toSet()
        // Las tomas que no cambian conservan su estado de hoy (tomada, cancelada…)
        val oldStates = initial?.todayStates().orEmpty()
        val states = sorted.map { sl ->
            initial?.slots?.indexOf(sl)?.takeIf { it >= 0 }?.let { oldStates.getOrNull(it) } ?: SlotState()
        }
        // Si cambian horarios o pauta, las tomas cuya hora ya pasó hoy no cuentan
        // (así no suena nada nada más guardar).
        val changed = initial == null || initial.slots != sorted || initial.mode != mode ||
            initial.weekdays != weekdays || initial.intervalDays != interval ||
            initial.anchorDate != anchor.toString() || initial.dates != newDates
        val med = base.copy(
            name = name.trim(), tagId = tag, slots = sorted,
            mode = mode, weekdays = weekdays, intervalDays = interval,
            anchorDate = anchor.toString(), dates = newDates,
            activeSince = if (changed) System.currentTimeMillis() else base.activeSince,
            stateDate = today.toString(), states = states,
        )
        MedRepo.upsert(ctx, med)
        // Para las alarmas que ya no correspondan (toma borrada o que ya no cuenta)
        for (k in 0 until MAX_SLOTS) {
            val st = if (k < med.slots.size) med.slotStatus(k) else null
            if (st != DoseStatus.DUE && st != DoseStatus.OVERDUE) AlarmService.stop(Occ.key(med.id, k))
        }
        MedRepo.log(ctx, (if (initial == null) "Añadido: " else "Modificado: ") + "${med.name} (${med.describe()})")
        AlarmScheduler.schedule(ctx, med)
        onDismiss()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancelar") }
                    Text(
                        if (initial == null) "Nuevo medicamento" else "Editar",
                        Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Button(onClick = { save() }) { Text("Guardar") }
                }
                HorizontalDivider()
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Nombre del medicamento") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Section("Etiqueta NFC")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (waiting) "Acerca la pegatina al móvil…" else tag.ifBlank { "Sin asignar" },
                            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
                        )
                        if (waiting) TextButton(onClick = { act.tagCapture = null }) { Text("Cancelar") }
                        else Button(onClick = { act.tagCapture = { tag = it } }) { Text("Escanear") }
                    }

                    Section("Pauta")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MODE_LABELS.forEach { (m, label) ->
                            FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(label) })
                        }
                    }

                    when (mode) {
                        Mode.DAILY -> Hint("Todos los días.")
                        Mode.WEEKDAYS -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                WEEKDAY_LETTERS.forEachIndexed { i, l ->
                                    val on = weekdays and (1 shl i) != 0
                                    DayBubble(l, on, Modifier.weight(1f)) { weekdays = weekdays xor (1 shl i) }
                                }
                            }
                        }
                        Mode.INTERVAL -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Cada", Modifier.padding(end = 8.dp))
                                OutlinedButton(onClick = { if (interval > 2) interval-- }) { Text("−") }
                                Text("$interval", Modifier.width(40.dp), textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.titleLarge)
                                OutlinedButton(onClick = { if (interval < 60) interval++ }) { Text("+") }
                                Text(if (interval == 2) "días (alternos)" else "días", Modifier.padding(start = 8.dp))
                            }
                            Hint("Toca en el calendario el día de la primera toma (ahora: ${fmtDay(anchor)}).")
                        }
                        Mode.DATES -> Hint("Toca en el calendario los días en los que hay toma (${dates.count { it >= today.toString() }} marcados).")
                        Mode.ON_DEMAND -> Hint(
                            "Sin horario ni alarma. Cada vez que la tomes, escanea la pegatina y quedará registrada " +
                                "con la hora, para llevar la cuenta."
                        )
                    }

                    if (mode != Mode.ON_DEMAND) {
                        MonthCalendar(
                            month = month,
                            onMonth = { month = it },
                            marked = { preview.isScheduledOn(it) && it >= today },
                            onDay = when (mode) {
                                Mode.DATES -> { d ->
                                    val k = d.toString()
                                    if (d >= today) dates = if (k in dates) dates - k else dates + k
                                }
                                Mode.INTERVAL -> { d -> anchor = d }
                                else -> null
                            }
                        )

                        Section(if (slots.size == 1) "Horario de la toma" else "Tomas del día (${slots.size})")
                        slots.forEachIndexed { idx, sl ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (slots.size > 1) Text("${idx + 1}.", fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                                OutlinedButton(onClick = {
                                    pickTime(ctx, sl.start) { picked ->
                                        val st = picked.coerceAtMost(LAST_START)
                                        val duration = (sl.end - sl.start).coerceAtLeast(60)
                                        var msg: String? = if (picked > LAST_START) "La hora de inicio máxima es ${fmt(LAST_START)}." else null
                                        var en = sl.end
                                        // Si el fin queda antes del inicio, se mueve para mantener la duración.
                                        if (en < st + MIN_RANGE) {
                                            en = (st + duration).coerceAtMost(LAST_MIN)
                                            msg = (msg?.plus(" ") ?: "") + "Hora de fin ajustada a las ${fmt(en)}."
                                        }
                                        slots[idx] = Slot(st, en)
                                        timeMsg = msg?.let { idx to it }
                                    }
                                }, modifier = Modifier.weight(1f)) { Text("Desde ${fmt(sl.start)}") }
                                OutlinedButton(onClick = {
                                    pickTime(ctx, sl.end) { picked ->
                                        if (picked < sl.start + MIN_RANGE) {
                                            timeMsg = idx to ("La hora de fin no puede ser anterior a la de inicio (${fmt(sl.start)}). " +
                                                "Elige una hora a partir de las ${fmt(sl.start + MIN_RANGE)}.")
                                        } else {
                                            slots[idx] = Slot(sl.start, picked)
                                            timeMsg = null
                                        }
                                    }
                                }, modifier = Modifier.weight(1f)) { Text("Hasta ${fmt(sl.end)}") }
                                if (slots.size > 1) TextButton(
                                    onClick = { slots.removeAt(idx); timeMsg = null },
                                    modifier = Modifier.width(44.dp)
                                ) { Text("✕", color = MaterialTheme.colorScheme.error) }
                            }
                            timeMsg?.takeIf { it.first == idx }?.let {
                                Text(it.second, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (slots.size < MAX_SLOTS) OutlinedButton(onClick = {
                            val last = slots.maxBy { it.end }
                            val st = (last.end + 60).coerceAtMost(LAST_START)
                            slots.add(Slot(st, (st + 60).coerceAtMost(LAST_MIN)))
                            timeMsg = null
                        }) { Text("+ Añadir toma") }
                        Hint(
                            if (slots.size == 1) "Si a las ${fmt(slots[0].end)} no has escaneado la pegatina, sonará la alarma."
                            else "Con la misma pegatina se registra la toma que esté en curso. Cada una tiene su propia alarma al acabar su franja. Las franjas no pueden solaparse."
                        )
                    }

                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }

                    if (initial != null) {
                        TextButton(onClick = {
                            if (!confirmDelete) confirmDelete = true
                            else {
                                AlarmScheduler.cancel(ctx, initial.id)
                                for (k in 0 until MAX_SLOTS) AlarmService.stop(Occ.key(initial.id, k))
                                MedRepo.delete(ctx, initial.id)
                                MedRepo.log(ctx, "Eliminado: ${initial.name}")
                                onDismiss()
                            }
                        }) {
                            Text(
                                if (confirmDelete) "Pulsa otra vez para eliminar" else "Eliminar medicamento",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun Section(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

@Composable
private fun DayBubble(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.aspectRatio(1f).clip(CircleShape)
            .background(if (on) c.primary else c.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (on) c.onPrimary else c.onSurfaceVariant, fontWeight = FontWeight.Bold)
    }
}

/** Calendario mensual (lunes primero). [marked] = días con toma; [onDay] null = solo lectura. */
@Composable
fun MonthCalendar(
    month: YearMonth,
    onMonth: (YearMonth) -> Unit,
    marked: (LocalDate) -> Boolean,
    onDay: ((LocalDate) -> Unit)?,
) {
    val c = MaterialTheme.colorScheme
    val today = LocalDate.now()
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, c.outlineVariant), MaterialTheme.shapes.medium).padding(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onMonth(month.minusMonths(1)) }, enabled = month > YearMonth.now()) { Text("‹", style = MaterialTheme.typography.titleLarge) }
            Text(
                month.month.getDisplayName(TextStyle.FULL_STANDALONE, ES).replaceFirstChar { it.uppercase() } + " ${month.year}",
                Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall
            )
            TextButton(onClick = { onMonth(month.plusMonths(1)) }) { Text("›", style = MaterialTheme.typography.titleLarge) }
        }
        Row {
            WEEKDAY_LETTERS.forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = c.outline)
            }
        }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val days = month.lengthOfMonth()
        val rows = (offset + days + 6) / 7
        for (r in 0 until rows) {
            Row {
                for (col in 0 until 7) {
                    val dayNum = r * 7 + col - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (dayNum in 1..days) {
                            val d = month.atDay(dayNum)
                            val on = marked(d)
                            val past = d < today
                            var m = Modifier.fillMaxSize().clip(CircleShape)
                            if (on) m = m.background(c.primary)
                            if (d == today) m = m.border(2.dp, c.tertiary, CircleShape)
                            if (onDay != null && !past) m = m.clickable { onDay(d) }
                            Box(m, contentAlignment = Alignment.Center) {
                                Text(
                                    "$dayNum",
                                    color = when {
                                        on -> c.onPrimary
                                        past -> c.outline.copy(alpha = 0.5f)
                                        else -> c.onSurface
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SoundDialog(act: MainActivity, onDismiss: () -> Unit) {
    val isDefault = AlarmSound.uri(act) == null
    var maxMin by remember { mutableStateOf(AlarmSettings.maxMinutes(act).toString()) }
    var every by remember { mutableStateOf(AlarmSettings.repeatEvery(act).toString()) }
    var times by remember { mutableStateOf(AlarmSettings.repeatTimes(act).toString()) }
    val dnd = act.dndAccess

    fun num(v: String) = v.filter(Char::isDigit).take(4)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustes de la alarma") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Section("Sonido")
                Text("Actual: ${act.soundName}", style = MaterialTheme.typography.bodySmall)
                SoundOption("Pitido estridente (por defecto)", isDefault) {
                    AlarmSound.set(act, null, null); act.refreshSound()
                }
                SoundOption("Tono de alarma del móvil…", false) { act.pickRingtone() }
                SoundOption("Archivo de audio…", false) { act.pickAudioFile() }

                Spacer(Modifier.height(8.dp))
                Section("Duración")
                NumberField(maxMin, { maxMin = num(it) }, "Tiempo máximo sonando", "min")
                Hint("0 = suena sin límite hasta escanear la pegatina.")
                if ((maxMin.toIntOrNull() ?: 0) > 0) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(every, { every = num(it) }, "Repetir cada", "min", Modifier.weight(1f))
                        NumberField(times, { times = num(it) }, "Veces", "", Modifier.weight(1f))
                    }
                    Hint(
                        "Si nadie la apaga, vuelve a sonar pasado ese tiempo. Al acabar las repeticiones " +
                            "la toma queda como «no tomada» (0 = no repetir)."
                    )
                }

                Spacer(Modifier.height(8.dp))
                Section("Probar")
                Hint("Guarda estos ajustes y hace sonar la alarma. Se para con «Detener prueba» o acercando cualquier pegatina. No afecta a ninguna toma.")
                OutlinedButton(onClick = {
                    AlarmSettings.set(act, maxMin.toIntOrNull() ?: 0, every.toIntOrNull() ?: 0, times.toIntOrNull() ?: 0)
                    onDismiss()
                    AlarmService.startTest(act)
                }) { Text("🔔 Probar alarma") }

                Spacer(Modifier.height(8.dp))
                Section("No molestar")
                if (dnd) Hint("✔ La alarma sonará aunque esté activado No molestar.")
                else {
                    Hint("Para que suene aunque tengas No molestar con las alarmas bloqueadas, da acceso a MediTag.")
                    TextButton(onClick = { act.requestDndAccess() }) { Text("Permitir acceso a No molestar") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AlarmSettings.set(act, maxMin.toIntOrNull() ?: 0, every.toIntOrNull() ?: 0, times.toIntOrNull() ?: 0)
                onDismiss()
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String, suffix: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) },
        suffix = { if (suffix.isNotEmpty()) Text(suffix) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun SoundOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

/** Rango mínimo entre inicio y fin (min). */
private const val MIN_RANGE = 5
private const val LAST_MIN = 23 * 60 + 59
private const val LAST_START = LAST_MIN - MIN_RANGE

fun pickTime(ctx: Context, current: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPicked(h * 60 + m) }, current / 60, current % 60, true).show()
}

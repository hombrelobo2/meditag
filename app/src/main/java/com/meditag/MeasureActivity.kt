package com.meditag

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Formulario para apuntar una medición (tensión, saturación, glucemia, peso, temperatura).
 * La toma cuenta como hecha al pulsar Guardar; si se cierra sin guardar, no se registra nada
 * (y la alarma, si estaba sonando, sigue).
 */
class MeasureActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_SLOT = "slot"

        fun intent(ctx: Context, itemId: Int, slot: Int): Intent =
            Intent(ctx, MeasureActivity::class.java)
                .putExtra(EXTRA_ITEM, itemId).putExtra(EXTRA_SLOT, slot)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Abre el formulario; para entrada manual, elige la toma igual que al escanear. */
        fun openManual(ctx: Context, med: Medication) {
            val slot = DoseManager.pickSlot(med, java.time.LocalDateTime.now())?.first ?: -1
            ctx.startActivity(intent(ctx, med.id, slot))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Se puede rellenar desde la alarma, sobre la pantalla de bloqueo.
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        val med = MedRepo.get(this, intent.getIntExtra(EXTRA_ITEM, -1))
        if (med == null) { finish(); return }
        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        setContent { MediTagTheme { Form(med, slot) } }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Form(med: Medication, slot: Int) {
        val m = med.measures.ifEmpty { setOf(Measure.BP) }
        var sys by remember { mutableStateOf("") }
        var dia by remember { mutableStateOf("") }
        var pulse by remember { mutableStateOf("") }
        var spo2 by remember { mutableStateOf("") }
        var glucose by remember { mutableStateOf("") }
        var gctx by remember { mutableStateOf(GLUCOSE_CONTEXTS[0]) }
        var weight by remember { mutableStateOf("") }
        var temp by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var warnings by remember { mutableStateOf<List<String>>(emptyList()) }
        val first = remember { FocusRequester() }
        val hasPulse = Measure.BP in m || Measure.SPO2 in m

        fun int(v: String) = v.trim().toIntOrNull()
        fun dbl(v: String) = v.trim().replace(',', '.').toDoubleOrNull()

        fun build(): Reading? {
            val missing = buildList {
                if (Measure.BP in m && (int(sys) == null || int(dia) == null)) add("tensión (alta y baja)")
                if (Measure.SPO2 in m && int(spo2) == null) add("saturación")
                if (Measure.GLUCOSE in m && int(glucose) == null) add("glucemia")
                if (Measure.WEIGHT in m && dbl(weight) == null) add("peso")
                if (Measure.TEMP in m && dbl(temp) == null) add("temperatura")
            }
            if (pulse.isNotBlank() && int(pulse) == null) { error = "El pulso debe ser un número entero."; return null }
            if (missing.isNotEmpty()) { error = "Falta: ${missing.joinToString()}."; return null }
            error = null
            return Reading(
                ts = System.currentTimeMillis(),
                sys = if (Measure.BP in m) int(sys) else null,
                dia = if (Measure.BP in m) int(dia) else null,
                pulse = if (hasPulse) int(pulse) else null,
                spo2 = if (Measure.SPO2 in m) int(spo2) else null,
                glucose = if (Measure.GLUCOSE in m) int(glucose) else null,
                glucoseCtx = if (Measure.GLUCOSE in m) gctx else null,
                weight = if (Measure.WEIGHT in m) dbl(weight) else null,
                temp = if (Measure.TEMP in m) dbl(temp) else null,
                note = note.trim(),
            )
        }

        fun save(r: Reading) {
            DoseManager.saveMeasurement(this, med, slot, r)
            Toast.makeText(this, "✔ Guardado: ${r.summary()}", Toast.LENGTH_LONG).show()
            finish()
        }

        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { finish() }) { Text("Cancelar") }
                    Text(
                        "${Kind.MEASUREMENT.icon} ${med.name}",
                        Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium
                    )
                    Button(onClick = {
                        val r = build() ?: return@Button
                        val w = unusual(r)
                        if (w.isEmpty()) save(r) else warnings = w
                    }) { Text("Guardar") }
                }
                HorizontalDivider()
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        if (slot in med.slots.indices) "Toma de ${med.slots[slot].label}" else "Medición fuera de horario (se guarda igual)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    var focusUsed = false
                    fun mod(): Modifier = if (!focusUsed) { focusUsed = true; Modifier.fillMaxWidth().focusRequester(first) } else Modifier.fillMaxWidth()

                    if (Measure.BP in m) {
                        Section("Tensión arterial")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NumField(sys, { sys = it }, "Alta (sistólica)", "mmHg", mod().weight(1f))
                            NumField(dia, { dia = it }, "Baja (diastólica)", "mmHg", Modifier.weight(1f))
                        }
                    }
                    if (Measure.SPO2 in m) {
                        Section("Saturación de oxígeno")
                        NumField(spo2, { spo2 = it }, "SatO₂", "%", mod())
                    }
                    if (hasPulse) {
                        NumField(pulse, { pulse = it }, "Pulso (opcional)", "lpm", mod())
                    }
                    if (Measure.GLUCOSE in m) {
                        Section("Glucemia")
                        NumField(glucose, { glucose = it }, "Glucosa", "mg/dL", mod())
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            GLUCOSE_CONTEXTS.forEach { c ->
                                FilterChip(selected = gctx == c, onClick = { gctx = c }, label = { Text(c) })
                            }
                        }
                    }
                    if (Measure.WEIGHT in m) {
                        Section("Peso")
                        NumField(weight, { weight = it }, "Peso", "kg", mod(), decimal = true)
                    }
                    if (Measure.TEMP in m) {
                        Section("Temperatura")
                        NumField(temp, { temp = it }, "Temperatura", "°C", mod(), decimal = true)
                    }
                    OutlinedTextField(
                        value = note, onValueChange = { note = it.take(200) },
                        label = { Text("Nota (opcional)") }, placeholder = { Text("p.ej. después de caminar") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
                    Text(
                        "La app solo guarda los valores; no los interpreta. Para saber si están bien, consulta a tu médico.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        if (warnings.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { warnings = emptyList() },
                title = { Text("¿Están bien escritos?") },
                text = {
                    Text(
                        "Estos valores son poco habituales y podría ser un error al teclear:\n\n• " +
                            warnings.joinToString("\n• ") + "\n\n¿Guardar igualmente?"
                    )
                },
                confirmButton = {
                    TextButton(onClick = { build()?.let { save(it) }; warnings = emptyList() }) { Text("Guardar igualmente") }
                },
                dismissButton = { TextButton(onClick = { warnings = emptyList() }) { Text("Corregir") } }
            )
        }
    }

    /** Solo para detectar erratas al teclear (no son rangos médicos ni se guardan). */
    private fun unusual(r: Reading): List<String> = buildList {
        r.sys?.let { if (it !in 60..260) add("Tensión alta: $it mmHg") }
        r.dia?.let { if (it !in 30..160) add("Tensión baja: $it mmHg") }
        if (r.sys != null && r.dia != null && r.dia >= r.sys) add("La baja (${r.dia}) es mayor o igual que la alta (${r.sys})")
        r.pulse?.let { if (it !in 30..220) add("Pulso: $it lpm") }
        r.spo2?.let { if (it !in 50..100) add("Saturación: $it %") }
        r.glucose?.let { if (it !in 20..600) add("Glucemia: $it mg/dL") }
        r.weight?.let { if (it !in 2.0..300.0) add("Peso: ${dec(it)} kg") }
        r.temp?.let { if (it !in 34.0..43.0) add("Temperatura: ${dec(it)} °C") }
    }
}

@Composable
private fun Section(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
private fun NumField(
    value: String, onChange: (String) -> Unit, label: String, suffix: String,
    modifier: Modifier = Modifier, decimal: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || (decimal && (it == ',' || it == '.')) }.take(6)) },
        singleLine = true,
        label = { Text(label) },
        suffix = { Text(suffix) },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
            imeAction = ImeAction.Next
        ),
        modifier = modifier
    )
}

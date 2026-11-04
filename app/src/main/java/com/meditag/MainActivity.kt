package com.meditag

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.delay
import java.time.LocalDateTime

data class Issue(val text: String, val action: String, val onClick: () -> Unit)

class MainActivity : ComponentActivity() {
    private var nfc: NfcAdapter? = null

    /** Si no es null, la próxima etiqueta leída se asigna al medicamento en edición. */
    var tagCapture: ((String) -> Unit)? by mutableStateOf<((String) -> Unit)?>(null)
    var issues: List<Issue> by mutableStateOf<List<Issue>>(emptyList())
        private set

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshIssues() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfc = NfcAdapter.getDefaultAdapter(this)
        AlarmScheduler.scheduleAll(this)
        if (savedInstanceState == null) handleNfcIntent(intent)
        setContent { MediTagTheme { MainScreen(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshIssues()
        nfc?.enableReaderMode(this, { tag -> onTag(Nfc.tagId(tag)) }, Nfc.READER_FLAGS, null)
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    /** Etiqueta escaneada con la app cerrada o en segundo plano. */
    private fun handleNfcIntent(intent: Intent?) {
        val a = intent?.action ?: return
        if (a != NfcAdapter.ACTION_TECH_DISCOVERED && a != NfcAdapter.ACTION_TAG_DISCOVERED) return
        val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java) ?: return
        onTag(Nfc.tagId(tag))
    }

    private fun onTag(id: String) = runOnUiThread {
        val capture = tagCapture
        if (capture != null) {
            capture(id)
            tagCapture = null
            Toast.makeText(this, "Etiqueta leída: $id", Toast.LENGTH_SHORT).show()
        } else {
            val r = DoseManager.onTagScanned(this, id)
            Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("BatteryLife")
    fun refreshIssues() {
        val list = mutableListOf<Issue>()
        val adapter = nfc
        if (adapter == null) {
            list += Issue("Este móvil no tiene NFC.", "") {}
        } else if (!adapter.isEnabled) {
            list += Issue("El NFC está desactivado.", "Activar") {
                startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            list += Issue("Sin permiso de notificaciones: la alarma no podrá mostrarse.", "Permitir") {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= 34 &&
            !getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        ) {
            list += Issue("Permite mostrar la alarma a pantalla completa.", "Permitir") {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            }
        }
        if (!AlarmScheduler.canExact(this) && Build.VERSION.SDK_INT >= 31) {
            list += Issue("Permite las alarmas exactas para que suene a su hora.", "Permitir") {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            list += Issue("Quita la optimización de batería para que el sistema no bloquee la alarma.", "Quitar") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        issues = list
        AlarmScheduler.scheduleAll(this)
    }
}

// ---------------------------------------------------------------- UI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(act: MainActivity) {
    val meds by MedRepo.meds.collectAsState()
    val history by MedRepo.history.collectAsState()
    val ringing by AlarmService.ringing.collectAsState()
    var editor by remember { mutableStateOf<Medication?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var confirmSkip by remember { mutableStateOf<Medication?>(null) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = LocalDateTime.now() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("MediTag") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editor = null; showEditor = true }) { Text("+  Añadir") }
        }
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (ringing.isNotEmpty()) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFC40000))) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("¡Alarma sonando!", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Button(onClick = {
                            act.startActivity(Intent(act, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Abrir") }
                    }
                }
            }
            items(act.issues) { issue ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(issue.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        if (issue.action.isNotEmpty()) TextButton(onClick = issue.onClick) { Text(issue.action) }
                    }
                }
            }
            if (meds.isEmpty()) item {
                Text(
                    "Añade un medicamento, escanea la pegatina NFC que lleva pegada y elige su rango horario. " +
                        "Para registrar cada toma basta con acercar la pegatina al móvil.",
                    Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge
                )
            }
            items(meds, key = { it.id }) { med ->
                MedCard(
                    med = med, now = now,
                    onEdit = { editor = med; showEditor = true },
                    onSkip = { confirmSkip = med },
                    onToggle = { enabled ->
                        val m = med.copy(enabled = enabled)
                        MedRepo.upsert(act, m)
                        AlarmScheduler.schedule(act, m)
                        if (!enabled) AlarmService.stop(med.id)
                    },
                    onTest = { AlarmService.start(act, med.id) }
                )
            }
            if (history.isNotEmpty()) {
                item { Text("Historial", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
                items(history.take(30)) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    if (showEditor) EditorDialog(initial = editor, act = act, onDismiss = { showEditor = false })

    confirmSkip?.let { med ->
        AlertDialog(
            onDismissRequest = { confirmSkip = null },
            title = { Text("Cancelar toma de hoy") },
            text = { Text("No sonará la alarma de ${med.name} hoy. Quedará registrado en el historial.") },
            confirmButton = {
                TextButton(onClick = { DoseManager.skipToday(act, med); confirmSkip = null }) { Text("Cancelar toma") }
            },
            dismissButton = { TextButton(onClick = { confirmSkip = null }) { Text("Volver") } }
        )
    }
}

@Composable
fun MedCard(
    med: Medication, now: LocalDateTime,
    onEdit: () -> Unit, onSkip: () -> Unit, onToggle: (Boolean) -> Unit, onTest: () -> Unit,
) {
    val status = med.status(now)
    val (label, color) = when (status) {
        DoseStatus.PENDING -> "Pendiente · desde ${fmt(med.startMin)}" to Color(0xFF757575)
        DoseStatus.DUE -> "¡Toca ahora! Escanéala antes de las ${fmt(med.endMin)}" to Color(0xFFEF6C00)
        DoseStatus.OVERDUE -> "Fuera de plazo · alarma activa" to Color(0xFFC62828)
        DoseStatus.TAKEN -> "✔ Tomada hoy a las ${med.takenTimeText()}" to Color(0xFF2E7D32)
        DoseStatus.SKIPPED -> "Toma de hoy cancelada" to Color(0xFF757575)
        DoseStatus.DISABLED -> "Desactivado" to Color(0xFF9E9E9E)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(med.name, style = MaterialTheme.typography.titleLarge)
                    Text("Toma entre ${fmt(med.startMin)} y ${fmt(med.endMin)}", style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = med.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(6.dp))
            Text(label, color = color, fontWeight = FontWeight.SemiBold)
            Text("Etiqueta ${med.tagId}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (status == DoseStatus.PENDING || status == DoseStatus.DUE || status == DoseStatus.OVERDUE) {
                    OutlinedButton(onClick = onSkip) { Text("Cancelar toma") }
                }
                TextButton(onClick = onEdit) { Text("Editar") }
                TextButton(onClick = onTest) { Text("Probar alarma") }
            }
        }
    }
}

@Composable
fun EditorDialog(initial: Medication?, act: MainActivity, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var tag by remember { mutableStateOf(initial?.tagId ?: "") }
    var start by remember { mutableIntStateOf(initial?.startMin ?: (8 * 60)) }
    var end by remember { mutableIntStateOf(initial?.endMin ?: (10 * 60)) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val waiting = act.tagCapture != null

    DisposableEffect(Unit) { onDispose { act.tagCapture = null } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nuevo medicamento" else "Editar medicamento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nombre del medicamento") }, singleLine = true
                )
                Text("Etiqueta NFC", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (waiting) "Acerca la pegatina al móvil…" else tag.ifBlank { "Sin asignar" },
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium
                    )
                    if (waiting) TextButton(onClick = { act.tagCapture = null }) { Text("Cancelar") }
                    else Button(onClick = { act.tagCapture = { tag = it } }) { Text("Escanear") }
                }
                Text("Rango horario de la toma", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickTime(ctx, start) { start = it } }, modifier = Modifier.weight(1f)) {
                        Text("Desde ${fmt(start)}")
                    }
                    OutlinedButton(onClick = { pickTime(ctx, end) { end = it } }, modifier = Modifier.weight(1f)) {
                        Text("Hasta ${fmt(end)}")
                    }
                }
                Text(
                    "Si a las ${fmt(end)} no has escaneado la pegatina, sonará la alarma hasta que lo hagas.",
                    style = MaterialTheme.typography.bodySmall
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (initial != null) {
                    TextButton(onClick = {
                        if (!confirmDelete) confirmDelete = true
                        else {
                            AlarmScheduler.cancel(ctx, initial.id)
                            AlarmService.stop(initial.id)
                            MedRepo.delete(ctx, initial.id)
                            MedRepo.log(ctx, "Eliminado: ${initial.name}")
                            onDismiss()
                        }
                    }) {
                        Text(if (confirmDelete) "Pulsa otra vez para eliminar" else "Eliminar medicamento",
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    name.isBlank() -> error = "Escribe el nombre del medicamento"
                    tag.isBlank() -> error = "Escanea la pegatina NFC del medicamento"
                    end - start < 5 -> error = "La hora de fin debe ser al menos 5 min posterior a la de inicio"
                    else -> {
                        val base = initial ?: Medication(id = MedRepo.nextId(ctx), name = "", tagId = "", startMin = 0, endMin = 0)
                        val med = base.copy(name = name.trim(), tagId = tag, startMin = start, endMin = end)
                        MedRepo.upsert(ctx, med)
                        if (initial == null) MedRepo.log(ctx, "Añadido: ${med.name} (${fmt(start)}–${fmt(end)})")
                        AlarmScheduler.schedule(ctx, med)
                        onDismiss()
                    }
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

private fun pickTime(ctx: Context, current: Int, onPicked: (Int) -> Unit) {
    TimePickerDialog(ctx, { _, h, m -> onPicked(h * 60 + m) }, current / 60, current % 60, true).show()
}

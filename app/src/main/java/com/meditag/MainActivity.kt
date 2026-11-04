package com.meditag

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.app.Activity
import android.media.RingtoneManager
import android.provider.OpenableColumns
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    var soundName: String by mutableStateOf("")
        private set

    private val ringtonePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = res.data?.let {
                IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
            }
            if (uri == null) AlarmSound.set(this, null, null)
            else AlarmSound.set(this, uri, runCatching { RingtoneManager.getRingtone(this, uri)?.getTitle(this) }.getOrNull())
            refreshSound()
        }

    private val audioPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            AlarmSound.set(this, uri, name ?: "Archivo de audio")
            refreshSound()
        }

    fun pickRingtone() {
        ringtonePicker.launch(
            Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_RINGTONE)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Sonido de la alarma")
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, AlarmSound.uri(this))
        )
    }

    fun pickAudioFile() = audioPicker.launch(arrayOf("audio/*"))

    fun refreshSound() { soundName = AlarmSound.name(this) }

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
        refreshSound()
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
    var showSound by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = LocalDateTime.now() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MediTag") },
                actions = { TextButton(onClick = { showSound = true }) { Text("🔔 Sonido") } }
            )
        },
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
                    "Añade un medicamento, escanea la pegatina NFC que lleva pegada y elige su pauta (cada día, " +
                        "días de la semana, cada N días, fechas concretas o a demanda) y su rango horario. " +
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
    if (showSound) SoundDialog(act, onDismiss = { showSound = false })

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
        DoseStatus.NOT_TODAY -> {
            val next = med.nextScheduled(now.toLocalDate().plusDays(1))
            (if (next != null) "Hoy no toca · próxima: ${fmtDay(next)}" else "No quedan días programados") to Color(0xFF757575)
        }
        DoseStatus.ON_DEMAND -> {
            val n = med.todayCount()
            when {
                n > 0 -> "Hoy: $n ${if (n == 1) "toma" else "tomas"} · última a las ${med.takenTimeText()}"
                med.takenAt > 0 -> "Última toma: ${med.takenDateTimeText()}"
                else -> "Sin tomas registradas · escanea al tomarla"
            } to Color(0xFF00796B)
        }
        DoseStatus.DISABLED -> "Desactivado" to Color(0xFF9E9E9E)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(med.name, style = MaterialTheme.typography.titleLarge)
                    Text(med.describe(), style = MaterialTheme.typography.bodyMedium)
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
                if (med.mode != Mode.ON_DEMAND) TextButton(onClick = onTest) { Text("Probar alarma") }
            }
        }
    }
}

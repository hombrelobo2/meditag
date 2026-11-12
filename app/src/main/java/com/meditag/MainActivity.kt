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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

    /** Copia leída y pendiente de confirmar. */
    var pendingImport: Backup.Pending? by mutableStateOf<Backup.Pending?>(null)

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            val msg = runCatching { Backup.export(this, uri) }
                .getOrElse { "No se pudo exportar: ${it.message}" }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching { Backup.read(this, uri) }
                .onSuccess { pendingImport = it }
                .onFailure {
                    Toast.makeText(this, "Archivo no válido: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }

    fun exportBackup() = exportLauncher.launch(Backup.suggestedName())

    private val sheetLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
            if (uri == null) return@registerForActivityResult
            val o = pendingExport ?: ExportOptions(0, Kind.entries.toSet())
            val msg = runCatching { Export.write(this, uri, o) }
                .getOrElse { "No se pudo exportar: ${it.message}" }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

    /** Opciones elegidas en el diálogo, a la espera de que el usuario elija dónde guardar. */
    private var pendingExport: ExportOptions? = null

    fun exportReport(o: ExportOptions) {
        pendingExport = o
        sheetLauncher.launch(Export.fileName())
    }
    fun importBackup() = importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))

    fun confirmImport() {
        val p = pendingImport ?: return
        pendingImport = null
        Backup.apply(this, p)
        Toast.makeText(this, "Importados ${p.meds.size} medicamentos", Toast.LENGTH_LONG).show()
    }

    fun refreshSound() {
        soundName = AlarmSound.name(this)
        dndAccess = getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted
        Notifications.createChannels(this) // crea el canal "No molestar" si ya hay acceso
    }

    var dndAccess: Boolean by mutableStateOf(false)
        private set

    fun requestDndAccess() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
    }

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
            if (r.measureItem != null) startActivity(MeasureActivity.intent(this, r.measureItem, r.measureSlot))
            else Toast.makeText(this, r.message, Toast.LENGTH_LONG).show()
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(act: MainActivity) {
    val meds by MedRepo.meds.collectAsState()
    val history by MedRepo.history.collectAsState()
    val ringing by AlarmService.ringing.collectAsState()
    var editor by remember { mutableStateOf<Medication?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var confirmSkip by remember { mutableStateOf<Pair<Medication, Int>?>(null) }
    var confirmDisable by remember { mutableStateOf<Medication?>(null) }
    var showSound by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showBackup by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(20_000); now = LocalDateTime.now() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MediTag") },
                actions = { TextButton(onClick = { showSound = true }) { Text("🔔 Alarma") } }
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
                    "Añade un medicamento, complemento, medición (tensión, glucemia, peso…) o hábito. " +
                        "Escanea la pegatina NFC que lleva pegada y elige su pauta y sus horarios. " +
                        "Para registrarlo basta con acercar la pegatina al móvil.",
                    Modifier.padding(vertical = 24.dp), style = MaterialTheme.typography.bodyLarge
                )
            }
            items(meds, key = { it.id }) { med ->
                MedCard(
                    med = med, now = now,
                    onEdit = { editor = med; showEditor = true },
                    onSkip = { i -> confirmSkip = med to i },
                    onToggle = { enabled ->
                        // Desactivar pide confirmación (evita apagarlo por un toque sin querer)
                        if (enabled) setEnabled(act, med, true) else confirmDisable = med
                    },

                )
            }
            item {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OutlinedButton(onClick = { showHistory = true }) { OneLine("Historial (${history.size})") }
                    OutlinedButton(onClick = { showBackup = true }) { OneLine("Copia de seguridad") }
                    OutlinedButton(onClick = { showAbout = true }) { OneLine("Acerca de") }
                }
                Text(
                    "MediTag ${BuildConfig.VERSION_NAME}",
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.labelSmall, color = Color.Gray,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }

    if (showEditor) EditorDialog(initial = editor, act = act, onDismiss = { showEditor = false })
    if (showSound) SoundDialog(act, onDismiss = { showSound = false })
    if (showAbout) AboutDialog(act, onDismiss = { showAbout = false })
    if (showBackup) BackupDialog(act, onDismiss = { showBackup = false })
    if (showHistory) HistoryDialog(history, onExport = { showExport = true }, onDismiss = { showHistory = false })
    if (showExport) ExportDialog(onExport = { act.exportReport(it) }, onDismiss = { showExport = false })
    act.pendingImport?.let { p ->
        ImportConfirmDialog(p, onConfirm = { act.confirmImport() }, onDismiss = { act.pendingImport = null })
    }

    confirmDisable?.let { med ->
        ConfirmDisableDialog(
            med = med,
            onConfirm = { setEnabled(act, med, false); confirmDisable = null },
            onDismiss = { confirmDisable = null },
        )
    }

    confirmSkip?.let { (med, i) ->
        AlertDialog(
            onDismissRequest = { confirmSkip = null },
            title = { Text("Cancelar toma") },
            text = {
                Text(
                    "No sonará la alarma de ${med.name}" +
                        (if (med.multi) " de ${med.slots[i].label}" else "") +
                        " hoy. Quedará registrado en el historial."
                )
            },
            confirmButton = {
                TextButton(onClick = { DoseManager.skip(act, med, i); confirmSkip = null }) { Text("Cancelar toma") }
            },
            dismissButton = { TextButton(onClick = { confirmSkip = null }) { Text("Volver") } }
        )
    }
}

@Composable
fun OneLine(text: String) = Text(text, maxLines = 1, softWrap = false)

/** Texto y color del estado de la toma [i] de hoy. */
fun slotLine(med: Medication, i: Int, now: LocalDateTime): Pair<String, Color> {
    val slot = med.slots[i]
    val st = med.state(i)
    return when (med.slotStatus(i, now)) {
        DoseStatus.PENDING -> "Pendiente · desde ${fmt(slot.start)}" to Color(0xFF757575)
        DoseStatus.DUE -> "¡Toca ahora! Escanéala antes de las ${fmt(slot.end)}" to Color(0xFFEF6C00)
        DoseStatus.OVERDUE ->
            (if (med.repeatPending(i)) "Sin tomar · la alarma volverá a sonar a las ${hhmm(st.nextRepeatAt)}"
            else "Fuera de plazo · alarma activa") to Color(0xFFC62828)
        DoseStatus.TAKEN -> "✔ ${med.kind.doneState} hoy a las ${hhmm(st.takenAt)}" to Color(0xFF2E7D32)
        DoseStatus.SKIPPED -> "Cancelada hoy" to Color(0xFF757575)
        DoseStatus.MISSED -> (if (med.isMeasurement) "✖ No realizada · puedes medirte ahora" else "✖ No realizada · si lo haces tarde, escanea la pegatina") to Color(0xFFC62828)
        DoseStatus.INACTIVE -> "No cuenta hoy (su hora ya había pasado al guardarla)" to Color(0xFF9E9E9E)
        else -> "" to Color.Gray
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MedCard(
    med: Medication, now: LocalDateTime,
    onEdit: () -> Unit, onSkip: (Int) -> Unit, onToggle: (Boolean) -> Unit,
) {
    // Estado general (no depende de la toma)
    val general: Pair<String, Color>? = when {
        !med.enabled -> "Desactivado" to Color(0xFF9E9E9E)
        med.mode == Mode.ON_DEMAND -> {
            val n = med.todayCount()
            when {
                n > 0 -> "Hoy: $n ${if (n == 1) "vez" else "veces"} · última a las ${med.takenTimeText()}"
                med.takenAt > 0 -> "Última toma: ${med.takenDateTimeText()}"
                else -> "Sin tomas registradas · escanea al tomarla"
            } to Color(0xFF00796B)
        }
        !med.isScheduledOn(now.toLocalDate()) -> {
            val next = med.nextScheduled(now.toLocalDate().plusDays(1))
            (if (next != null) "Hoy no toca · próxima: ${fmtDay(next)}" else "No quedan días programados") to Color(0xFF757575)
        }
        else -> null
    }
    val open = if (general == null) med.currentOpenSlot(now) else null
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val readingsVersion by MedRepo.readingsVersion.collectAsState()
    val last = if (med.isMeasurement) remember(med.id, readingsVersion) { Store.lastReading(ctx, med.id) } else null
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${med.kind.icon} ${med.name}", style = MaterialTheme.typography.titleLarge)
                    Text(med.describe(), style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = med.enabled, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(6.dp))
            if (general != null) {
                Text(general.first, color = general.second, fontWeight = FontWeight.SemiBold)
            } else if (!med.multi) {
                val (t, c) = slotLine(med, 0, now)
                Text(t, color = c, fontWeight = FontWeight.SemiBold)
            } else {
                // Una línea por toma
                med.slots.indices.forEach { i ->
                    val (t, c) = slotLine(med, i, now)
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(
                            med.slots[i].label, Modifier.width(96.dp),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                        )
                        Text(t, color = c, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (med.isMeasurement) Text(
                last?.let { "Última: ${it.summary()} · ${it.time.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"))}" }
                    ?: "Sin mediciones todavía",
                style = MaterialTheme.typography.bodySmall
            )
            Text("Etiqueta ${med.tagId}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            // FlowRow: si no caben en una línea, los botones pasan a la siguiente en vez de partirse.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                if (med.isMeasurement && med.enabled) {
                    Button(onClick = { MeasureActivity.openManual(ctx, med) }) { OneLine("Añadir medición") }
                }
                if (open != null) {
                    OutlinedButton(onClick = { onSkip(open) }) {
                        OneLine(if (med.multi) "Cancelar toma de ${fmt(med.slots[open].start)}" else "Cancelar toma")
                    }
                }
                TextButton(onClick = onEdit) { OneLine("Editar") }
            }
        }
    }
}

fun setEnabled(act: MainActivity, med: Medication, enabled: Boolean) {
    val m = med.copy(enabled = enabled)
    MedRepo.upsert(act, m)
    AlarmScheduler.schedule(act, m)
    if (!enabled) for (i in med.slots.indices) AlarmService.stop(Occ.key(med.id, i))
    MedRepo.logItem(act, if (enabled) "Activado" else "Desactivado", med)
}

/** Confirmación para desactivar; si no se responde en 5 s, se cierra sin desactivar. */
@Composable
fun ConfirmDisableDialog(med: Medication, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    var left by remember(med.id) { mutableStateOf(5) }
    LaunchedEffect(med.id) {
        while (left > 0) { delay(1000); left-- }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Desactivar ${med.name}?") },
        text = {
            Text(
                "Mientras esté desactivado no habrá recordatorios ni alarma de este medicamento.\n\n" +
                    "Si no respondes, se cancela en $left s."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Desactivar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("No ($left)") } }
    )
}

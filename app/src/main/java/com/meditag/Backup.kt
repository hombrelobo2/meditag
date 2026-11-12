package com.meditag

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime

const val REPO_URL = "https://github.com/hombrelobo2/meditag"
/** Manual de uso en PDF (siempre la última versión publicada en el repositorio). */
const val MANUAL_URL = "$REPO_URL/raw/main/docs/MediTag-Guia-de-uso.pdf"
const val APK_URL = "$REPO_URL/releases/download/ultima/MediTag.apk"

/** Enlace al APK de una versión concreta, con su número en el nombre (MediTag-vX.Y.Z.apk). */
fun versionedApkUrl(version: String) = "$REPO_URL/releases/download/v$version/MediTag-v$version.apk"
private const val VERSION_URL = "$REPO_URL/releases/download/ultima/version.json"

/** Comprueba en GitHub la última versión publicada. Solo descarga un archivo pequeño; no envía datos. */
object UpdateCheck {
    sealed interface Result
    data class UpToDate(val name: String) : Result
    /** [url]: APK de esa versión, con su número en el nombre. */
    data class Available(val name: String, val url: String) : Result
    data class Failed(val reason: String) : Result

    suspend fun check(): Result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            var url = java.net.URL(VERSION_URL)
            var conn: java.net.HttpURLConnection? = null
            var hops = 0
            while (true) { // sigue redirecciones (GitHub redirige a su CDN)
                conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 10_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/json")
                }
                val code = conn!!.responseCode
                if (code in 300..399 && hops++ < 5) {
                    url = java.net.URL(url, conn!!.getHeaderField("Location")); conn!!.disconnect(); continue
                }
                if (code != 200) return@withContext Failed("respuesta $code de GitHub")
                break
            }
            val c = conn!!
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            val remoteCode = o.getInt("versionCode")
            val remoteName = o.optString("versionName", remoteCode.toString())
            val apkUrl = o.optString("apkUrl").ifBlank { versionedApkUrl(remoteName) }
            if (remoteCode > BuildConfig.VERSION_CODE) Available(remoteName, apkUrl) else UpToDate(BuildConfig.VERSION_NAME)
        } catch (e: java.io.IOException) {
            Failed("sin conexión")
        } catch (e: Exception) {
            Failed(e.message ?: "error desconocido")
        }
    }
}

/** Copia de seguridad en JSON: elementos (con pauta y estado), historial y mediciones. */
object Backup {
    private const val FORMAT = 2

    fun suggestedName() = "meditag-${LocalDate.now()}.json"

    fun export(ctx: Context, uri: Uri): String {
        val meds = JSONArray().apply { MedRepo.all(ctx).forEach { put(it.toJson()) } }
        val events = Store.events(ctx)
        val readings = Store.readings(ctx)
        val root = JSONObject()
            .put("app", "MediTag")
            .put("format", FORMAT)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("exportedAt", LocalDateTime.now().toString())
            .put("medications", meds)
            .put("events", JSONArray().apply { events.forEach { put(it.toJson()) } })
            .put("readings", JSONArray().apply { readings.forEach { put(it.toJson()) } })
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(root.toString(1).toByteArray()) }
        return "Exportados ${MedRepo.all(ctx).size} elementos, ${events.size} registros del historial y ${readings.size} mediciones"
    }

    /** Lee y valida el archivo; no cambia nada hasta llamar a [apply]. Acepta copias antiguas. */
    fun read(ctx: Context, uri: Uri): Pending {
        val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(text)
        require(root.optString("app") == "MediTag") { "El archivo no es una copia de MediTag" }
        val arr = root.getJSONArray("medications")
        val meds = (0 until arr.length()).map { Medication.fromJson(arr.getJSONObject(it)) }
        val events = root.optJSONArray("events")?.let { e -> (0 until e.length()).map { Event.fromJson(e.getJSONObject(it)) } }
            ?: (root.optJSONArray("history") ?: JSONArray()).let { h -> // formato 1: historial en texto
                (0 until h.length()).mapNotNull { MedRepo.parseLegacy(h.getString(it), meds) }
            }
        val readings = (root.optJSONArray("readings") ?: JSONArray()).let { r ->
            (0 until r.length()).map { Reading.fromJson(r.getJSONObject(it)) }
        }
        return Pending(meds, events, readings, root.optString("exportedAt").take(16).replace('T', ' '))
    }

    data class Pending(val meds: List<Medication>, val events: List<Event>, val readings: List<Reading>, val exportedAt: String)

    fun apply(ctx: Context, p: Pending) {
        MedRepo.all(ctx).forEach {
            AlarmScheduler.cancel(ctx, it.id)
            for (i in it.slots.indices) AlarmService.stop(Occ.key(it.id, i))
        }
        MedRepo.replaceAll(ctx, p.meds, p.events, p.readings)
        MedRepo.log(ctx, "Importada copia de seguridad (${p.meds.size} elementos, ${p.readings.size} mediciones)")
        AlarmScheduler.scheduleAll(ctx)
    }
}

@Composable
fun AboutDialog(act: MainActivity, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateCheck.Result?>(null) }
    var auto by remember { mutableStateOf(AutoUpdate.enabled(act)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Acerca de MediTag") },
        text = {
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Versión ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold)
                Text("Te ayuda a no olvidar la medicación y a comprobar que de verdad la has tomado, con pegatinas NFC.")
                Text("Qué puede hacer", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text(
                    listOf(
                        "💊🌿🩺✅ Medicamentos, complementos, mediciones y hábitos",
                        "🏷️ Registrar acercando el móvil a la pegatina NFC",
                        "🕒 Varias tomas al día con una sola pegatina",
                        "📅 Pautas: cada día, días de la semana, cada N días, fechas concretas o a demanda",
                        "🔔 Alarma que no para hasta registrarlo; sonido, duración y repeticiones a tu gusto",
                        "⚠️ Aviso de toma no realizada al coger el móvil",
                        "🩺 Tensión, saturación, glucemia, peso y temperatura",
                        "📊 Informes para el médico en Excel o PDF con gráficas",
                        "📌 Anclar elementos arriba de la lista",
                        "💾 Copia de seguridad y avisos de versiones nuevas",
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Software libre bajo licencia GNU GPL v3. Sin anuncios; tus datos se quedan en tu móvil.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Comprobar actualizaciones cada semana", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(checked = auto, onCheckedChange = { auto = it; AutoUpdate.setEnabled(act, it) })
                }
                OutlinedButton(
                    enabled = !checking,
                    onClick = {
                        checking = true; result = null
                        scope.launch { result = UpdateCheck.check(); checking = false }
                    }
                ) { Text(if (checking) "Comprobando…" else "Buscar actualizaciones") }
                when (val r = result) {
                    is UpdateCheck.UpToDate -> Text("✔ Tienes la última versión (${r.name}).", color = MaterialTheme.colorScheme.primary)
                    is UpdateCheck.Available -> {
                        Text("Hay una versión nueva: ${r.name}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Text(
                            "Descárgala e instálala encima: no se pierde nada. Por si acaso, haz antes una copia de seguridad.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Button(onClick = {
                            runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url))) }
                        }) { Text("Descargar ${r.name}") }
                    }
                    is UpdateCheck.Failed -> Text(
                        "No se pudo comprobar (${r.reason}). Revisa la conexión a internet.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                    )
                    null -> {}
                }
                TextButton(onClick = {
                    runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MANUAL_URL))) }
                }) { Text("📖 Manual de uso (PDF)") }
                TextButton(onClick = {
                    runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
                }) { Text("Código fuente en GitHub") }
                TextButton(onClick = {
                    runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$REPO_URL/issues"))) }
                }) { Text("Informar de un problema") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
    )
}

@Composable
fun BackupDialog(act: MainActivity, onDismiss: () -> Unit) {
    var auto by remember { mutableStateOf(AutoBackup.enabled(act)) }
    var days by remember { mutableStateOf(AutoBackup.days(act)) }
    var excel by remember { mutableStateOf(AutoBackup.withExcel(act)) }
    var keep by remember { mutableStateOf(AutoBackup.keep(act).toString()) }
    val folderName = act.backupFolderName
    val last = AutoBackup.lastRun(act)
    fun saveAuto() = AutoBackup.set(act, auto, days, excel, keep.toIntOrNull() ?: 10)

    AlertDialog(
        onDismissRequest = { saveAuto(); onDismiss() },
        title = { Text("Copia de seguridad") },
        text = {
            Column(
                Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Guarda en un archivo los elementos (con su pauta y su pegatina), el historial y las mediciones, o recupéralos desde uno.")
                TextButton(onClick = { onDismiss(); act.exportBackup() }) { Text("Exportar a archivo…") }
                TextButton(onClick = { onDismiss(); act.importBackup() }) { Text("Importar desde archivo…") }
                Text(
                    "Importar sustituye los elementos, el historial y las mediciones actuales. El sonido de alarma elegido no se incluye.",
                    style = MaterialTheme.typography.bodySmall
                )

                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Copia automática", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    androidx.compose.material3.Switch(checked = auto, onCheckedChange = { auto = it; saveAuto() })
                }
                Text(
                    "Guarda la copia sola, cada cierto tiempo, en una carpeta que elijas: por ejemplo una carpeta de Google Drive (aparece en el selector si tienes la app de Drive), OneDrive o del propio móvil. MediTag no inicia sesión en ningún sitio.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (auto) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Carpeta: " + (folderName ?: "sin elegir"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { act.pickBackupFolder() }) { Text(if (folderName == null) "Elegir…" else "Cambiar…") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1 to "Cada día", 7 to "Cada semana").forEach { (d, label) ->
                            androidx.compose.material3.FilterChip(
                                selected = days == d, onClick = { days = d; saveAuto() }, label = { Text(label) }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(checked = excel, onCheckedChange = { excel = it; saveAuto() })
                        Text("Incluir también el Excel para el médico", style = MaterialTheme.typography.bodyMedium)
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = keep, onValueChange = { v -> keep = v.filter { it.isDigit() }.take(3) },
                        label = { Text("Copias que se conservan") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Las más antiguas se borran solas.", style = MaterialTheme.typography.bodySmall)
                    if (last > 0) Text(
                        "Última copia: " + java.time.Instant.ofEpochMilli(last).atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) +
                            " · " + (AutoBackup.lastResult(act) ?: ""),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(enabled = folderName != null, onClick = {
                        saveAuto(); AutoBackup.runNow(act)
                        android.widget.Toast.makeText(act, "Haciendo la copia…", android.widget.Toast.LENGTH_SHORT).show()
                    }) { Text("Hacer una copia ahora") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { saveAuto(); onDismiss() }) { Text("Cerrar") } }
    )
}

@Composable
fun ImportConfirmDialog(p: Backup.Pending, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("¿Importar copia?") },
        text = {
            Text(
                "La copia${if (p.exportedAt.isNotBlank()) " del ${p.exportedAt}" else ""} contiene ${p.meds.size} elementos" +
                    (if (p.meds.isNotEmpty()) " (${p.meds.joinToString { it.name }})" else "") +
                    ", ${p.events.size} registros del historial y ${p.readings.size} mediciones.\n\nSe sustituirán los datos actuales."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Importar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

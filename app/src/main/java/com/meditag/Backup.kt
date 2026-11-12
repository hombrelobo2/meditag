package com.meditag

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
const val APK_URL = "$REPO_URL/releases/download/ultima/MediTag.apk"
private const val VERSION_URL = "$REPO_URL/releases/download/ultima/version.json"

/** Comprueba en GitHub la última versión publicada. Solo descarga un archivo pequeño; no envía datos. */
object UpdateCheck {
    sealed interface Result
    data class UpToDate(val name: String) : Result
    data class Available(val name: String) : Result
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
            if (remoteCode > BuildConfig.VERSION_CODE) Available(remoteName) else UpToDate(BuildConfig.VERSION_NAME)
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Acerca de MediTag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Versión ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold)
                Text("Control de la toma de medicación con etiquetas NFC.")
                Text("Software libre bajo licencia GNU GPL v3.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
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
                            runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(APK_URL))) }
                        }) { Text("Descargar ${r.name}") }
                    }
                    is UpdateCheck.Failed -> Text(
                        "No se pudo comprobar (${r.reason}). Revisa la conexión a internet.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                    )
                    null -> {}
                }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copia de seguridad") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Guarda en un archivo los elementos (con su pauta y su pegatina), el historial y las mediciones, o recupéralos desde uno.")
                TextButton(onClick = { onDismiss(); act.exportBackup() }) { Text("Exportar a archivo…") }
                TextButton(onClick = { onDismiss(); act.importBackup() }) { Text("Importar desde archivo…") }
                Text(
                    "Importar sustituye los elementos, el historial y las mediciones actuales. El sonido de alarma elegido no se incluye.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } }
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

/** Historial completo en pantalla aparte (la lista principal queda solo con los medicamentos). */
@Composable
fun HistoryDialog(history: List<String>, onExport: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Historial", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
                Text(
                    "Se guarda el último año (las mediciones, siempre). Puedes exportarlo a Excel para el médico.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                if (history.isNotEmpty()) TextButton(
                    onClick = onExport, modifier = Modifier.padding(horizontal = 8.dp)
                ) { Text("📄 Exportar para el médico") }
                HorizontalDivider()
                if (history.isEmpty()) {
                    Text("Todavía no hay nada registrado.", Modifier.padding(16.dp))
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(history) { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}

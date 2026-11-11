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

/** Copia de seguridad en JSON: medicamentos (con pauta y estado) + historial. */
object Backup {
    private const val FORMAT = 1

    fun suggestedName() = "meditag-${LocalDate.now()}.json"

    fun export(ctx: Context, uri: Uri): String {
        val meds = JSONArray().apply { MedRepo.all(ctx).forEach { put(it.toJson()) } }
        val root = JSONObject()
            .put("app", "MediTag")
            .put("format", FORMAT)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("exportedAt", LocalDateTime.now().toString())
            .put("medications", meds)
            .put("history", JSONArray(MedRepo.history.value))
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(root.toString(2).toByteArray()) }
        val n = MedRepo.all(ctx).size
        return "Exportados $n medicamentos y ${MedRepo.history.value.size} registros del historial"
    }

    /** Lee y valida el archivo; no cambia nada hasta llamar a [apply]. */
    fun read(ctx: Context, uri: Uri): Pending {
        val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = JSONObject(text)
        require(root.optString("app") == "MediTag") { "El archivo no es una copia de MediTag" }
        val arr = root.getJSONArray("medications")
        val meds = (0 until arr.length()).map { Medication.fromJson(arr.getJSONObject(it)) }
        val h = root.optJSONArray("history") ?: JSONArray()
        val history = (0 until h.length()).map { h.getString(it) }
        return Pending(meds, history, root.optString("exportedAt").take(16).replace('T', ' '))
    }

    data class Pending(val meds: List<Medication>, val history: List<String>, val exportedAt: String)

    fun sheetName() = "meditag-historial-${LocalDate.now()}.xlsx"

    /**
     * Historial como hoja de cálculo Excel (.xlsx), de más antiguo a más reciente.
     * Columnas: Fecha, Hora, Evento, Medicamento, Detalle.
     */
    fun exportSheet(ctx: Context, uri: Uri): String {
        val entries = MedRepo.history.value.reversed()
        val rows = entries.map { e ->
            val t = MedRepo.entryTime(e)
            val text = if (t != null) e.substringAfter(" · ") else e
            val event = text.substringBefore(": ", text)
            var med = if (": " in text) text.substringAfter(": ") else ""
            var detail = ""
            if (med.endsWith(")") && " (" in med) {
                detail = med.substringAfterLast(" (").removeSuffix(")")
                med = med.substringBeforeLast(" (")
            }
            listOf(
                t?.let { Xlsx.Date(it.toLocalDate()) } ?: Xlsx.Empty,
                t?.let { Xlsx.Time(it.toLocalTime().withSecond(0).withNano(0)) } ?: Xlsx.Empty,
                Xlsx.Text(event), Xlsx.Text(med), Xlsx.Text(detail),
            )
        }
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use {
            Xlsx.write(
                it, "Historial",
                header = listOf("Fecha", "Hora", "Evento", "Medicamento", "Detalle"),
                widths = listOf(12, 7, 38, 26, 34),
                rows = rows,
            )
        }
        return "Exportadas ${rows.size} entradas del historial"
    }

    fun apply(ctx: Context, p: Pending) {
        MedRepo.all(ctx).forEach {
            AlarmScheduler.cancel(ctx, it.id)
            for (i in it.slots.indices) AlarmService.stop(Occ.key(it.id, i))
        }
        MedRepo.replaceAll(ctx, p.meds, p.history)
        MedRepo.log(ctx, "Importada copia de seguridad (${p.meds.size} medicamentos)")
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
                Text("Guarda en un archivo los medicamentos (con su pauta y su pegatina) y el historial, o recupéralos desde uno.")
                TextButton(onClick = { onDismiss(); act.exportBackup() }) { Text("Exportar a archivo…") }
                TextButton(onClick = { onDismiss(); act.importBackup() }) { Text("Importar desde archivo…") }
                Text(
                    "Importar sustituye los medicamentos y el historial actuales. El sonido de alarma elegido no se incluye.",
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
                "La copia${if (p.exportedAt.isNotBlank()) " del ${p.exportedAt}" else ""} contiene ${p.meds.size} medicamentos" +
                    (if (p.meds.isNotEmpty()) " (${p.meds.joinToString { it.name }})" else "") +
                    " y ${p.history.size} registros del historial.\n\nSe sustituirán los datos actuales."
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
                    "Se guarda el último año. Puedes exportarlo a una hoja de cálculo para enseñárselo a tu médico.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                if (history.isNotEmpty()) TextButton(
                    onClick = onExport, modifier = Modifier.padding(horizontal = 8.dp)
                ) { Text("📄 Exportar a hoja de cálculo") }
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

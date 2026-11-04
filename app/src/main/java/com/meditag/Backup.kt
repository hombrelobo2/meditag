package com.meditag

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
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

    fun apply(ctx: Context, p: Pending) {
        MedRepo.all(ctx).forEach {
            AlarmScheduler.cancel(ctx, it.id)
            AlarmService.stop(it.id)
        }
        MedRepo.replaceAll(ctx, p.meds, p.history)
        MedRepo.log(ctx, "Importada copia de seguridad (${p.meds.size} medicamentos)")
        AlarmScheduler.scheduleAll(ctx)
    }
}

@Composable
fun AboutDialog(act: MainActivity, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Acerca de MediTag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Versión ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", fontWeight = FontWeight.SemiBold)
                Text("Control de la toma de medicación con etiquetas NFC.")
                Text("Software libre bajo licencia GNU GPL v3.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
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

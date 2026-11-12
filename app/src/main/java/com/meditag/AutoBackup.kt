package com.meditag

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Copia automática a una carpeta elegida por el usuario con el selector de Android
 * (por ejemplo, una carpeta de Google Drive, OneDrive o del propio móvil).
 * La app no inicia sesión en ningún sitio: escribe en la carpeta y el proveedor
 * (p. ej. la app de Drive) se encarga de subirla. Desactivada por defecto.
 */
object AutoBackup {
    private const val WORK = "auto-backup"
    private const val K_ON = "ab_enabled"
    private const val K_DIR = "ab_folder"
    private const val K_DAYS = "ab_days"
    private const val K_XLSX = "ab_xlsx"
    private const val K_KEEP = "ab_keep"
    private const val K_LAST = "ab_last"
    private const val K_RESULT = "ab_result"
    const val PREFIX_JSON = "meditag-copia-"
    const val PREFIX_XLSX = "meditag-seguimiento-"

    private fun p(ctx: Context) = MedRepo.prefs(ctx)

    fun enabled(ctx: Context) = p(ctx).getBoolean(K_ON, false)
    fun folder(ctx: Context): Uri? = p(ctx).getString(K_DIR, null)?.let(Uri::parse)
    fun days(ctx: Context) = p(ctx).getInt(K_DAYS, 1)
    fun withExcel(ctx: Context) = p(ctx).getBoolean(K_XLSX, true)
    fun keep(ctx: Context) = p(ctx).getInt(K_KEEP, 10)
    fun lastRun(ctx: Context) = p(ctx).getLong(K_LAST, 0L)
    fun lastResult(ctx: Context) = p(ctx).getString(K_RESULT, null)

    fun folderName(ctx: Context): String? = folder(ctx)?.let {
        runCatching { DocumentFile.fromTreeUri(ctx, it)?.name }.getOrNull() ?: "Carpeta elegida"
    }

    fun setFolder(ctx: Context, uri: Uri) {
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        p(ctx).edit().putString(K_DIR, uri.toString()).apply()
        schedule(ctx)
    }

    fun set(ctx: Context, enabled: Boolean, days: Int, excel: Boolean, keep: Int) {
        p(ctx).edit().putBoolean(K_ON, enabled).putInt(K_DAYS, days).putBoolean(K_XLSX, excel)
            .putInt(K_KEEP, keep.coerceIn(1, 100)).apply()
        schedule(ctx)
    }

    /** Programa (o cancela) la copia periódica según los ajustes. */
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        if (!enabled(ctx) || folder(ctx) == null) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<AutoBackupWorker>(days(ctx).toLong(), TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Hace una copia ahora mismo (en segundo plano). */
    fun runNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<AutoBackupWorker>().build())
    }

    /** Escribe la copia (y el Excel) en la carpeta y borra las más antiguas. Devuelve el resultado legible. */
    fun run(ctx: Context): Result<String> = runCatching {
        val dirUri = folder(ctx) ?: error("No hay carpeta elegida")
        val dir = DocumentFile.fromTreeUri(ctx, dirUri)
        if (dir == null || !dir.canWrite()) error("No se puede escribir en la carpeta. Vuelve a elegirla.")
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm"))

        val json = dir.createFile("application/json", "$PREFIX_JSON$stamp.json") ?: error("No se pudo crear el archivo")
        Backup.export(ctx, json.uri)
        var files = 1
        if (withExcel(ctx)) {
            val x = dir.createFile(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "$PREFIX_XLSX$stamp.xlsx"
            ) ?: error("No se pudo crear el Excel")
            Export.write(ctx, x.uri, ExportOptions(0, Kind.entries.toSet()))
            files++
        }
        // Conservar solo las N copias más recientes de cada tipo (el nombre lleva fecha y hora)
        val n = keep(ctx)
        listOf(PREFIX_JSON, PREFIX_XLSX).forEach { prefix ->
            dir.listFiles().filter { it.name?.startsWith(prefix) == true }
                .sortedByDescending { it.name }
                .drop(n)
                .forEach { runCatching { it.delete() } }
        }
        if (files == 1) "Copia guardada" else "Copia y Excel guardados"
    }.also { r ->
        p(ctx).edit()
            .putLong(K_LAST, System.currentTimeMillis())
            .putString(K_RESULT, r.fold({ "✔ $it" }, { "✖ ${it.message ?: "Error"}" }))
            .apply()
    }
}

class AutoBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        MedRepo.init(applicationContext)
        return if (AutoBackup.run(applicationContext).isSuccess) Result.success() else Result.retry()
    }
}

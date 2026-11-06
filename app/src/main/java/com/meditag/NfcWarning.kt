package com.meditag

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Vigila si el NFC está encendido mientras una pantalla (alarma / aviso) está abierta.
 * Llamar a [start] en onCreate y a [stop] en onDestroy; [refresh] en onResume.
 */
class NfcWatcher(private val activity: Activity) {
    // lazy: la actividad aún no tiene contexto cuando se crea este objeto
    private val adapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(activity) }

    /** false solo si el móvil tiene NFC y está apagado. */
    var enabled by mutableStateOf(true)
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    fun refresh() { enabled = adapter?.isEnabled ?: true }

    fun start() {
        refresh()
        ContextCompat.registerReceiver(
            activity, receiver, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun stop() { runCatching { activity.unregisterReceiver(receiver) } }

    /** Pide desbloquear (si hace falta) y abre los ajustes de NFC. */
    fun openSettings() {
        activity.getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(activity, null)
        runCatching { activity.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
            .onFailure { runCatching { activity.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }
    }
}

/** Aviso grande para las pantallas rojas: el NFC está apagado y la pegatina no se puede leer. */
@Composable
fun NfcOffBanner(watcher: NfcWatcher, accent: Color) {
    if (watcher.enabled) return
    Column(
        Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "⚠ El NFC está apagado",
            color = accent, fontSize = 20.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center
        )
        Text(
            "Así no se puede leer la pegatina. Actívalo para poder registrar la toma.",
            color = Color(0xFF222222), fontSize = 15.sp, textAlign = TextAlign.Center
        )
        Button(
            onClick = { watcher.openSettings() },
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White)
        ) { Text("Activar NFC", fontWeight = FontWeight.Bold) }
    }
}

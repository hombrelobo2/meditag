package com.meditag

import android.app.KeyguardManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

/**
 * Aviso de toma sin respuesta. Se abre (sin sonido ni encender la pantalla) encima de la
 * pantalla de bloqueo, de modo que es lo primero que se ve al coger el móvil.
 * Desde aquí se puede escanear la pegatina para registrar la toma con retraso.
 */
class MissedActivity : ComponentActivity() {
    private var nfc: NfcAdapter? = null
    private var message by mutableStateOf<String?>(null)
    private val nfcWatcher = NfcWatcher(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        nfc = NfcAdapter.getDefaultAdapter(this)
        nfcWatcher.start()
        setContent { MediTagTheme { Screen() } }
    }

    override fun onResume() {
        super.onResume()
        nfcWatcher.refresh()
        nfc?.enableReaderMode(this, { tag -> onTag(Nfc.tagId(tag)) }, Nfc.READER_FLAGS, null)
    }

    override fun onDestroy() {
        nfcWatcher.stop()
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        nfc?.disableReaderMode(this)
    }

    private fun onTag(id: String) = runOnUiThread {
        val r = DoseManager.onTagScanned(this, id)
        if (r.measureItem != null) startActivity(MeasureActivity.intent(this, r.measureItem, r.measureSlot))
        message = if (r.ok) r.message else "✖ ${r.message}"
    }

    @Composable
    private fun Screen() {
        val meds by MedRepo.meds.collectAsState()
        // Tomas de hoy sin tomar: con la alarma ya agotada o esperando a repetirse
        val missed = meds.flatMap { m ->
            m.slots.indices.filter {
                val s = m.slotStatus(it)
                s == DoseStatus.MISSED || (s == DoseStatus.OVERDUE && m.repeatPending(it))
            }.map { m to it }
        }
        // Cuando ya no queda ninguna pendiente (escaneada o cancelada), se cierra sola.
        LaunchedEffect(missed.isEmpty()) {
            if (missed.isEmpty()) { kotlinx.coroutines.delay(2500); finish() }
        }

        Box(Modifier.fillMaxSize().background(Color(0xFF8E1B10)).systemBarsPadding().padding(24.dp)) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("⚠", color = Color.White, fontSize = 64.sp)
                Text(
                    when {
                        missed.isEmpty() -> "Todo en orden"
                        missed.map { it.first.kind }.toSet().size == 1 -> missed.first().first.kind.missedTitle
                        else -> "Tienes cosas sin hacer"
                    },
                    color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center
                )
                missed.forEach { (m, i) ->
                    Text("${m.kind.icon} ${m.name}", color = Color(0xFFFFE082), fontSize = 26.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(
                        "Toma de ${m.slots[i].label}" +
                            if (m.repeatPending(i)) " · la alarma volverá a sonar a las ${hhmm(m.state(i).nextRepeatAt)}" else "",
                        color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center
                    )
                }
                if (missed.isNotEmpty()) Text(
                    "Si lo haces ahora, desbloquea el móvil y acerca la pegatina para registrarlo.",
                    color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center
                )
                NfcOffBanner(nfcWatcher, Color(0xFF8E1B10))
                message?.let {
                    Text(it, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                missed.filter { it.first.isMeasurement }.forEach { (m, i) ->
                    Button(
                        onClick = { startActivity(MeasureActivity.intent(this@MissedActivity, m.id, i)) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFE082), contentColor = Color(0xFF8E1B10))
                    ) { Text("Introducir valores de ${m.name}") }
                }
                if (missed.isNotEmpty()) Button(
                    onClick = { getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this@MissedActivity, null) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF8E1B10))
                ) { Text("Desbloquear para escanear") }
                OutlinedButton(
                    onClick = { finish() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Entendido") }
            }
        }
    }
}

package com.meditag

import android.app.KeyguardManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Pantalla de alarma a pantalla completa. Solo se quita escaneando la etiqueta (o cancelando la toma). */
class AlarmActivity : ComponentActivity() {
    private var nfc: NfcAdapter? = null
    private var message by mutableStateOf<String?>(null)
    private val nfcWatcher = NfcWatcher(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // La mayoría de móviles solo leen NFC desbloqueados: pedimos desbloquear.
        getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)

        nfc = NfcAdapter.getDefaultAdapter(this)
        nfcWatcher.start()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                message = "Escanea la etiqueta del medicamento para detener la alarma"
            }
        })
        setContent { MediTagTheme { AlarmScreen() } }
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
        message = if (r.ok) r.message else "✖ ${r.message}"
    }

    @Composable
    private fun AlarmScreen() {
        val ringing by AlarmService.ringing.collectAsState()
        val meds by MedRepo.meds.collectAsState()
        var confirm by remember { mutableStateOf<Medication?>(null) }

        LaunchedEffect(ringing) { if (ringing.isEmpty()) finish() }

        val list = meds.filter { it.id in ringing }
        val test = AlarmService.TEST_ID in ringing
        val flash = rememberInfiniteTransition(label = "flash")
        val bg by flash.animateColor(
            initialValue = Color(0xFFC40000),
            targetValue = Color.Black,
            animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
            label = "bg"
        )

        Box(Modifier.fillMaxSize().background(bg).systemBarsPadding().padding(24.dp)) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Text(
                    if (test && list.isEmpty()) "PRUEBA DE ALARMA" else "¡TOMA TU MEDICACIÓN!",
                    color = Color.White, fontSize = 34.sp,
                    fontWeight = FontWeight.Black, textAlign = TextAlign.Center
                )
                list.forEach {
                    Text(it.name, color = Color.Yellow, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                Text(
                    if (test && list.isEmpty())
                        "Acerca cualquier pegatina para comprobar que la lectura funciona, o pulsa «Detener prueba». No se registra ninguna toma."
                    else "Desbloquea el móvil y acerca la etiqueta NFC del medicamento a la parte trasera para detener la alarma.",
                    color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center
                )
                NfcOffBanner(nfcWatcher, Color(0xFFC40000))
                message?.let {
                    Text(it, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (test) OutlinedButton(
                    onClick = { AlarmService.stop(AlarmService.TEST_ID) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Detener prueba") }
                list.forEach { med ->
                    OutlinedButton(
                        onClick = { confirm = med },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) { Text("Cancelar toma de ${med.name}") }
                }
            }
        }

        confirm?.let { med ->
            AlertDialog(
                onDismissRequest = { confirm = null },
                title = { Text("¿Cancelar la toma?") },
                text = { Text("Se saltará la toma de hoy de ${med.name} y quedará registrado en el historial.") },
                confirmButton = {
                    TextButton(onClick = { DoseManager.cancelFromAlarm(this, med); confirm = null }) {
                        Text("Sí, cancelar toma")
                    }
                },
                dismissButton = { TextButton(onClick = { confirm = null }) { Text("No") } }
            )
        }
    }
}

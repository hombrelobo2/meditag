package com.meditag

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Servicio en primer plano que hace sonar una alarma desagradable
 * (onda cuadrada aguda alternando 2.9 / 3.5 kHz, volumen de alarma al máximo
 * y vibración continua) hasta que se escanee la etiqueta o se cancele la toma.
 */
class AlarmService : Service() {

    companion object {
        private const val ACTION_START = "com.meditag.START_ALARM"
        private val _ringing = MutableStateFlow<Set<Int>>(emptySet())
        /** IDs de medicamentos cuya alarma está sonando. */
        val ringing: StateFlow<Set<Int>> = _ringing.asStateFlow()

        fun start(ctx: Context, medId: Int) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, AlarmService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(AlarmScheduler.EXTRA_ID, medId)
            )
        }

        fun stop(medId: Int) = _ringing.update { it - medId }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var playing = false
    private var audioThread: Thread? = null
    private var savedVolume = -1
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // El sistema reinició el servicio: recuperamos las tomas fuera de plazo.
            val overdue = MedRepo.all(this).filter { it.status() == DoseStatus.OVERDUE }.map { it.id }
            _ringing.update { it + overdue }
        } else {
            val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
            if (MedRepo.get(this, id) != null) _ringing.update { it + id }
        }

        goForeground() // obligatorio siempre tras startForegroundService
        if (_ringing.value.isEmpty()) {
            shutdown()
            return START_NOT_STICKY
        }
        startRinging()
        launchAlarmScreen()

        if (!observing) {
            observing = true
            scope.launch {
                _ringing.collect { set -> if (set.isEmpty()) shutdown() else goForeground() }
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val names = MedRepo.all(this).filter { it.id in _ringing.value }
            .joinToString { it.name }.ifBlank { "Medicación" }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(
            this, Notifications.ALARM_NOTIF_ID, Notifications.alarmNotification(this, names), type
        )
    }

    private fun launchAlarmScreen() {
        // Puede bloquearse en segundo plano; la notificación full-screen lo cubre.
        try {
            startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }

    private fun startRinging() {
        if (playing) return
        playing = true

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "meditag:alarm")
            .apply { acquire(2 * 60 * 60 * 1000L) }

        val am = getSystemService(AudioManager::class.java)
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) { }

        audioThread = thread(name = "meditag-alarm-audio") { playHarshSound() }

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        @Suppress("DEPRECATION")
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 900, 250, 300, 250), 0), attrs)
    }

    private fun playHarshSound() {
        val rate = 44100
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(rate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minBuf, rate / 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Exception) { return }

        val pattern = buildPattern(rate)
        track.play()
        while (playing) {
            if (track.write(pattern, 0, pattern.size) < 0) break
        }
        try { track.stop() } catch (_: Exception) { }
        track.release()
    }

    /** Ráfagas de onda cuadrada (muy estridente) con una pausa corta. */
    private fun buildPattern(rate: Int): ShortArray {
        val amp: Short = 30000
        val out = ArrayList<Short>(rate * 2)
        fun square(freq: Int, ms: Int) {
            val n = rate * ms / 1000
            for (i in 0 until n) {
                val half = (i.toLong() * freq * 2 / rate) % 2
                out.add(if (half == 0L) amp else (-amp).toShort())
            }
        }
        fun silence(ms: Int) = repeat(rate * ms / 1000) { out.add(0) }
        repeat(5) { square(2900, 100); square(3500, 100) }
        silence(150)
        return out.toShortArray()
    }

    private fun stopRinging() {
        if (!playing) return
        playing = false
        try { audioThread?.join(500) } catch (_: InterruptedException) { }
        audioThread = null
        vibrator?.cancel()
        if (savedVolume >= 0) {
            try {
                getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: Exception) { }
            savedVolume = -1
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun shutdown() {
        stopRinging()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRinging()
        scope.cancel()
        super.onDestroy()
    }
}

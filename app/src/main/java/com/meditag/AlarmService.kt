package com.meditag

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
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
import kotlinx.coroutines.delay
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

        /** ID reservado para la alarma de prueba (no corresponde a ningún medicamento). */
        const val TEST_ID = 0

        fun startTest(ctx: Context) = start(ctx, TEST_ID)

        fun start(ctx: Context, medId: Int) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, AlarmService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(AlarmScheduler.EXTRA_ID, medId)
            )
        }

        fun stop(medId: Int) = _ringing.update { it - medId }

        /** Momento en que empezó a sonar cada medicamento (para la duración máxima). */
        private val ringSince = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var playing = false
    private var audioThread: Thread? = null
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observing = false
    private var savedFilter = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // El sistema reinició el servicio: recuperamos las tomas fuera de plazo.
            val overdue = MedRepo.all(this)
                .filter { it.status() == DoseStatus.OVERDUE && !it.repeatPending() }.map { it.id }
            _ringing.update { it + overdue }
        } else {
            val id = intent.getIntExtra(AlarmScheduler.EXTRA_ID, -1)
            if (id == TEST_ID || MedRepo.get(this, id) != null) _ringing.update { it + id }
        }

        val nowMs = System.currentTimeMillis()
        _ringing.value.forEach { ringSince.putIfAbsent(it, nowMs) }

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
                _ringing.collect { set ->
                    ringSince.keys.retainAll(set)
                    if (set.isEmpty()) shutdown() else goForeground()
                }
            }
            // Duración máxima: pasado ese tiempo sin respuesta, se para y se repite más tarde.
            scope.launch {
                while (true) {
                    delay(10_000)
                    val max = AlarmSettings.maxMinutes(this@AlarmService)
                    if (max <= 0) continue
                    val limit = System.currentTimeMillis() - max * 60_000L
                    ringSince.filterValues { it <= limit }.keys.forEach {
                        ringSince.remove(it)
                        DoseManager.alarmTimedOut(this@AlarmService, it)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun goForeground() {
        val names = (MedRepo.all(this).filter { it.id in _ringing.value }.map { it.name } +
            (if (TEST_ID in _ringing.value) listOf("Prueba de alarma") else emptyList()))
            .joinToString().ifBlank { "Medicación" }
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

        allowThroughDnd()
        val am = getSystemService(AudioManager::class.java)
        try {
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) { }

        // Sonido elegido por el usuario; si falla (archivo borrado, sin permiso…) se usa el pitido.
        val custom = AlarmSound.uri(this)
        if (custom == null || !playCustom(custom)) {
            audioThread = thread(name = "meditag-alarm-audio") { playHarshSound() }
        }

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

    /**
     * Si el usuario dio acceso a No molestar y el modo actual silenciaría las alarmas,
     * se cambia temporalmente a "solo alarmas" y se restaura al parar.
     */
    private fun allowThroughDnd() {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        val f = nm.currentInterruptionFilter
        val blocksAlarms = f == NotificationManager.INTERRUPTION_FILTER_NONE ||
            (f == NotificationManager.INTERRUPTION_FILTER_PRIORITY &&
                nm.notificationPolicy.priorityCategories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS == 0)
        if (!blocksAlarms) return
        try {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
            savedFilter = f
        } catch (_: Exception) { }
    }

    private fun restoreDnd() {
        if (savedFilter < 0) return
        try {
            getSystemService(NotificationManager::class.java).setInterruptionFilter(savedFilter)
        } catch (_: Exception) { }
        savedFilter = -1
    }

    private fun playCustom(uri: Uri): Boolean {
        val mp = MediaPlayer()
        return try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(this, uri)
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
            true
        } catch (_: Exception) {
            mp.release()
            false
        }
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
        player?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
        player = null
        vibrator?.cancel()
        if (savedVolume >= 0) {
            try {
                getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (_: Exception) { }
            savedVolume = -1
        }
        restoreDnd()
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

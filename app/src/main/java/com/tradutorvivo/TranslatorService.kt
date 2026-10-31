package com.tradutorvivo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Serviço em primeiro plano: captura o áudio, transcreve em streaming e traduz. */
class TranslatorService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_SOURCE = "source"      // "mic" ou "internal"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "tradutor"
        private const val NOTIF_ID = 1
        private const val RATE = 24_000

        @Volatile var isRunning = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val httpClient = wsClient.newBuilder()
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var running = false
    @Volatile private var transcriber: RealtimeTranscriber? = null
    private var translator: Translator? = null
    private var projection: MediaProjection? = null
    private var captureThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var source = "mic"
    private var retries = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (running) return
        source = intent.getStringExtra(EXTRA_SOURCE) ?: "mic"
        val internal = source == "internal"

        val notification = buildNotification(internal)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val type = if (internal)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, notification, type)
        } else if (internal) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("permissão de microfone negada"); return
        }

        if (internal) {
            val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
            if (data == null) { fail("permissão de captura não concedida"); return }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projection = mpm.getMediaProjection(code, data)?.also {
                it.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { main.post { shutdown() } }
                }, main)
            }
            if (projection == null) { fail("não foi possível iniciar a captura"); return }
        }

        val record = buildRecord(internal)
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            fail("não foi possível abrir o áudio"); return
        }

        val key = Prefs.apiKey(this)
        translator = Translator(httpClient, key, Prefs.mtModel(this))
        running = true
        isRunning = true
        retries = 0

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tradutor:audio").apply { acquire(4 * 60 * 60 * 1000L) }

        TranscriptStore.setStatus("Conectando…")
        connect()

        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ByteArray(RATE * 2 / 10) // 100 ms de áudio
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(buf, 0, buf.size)
                    if (n > 0) transcriber?.sendAudio(buf, n)
                }
            } catch (e: Exception) {
                TranscriptStore.setStatus("Erro no áudio: ${e.message}")
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "captura-audio").apply { start() }
    }

    private fun connect() {
        val key = Prefs.apiKey(this)
        val t = RealtimeTranscriber(wsClient, key, Prefs.sttModel(this), farField = source == "mic",
            listener = object : RealtimeTranscriber.Listener {
                override fun onReady() {
                    retries = 0
                    TranscriptStore.setStatus(if (source == "internal") "Ouvindo o áudio do celular… 🎧" else "Ouvindo pelo microfone… 🎙️")
                }
                override fun onSpeechStarted(itemId: String) = TranscriptStore.onSpeechStarted(itemId)
                override fun onDelta(itemId: String, delta: String) = TranscriptStore.onDelta(itemId, delta)
                override fun onCompleted(itemId: String, text: String) {
                    TranscriptStore.onCompleted(itemId, text)
                    translator?.translate(itemId, text)
                }
                override fun onError(message: String) = TranscriptStore.setStatus("Aviso da API: $message")
                override fun onClosed(reason: String?) {
                    if (!running) return
                    retries++
                    if (retries > 5) { main.post { fail(reason ?: "conexão perdida") }; return }
                    TranscriptStore.setStatus("Reconectando… (${reason ?: ""})")
                    main.postDelayed({ if (running) connect() }, 1500L * retries)
                }
            })
        transcriber = t
        t.connect()
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(internal: Boolean): AudioRecord? = try {
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = maxOf(min, RATE * 2 / 5) * 2
        val b = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(size)
        if (internal) {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            b.setAudioPlaybackCaptureConfig(cfg)
        } else {
            b.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        }
        b.build()
    } catch (e: Exception) { null }

    private fun fail(msg: String) {
        shutdown()
        TranscriptStore.setStatus("Parado — $msg")
    }

    private fun shutdown() {
        val wasRunning = running
        running = false
        isRunning = false
        transcriber?.close(); transcriber = null
        translator?.shutdown(); translator = null
        captureThread = null
        projection?.stop(); projection = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        main.removeCallbacksAndMessages(null)
        if (wasRunning) TranscriptStore.setStatus("Parado")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) shutdown()
        super.onDestroy()
    }

    private fun buildNotification(internal: Boolean): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tradução ao vivo", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, TranslatorService::class.java).setAction(ACTION_STOP), flags)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Tradutor ao vivo")
            .setContentText(if (internal) "Traduzindo o áudio do celular" else "Traduzindo pelo microfone")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Parar", stop)
            .build()
    }
}

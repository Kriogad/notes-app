package ru.notes.app

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlin.math.roundToInt

/**
 * Играет мелодию напоминания со своей громкостью: на время звонка выставляет громкость
 * будильника, потом возвращает как было. Уведомление напоминания служит уведомлением сервиса.
 */
class SoundService : Service() {
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopAll() }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopAll(remove = intent?.getBooleanExtra("remove", true) ?: true); return START_NOT_STICKY
        }
        val n = IntentCompat.getParcelableExtra(intent, "n", Notification::class.java)
        if (n == null) { stopAll(); return START_NOT_STICKY }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, intent.getIntExtra("id", 1), n, type)
        running = true
        play(intent.getStringExtra("sound")?.let(Uri::parse), intent.getIntExtra("volume", 80), intent.getBooleanExtra("loop", true))
        return START_NOT_STICKY
    }

    private fun play(sound: Uri?, volume: Int, loop: Boolean) {
        player?.release()
        val am = getSystemService(AudioManager::class.java)
        runCatching {
            if (savedVolume < 0) savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, (max * volume / 100f).roundToInt().coerceIn(1, max), 0)
        }
        fun start(uri: Uri) = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            setDataSource(this@SoundService, uri)
            isLooping = loop
            setOnCompletionListener { if (!loop) stopAll() }
            prepare()
            start()
        }
        // Если свой файл удалили — играем стандартный будильник
        player = runCatching { start(sound!!) }.getOrNull()
            ?: runCatching { start(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)) }.getOrNull()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 3 * 60_000L)
        if (player == null) stopAll()
    }

    /** remove — пользователь отреагировал, уведомление убрать; иначе (мелодия кончилась) — оставить. */
    private fun stopAll(remove: Boolean = false) {
        handler.removeCallbacks(timeout)
        player?.release()
        player = null
        if (savedVolume >= 0) {
            runCatching {
                getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            }
            savedVolume = -1
        }
        running = false
        ServiceCompat.stopForeground(
            this, if (remove) ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH
        )
        stopSelf()
    }

    override fun onDestroy() {
        if (player != null) {
            player?.release(); player = null
            if (savedVolume >= 0) runCatching {
                getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            }
        }
        running = false
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "ru.notes.app.STOP_SOUND"
        @Volatile private var running = false

        /** false — запустить не дали (например, телефон запретил), тогда звук даст само уведомление. */
        fun start(ctx: Context, id: Int, n: Notification, sound: Uri?, volume: Int, loop: Boolean): Boolean = runCatching {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, SoundService::class.java).putExtra("id", id).putExtra("n", n)
                    .putExtra("sound", sound?.toString()).putExtra("volume", volume).putExtra("loop", loop),
            )
        }.isSuccess

        fun stop(ctx: Context, remove: Boolean = true) {
            if (running) runCatching {
                ctx.startService(Intent(ctx, SoundService::class.java).setAction(ACTION_STOP).putExtra("remove", remove))
            }
        }
    }
}

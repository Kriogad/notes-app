package ru.notes.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

const val EXTRA_OPEN = "open_id"
const val EXTRA_NEW = "new_note"
const val EXTRA_ACK = "ack"

enum class Repeat(val key: String, val label: String) {
    NONE("", "Не повторять"),
    DAY("day", "Каждый день"),
    WEEK("week", "Каждую неделю"),
    MONTH("month", "Каждый месяц"),
    YEAR("year", "Каждый год");

    /** Следующий раз после [from], но не раньше чем сейчас. */
    fun next(from: Long): Long {
        val zone = ZoneId.systemDefault()
        var t = LocalDateTime.ofInstant(Instant.ofEpochMilli(from), zone)
        val now = LocalDateTime.now()
        do {
            t = when (this) {
                NONE -> return from
                DAY -> t.plusDays(1)
                WEEK -> t.plusWeeks(1)
                MONTH -> t.plusMonths(1)
                YEAR -> t.plusYears(1)
            }
        } while (!t.isAfter(now))
        return t.atZone(zone).toInstant().toEpochMilli()
    }

    companion object {
        fun of(key: String) = entries.find { it.key == key } ?: NONE
    }
}

/**
 * Два будильника на заметку: «по графику» (REMIND) и «напомнить снова / отложено» (NAG).
 * Пока напоминание не подтверждено (Выполнено или открыли заметку), NAG повторяет уведомление.
 */
object Reminders {
    private const val ACTION_REMIND = "ru.notes.app.REMIND"
    private const val ACTION_NAG = "ru.notes.app.NAG"
    private const val ACTION_DONE = "ru.notes.app.DONE"
    private const val ACTION_SNOOZE_10 = "ru.notes.app.SNOOZE10"
    private const val ACTION_SNOOZE_60 = "ru.notes.app.SNOOZE60"
    private const val EXTRA_ID = "id"
    private const val TEST_ID = 1

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("remind", Context.MODE_PRIVATE)

    // ---------- настройки ----------

    fun soundUri(ctx: Context): Uri? = prefs(ctx).getString("sound", null)?.let(Uri::parse)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun soundTitle(ctx: Context): String =
        runCatching { RingtoneManager.getRingtone(ctx, soundUri(ctx))?.getTitle(ctx) }.getOrNull() ?: "Стандартная"

    fun insistent(ctx: Context) = prefs(ctx).getBoolean("insistent", true)
    fun setInsistent(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("insistent", on).apply()

    /** Через сколько минут напомнить снова, если не отреагировал. 0 — не напоминать. */
    fun nagMinutes(ctx: Context) = prefs(ctx).getInt("nag", 15)
    fun setNagMinutes(ctx: Context, m: Int) = prefs(ctx).edit().putInt("nag", m).apply()

    /** Звук канала в Android менять нельзя — создаём новый канал. */
    fun setSound(ctx: Context, uri: Uri?) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(channelId(ctx))
        prefs(ctx).edit().putString("sound", uri?.toString()).putInt("chan", prefs(ctx).getInt("chan", 1) + 1).apply()
        createChannel(ctx)
    }

    private fun channelId(ctx: Context) = "remind_" + prefs(ctx).getInt("chan", 1)

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("reminders") // канал старых версий
        val ch = NotificationChannel(channelId(ctx), "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
            // Громкость будильника, а не тихих уведомлений
            setSound(
                soundUri(ctx),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    // ---------- будильники ----------

    private fun broadcast(ctx: Context, action: String, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarm(ctx: Context, action: String, id: Long, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = broadcast(ctx, action, id)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Ставит или снимает будильник по состоянию заметки. */
    fun sync(ctx: Context, note: Note) {
        val at = note.remindAt
        if (at == null || note.done) {
            cancel(ctx, note.id)
            return
        }
        if (at <= System.currentTimeMillis()) {
            // Повторяющееся пропущено (телефон был выключен) — переносим на следующий раз
            if (note.repeat != Repeat.NONE) Notes.upsert(note.copy(remindAt = note.repeat.next(at)))
            return
        }
        alarm(ctx, ACTION_REMIND, note.id, at)
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(broadcast(ctx, ACTION_REMIND, id))
        ack(ctx, id)
    }

    /** Напоминание увидели: больше не повторять, убрать уведомление. */
    fun ack(ctx: Context, id: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(broadcast(ctx, ACTION_NAG, id))
        setPending(ctx, id, false)
        NotificationManagerCompat.from(ctx).cancel(id.hashCode())
    }

    private fun pending(ctx: Context) = prefs(ctx).getStringSet("pending", emptySet())!!

    private fun setPending(ctx: Context, id: Long, on: Boolean) {
        val s = pending(ctx).toMutableSet()
        if (on) s += id.toString() else s -= id.toString()
        prefs(ctx).edit().putStringSet("pending", s).apply()
    }

    // ---------- уведомление ----------

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun builder(ctx: Context, title: String, text: String) = NotificationCompat.Builder(ctx, channelId(ctx))
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_REMINDER)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

    private fun post(ctx: Context, id: Int, b: NotificationCompat.Builder) {
        val n = b.build()
        if (insistent(ctx)) n.flags = n.flags or Notification.FLAG_INSISTENT // звенит, пока не нажмёшь
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    fun show(ctx: Context, note: Note) {
        if (!canNotify(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, note.id.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN, note.id).putExtra(EXTRA_ACK, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = note.text.ifBlank { note.items.filter { !it.checked }.joinToString(", ") { it.text } }
        post(
            ctx, note.id.hashCode(),
            builder(ctx, note.displayTitle, text)
                .setContentIntent(open)
                .addAction(0, "Выполнено", broadcast(ctx, ACTION_DONE, note.id))
                .addAction(0, "+10 мин", broadcast(ctx, ACTION_SNOOZE_10, note.id))
                .addAction(0, "+1 час", broadcast(ctx, ACTION_SNOOZE_60, note.id)),
        )
        setPending(ctx, note.id, true)
        val nag = nagMinutes(ctx)
        if (nag > 0) alarm(ctx, ACTION_NAG, note.id, System.currentTimeMillis() + nag * 60_000L)
    }

    /** Проверка звука из настроек. */
    fun test(ctx: Context): Boolean {
        if (!canNotify(ctx)) return false
        post(ctx, TEST_ID, builder(ctx, "Проверка напоминания", "Так будет звучать и выглядеть напоминание"))
        return true
    }

    fun handle(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val note = Notes.get(id) ?: return
        when (intent.action) {
            ACTION_REMIND -> {
                if (!note.done) show(ctx, note)
                if (note.repeat != Repeat.NONE && note.remindAt != null) {
                    Notes.upsert(note.copy(remindAt = note.repeat.next(note.remindAt)))
                }
            }
            ACTION_NAG -> if (!note.done && id.toString() in pending(ctx)) show(ctx, note)
            ACTION_DONE -> {
                ack(ctx, id)
                // Разовое — в архив; повторяющееся — ждём следующего раза
                if (note.repeat == Repeat.NONE) Notes.setDone(id, true)
            }
            ACTION_SNOOZE_10, ACTION_SNOOZE_60 -> {
                NotificationManagerCompat.from(ctx).cancel(id.hashCode())
                setPending(ctx, id, true)
                val min = if (intent.action == ACTION_SNOOZE_10) 10 else 60
                alarm(ctx, ACTION_NAG, id, System.currentTimeMillis() + min * 60_000L)
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Reminders.handle(context, intent)
}

/** После перезагрузки будильники сбрасываются системой — ставим заново. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notes.notes.value.forEach { Reminders.sync(context, it) }
    }
}

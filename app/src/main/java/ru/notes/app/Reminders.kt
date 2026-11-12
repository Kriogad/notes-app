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
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
const val EXTRA_ITEM = "item"

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

    /**
     * Свой MP3: копируем в системную папку «Alarms» — оттуда звук уведомления
     * гарантированно может проиграть сама система.
     */
    suspend fun importSound(ctx: Context, src: Uri): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val cr = ctx.contentResolver
            val name = cr.query(src, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "Мелодия заметок.mp3"
            if (Build.VERSION.SDK_INT < 29) return@runCatching src
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, cr.getType(src) ?: "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_ALARMS)
                put(MediaStore.Audio.Media.IS_ALARM, 1)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val dst = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
            cr.openInputStream(src)!!.use { i -> cr.openOutputStream(dst)!!.use { i.copyTo(it) } }
            cr.update(dst, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            dst
        }.getOrNull()
    }

    private fun channelId(ctx: Context) = "remind_" + prefs(ctx).getInt("chan", 1)

    fun titleOf(ctx: Context, sound: String?): String = if (sound == null) "Как в настройках" else
        runCatching { RingtoneManager.getRingtone(ctx, Uri.parse(sound))?.getTitle(ctx) }.getOrNull() ?: "Своя мелодия"

    /** У каждой своей мелодии — свой канал (звук канала Android менять не даёт). */
    private fun channelFor(ctx: Context, sound: String?): String {
        if (sound == null) return channelId(ctx)
        val id = "remind_snd_" + sound.hashCode().toUInt()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) nm.createNotificationChannel(channel(id, "Напоминания: " + titleOf(ctx, sound), Uri.parse(sound)))
        return id
    }

    private fun channel(id: String, name: String, sound: Uri?) =
        NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            // Громкость будильника, а не тихих уведомлений
            setSound(
                sound,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("reminders") // канал старых версий
        nm.createNotificationChannel(channel(channelId(ctx), "Напоминания", soundUri(ctx)))
    }

    // ---------- будильники ----------
    // Напоминание есть у заметки (item = 0) и у любого пункта (item = id пункта).
    // На каждое — два будильника: «по графику» (REMIND) и «напомнить снова / отложено» (NAG).

    private class Rem(
        val item: Long, val at: Long?, val repeat: Repeat, val done: Boolean,
        val title: String, val text: String, val sound: String?,
    )

    private fun rems(n: Note): List<Rem> {
        val main = Rem(0, n.remindAt, n.repeat, n.done, n.displayTitle,
            n.text.ifBlank { n.items.filter { !it.checked }.joinToString(", ") { it.text } }, n.sound)
        return listOf(main) + n.items.filter { it.remindAt != null }.map {
            Rem(it.id, it.remindAt, it.repeat, n.done || it.checked, it.text.ifBlank { n.displayTitle }, n.displayTitle, it.sound)
        }
    }

    private fun Note.withRemind(item: Long, at: Long?) =
        if (item == 0L) copy(remindAt = at)
        else copy(items = items.map { if (it.id == item) it.copy(remindAt = at) else it })

    private fun key(note: Long, item: Long) = "$note/$item"

    private fun intent(ctx: Context, action: String, note: Long, item: Long) =
        Intent(ctx, ReminderReceiver::class.java).setAction(action)
            .setData(Uri.parse("notes://rem/${key(note, item)}")) // своя «ссылка» у каждого напоминания
            .putExtra(EXTRA_ID, note).putExtra(EXTRA_ITEM, item)

    private fun broadcast(ctx: Context, action: String, note: Long, item: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, key(note, item).hashCode(), intent(ctx, action, note, item),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarm(ctx: Context, action: String, note: Long, item: Long, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = broadcast(ctx, action, note, item)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Ставит или снимает все будильники заметки и её пунктов. */
    fun sync(ctx: Context, note: Note) {
        val now = System.currentTimeMillis()
        val all = rems(note)
        // Повторяющиеся, которые пропущены (телефон был выключен), — сразу на следующий раз
        var moved = note
        all.forEach { r -> if (r.at != null && r.at <= now && !r.done && r.repeat != Repeat.NONE) moved = moved.withRemind(r.item, r.repeat.next(r.at)) }
        if (moved != note) { Notes.upsert(moved); return }

        all.forEach { r ->
            when {
                r.at == null || r.done -> cancel(ctx, note.id, r.item)
                r.at > now -> alarm(ctx, ACTION_REMIND, note.id, r.item, r.at)
                // прошедшее разовое не трогаем: возможно, ещё идут повторы «напомнить снова»
            }
        }
        // Пункты, которые удалили или у которых убрали напоминание
        val known = prefs(ctx).getStringSet("items_${note.id}", emptySet())!!
        val now_ = all.filter { it.item != 0L }.map { it.item.toString() }.toSet()
        (known - now_).forEach { cancel(ctx, note.id, it.toLong()) }
        prefs(ctx).edit().putStringSet("items_${note.id}", now_).apply()
    }

    fun cancelAll(ctx: Context, note: Note) {
        cancel(ctx, note.id, 0)
        note.items.forEach { cancel(ctx, note.id, it.id) }
        prefs(ctx).getStringSet("items_${note.id}", emptySet())!!.forEach { cancel(ctx, note.id, it.toLong()) }
        prefs(ctx).edit().remove("items_${note.id}").apply()
    }

    fun cancel(ctx: Context, note: Long, item: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(broadcast(ctx, ACTION_REMIND, note, item))
        ack(ctx, note, item)
    }

    /** Напоминание увидели: больше не повторять, убрать уведомление. */
    fun ack(ctx: Context, note: Long, item: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(broadcast(ctx, ACTION_NAG, note, item))
        setPending(ctx, key(note, item), false)
        NotificationManagerCompat.from(ctx).cancel(key(note, item).hashCode())
    }

    private fun pending(ctx: Context) = prefs(ctx).getStringSet("pending", emptySet())!!

    private fun setPending(ctx: Context, k: String, on: Boolean) {
        val s = pending(ctx).toMutableSet()
        if (on) s += k else s -= k
        prefs(ctx).edit().putStringSet("pending", s).apply()
    }

    // ---------- уведомление ----------

    private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun builder(ctx: Context, title: String, text: String, sound: String? = null) =
        NotificationCompat.Builder(ctx, channelFor(ctx, sound))
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

    private fun show(ctx: Context, note: Note, r: Rem) {
        if (!canNotify(ctx)) return
        val k = key(note.id, r.item)
        val open = PendingIntent.getActivity(
            ctx, k.hashCode(),
            Intent(ctx, MainActivity::class.java).setData(Uri.parse("notes://open/$k"))
                .putExtra(EXTRA_OPEN, note.id).putExtra(EXTRA_ITEM, r.item).putExtra(EXTRA_ACK, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(
            ctx, k.hashCode(),
            builder(ctx, r.title, r.text, r.sound)
                .setContentIntent(open)
                .addAction(0, "Выполнено", broadcast(ctx, ACTION_DONE, note.id, r.item))
                .addAction(0, "+10 мин", broadcast(ctx, ACTION_SNOOZE_10, note.id, r.item))
                .addAction(0, "+1 час", broadcast(ctx, ACTION_SNOOZE_60, note.id, r.item)),
        )
        setPending(ctx, k, true)
        val nag = nagMinutes(ctx)
        if (nag > 0) alarm(ctx, ACTION_NAG, note.id, r.item, System.currentTimeMillis() + nag * 60_000L)
    }

    /** Проверка звука из настроек. */
    fun test(ctx: Context): Boolean {
        if (!canNotify(ctx)) return false
        post(ctx, TEST_ID, builder(ctx, "Проверка напоминания", "Так будет звучать и выглядеть напоминание"))
        return true
    }

    fun handle(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val item = intent.getLongExtra(EXTRA_ITEM, 0)
        val note = Notes.get(id) ?: return
        val r = rems(note).find { it.item == item } ?: return
        when (intent.action) {
            ACTION_REMIND -> {
                if (!r.done) show(ctx, note, r)
                if (r.repeat != Repeat.NONE && r.at != null) {
                    // Заметка ждёт «Выполнено» за этот раз (due), а будильник — уже на следующий
                    val moved = note.withRemind(item, r.repeat.next(r.at))
                    Notes.upsert(if (item == 0L) moved.copy(due = true) else moved)
                }
            }
            ACTION_NAG -> if (!r.done && key(id, item) in pending(ctx)) show(ctx, note, r)
            ACTION_DONE -> {
                ack(ctx, id, item)
                // Заметка целиком: в архив (повторяющаяся — с новой копией на следующий раз).
                // Повторяющийся пункт просто ждёт следующего раза.
                if (item == 0L) Notes.setDone(id, true)
                else if (r.repeat == Repeat.NONE) {
                    Notes.upsert(note.copy(items = note.items.map { if (it.id == item) it.copy(checked = true) else it }))
                }
            }
            ACTION_SNOOZE_10, ACTION_SNOOZE_60 -> {
                NotificationManagerCompat.from(ctx).cancel(key(id, item).hashCode())
                setPending(ctx, key(id, item), true)
                val min = if (intent.action == ACTION_SNOOZE_10) 10 else 60
                alarm(ctx, ACTION_NAG, id, item, System.currentTimeMillis() + min * 60_000L)
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

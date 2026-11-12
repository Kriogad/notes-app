package ru.notes.app

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

const val EXTRA_OPEN = "open_id"
const val EXTRA_NEW = "new_note"

object Reminders {
    private const val CHANNEL = "reminders"
    private const val ACTION_REMIND = "ru.notes.app.REMIND"
    const val ACTION_DONE = "ru.notes.app.DONE"
    const val EXTRA_ID = "id"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH)
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun broadcast(ctx: Context, action: String, id: Long): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Ставит или снимает будильник в зависимости от состояния заметки. */
    fun sync(ctx: Context, note: Note) {
        val at = note.remindAt
        if (at == null || note.done || at <= System.currentTimeMillis()) {
            cancel(ctx, note.id, dismiss = note.done)
            return
        }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = broadcast(ctx, ACTION_REMIND, note.id)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, id: Long, dismiss: Boolean = true) {
        ctx.getSystemService(AlarmManager::class.java).cancel(broadcast(ctx, ACTION_REMIND, id))
        if (dismiss) NotificationManagerCompat.from(ctx).cancel(id.hashCode())
    }

    fun show(ctx: Context, note: Note) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            ctx, note.id.hashCode(),
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN, note.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val preview = note.text.ifBlank {
            note.items.filter { !it.checked }.joinToString(", ") { it.text }
        }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(note.displayTitle)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Выполнено", broadcast(ctx, ACTION_DONE, note.id))
            .build()
        NotificationManagerCompat.from(ctx).notify(note.id.hashCode(), n)
    }

    fun handle(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val note = Notes.get(id) ?: return
        when (intent.action) {
            ACTION_REMIND -> if (!note.done) show(ctx, note)
            ACTION_DONE -> Notes.setDone(id, true)
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

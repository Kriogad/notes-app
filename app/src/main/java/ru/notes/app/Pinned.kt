package ru.notes.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Список, закреплённый в шторке: пункты нажимаются прямо в уведомлении (удобно в магазине). */
object Pinned {
    private const val CHANNEL = "pinned"
    private const val ACTION_TOGGLE = "ru.notes.app.PIN_TOGGLE"
    private const val ACTION_UNPIN = "ru.notes.app.UNPIN"
    private const val MAX_ROWS = 8

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pinned", Context.MODE_PRIVATE)
    private fun ids(ctx: Context) = prefs(ctx).getStringSet("ids", emptySet())!!.mapNotNull { it.toLongOrNull() }.toSet()
    private fun nid(id: Long) = "pin/$id".hashCode()

    fun isPinned(ctx: Context, id: Long) = id in ids(ctx)

    fun set(ctx: Context, id: Long, on: Boolean) {
        val s = ids(ctx).toMutableSet()
        if (on) s += id else s -= id
        prefs(ctx).edit().putStringSet("ids", s.map { it.toString() }.toSet()).apply()
        if (!on) NotificationManagerCompat.from(ctx).cancel(nid(id))
        refresh(ctx)
    }

    private fun pi(ctx: Context, action: String, note: Long, item: Long) = PendingIntent.getBroadcast(
        ctx, "$action/$note/$item".hashCode(),
        Intent(ctx, PinReceiver::class.java).setAction(action).setData(Uri.parse("notes://pin/$note/$item"))
            .putExtra("note", note).putExtra("item", item),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Перерисовать закреплённые (после любого изменения заметок). */
    fun refresh(ctx: Context) {
        val pinned = ids(ctx)
        if (pinned.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Закреплённые списки", NotificationManager.IMPORTANCE_LOW))
        for (id in pinned) {
            val n = Notes.get(id)
            if (n == null || n.done) { set(ctx, id, false); continue }
            val open = PendingIntent.getActivity(
                ctx, nid(id), Intent(ctx, MainActivity::class.java).setData(Uri.parse("notes://pinopen/$id")).putExtra(EXTRA_OPEN, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val todo = n.items.filter { !it.checked && it.text.isNotBlank() }
            val doneCount = n.items.count { it.checked }
            val big = RemoteViews(ctx.packageName, R.layout.pin_list)
            todo.take(MAX_ROWS).forEach { item ->
                val row = RemoteViews(ctx.packageName, R.layout.pin_row)
                row.setTextViewText(R.id.text, "☐  " + item.text)
                row.setOnClickPendingIntent(R.id.text, pi(ctx, ACTION_TOGGLE, id, item.id))
                big.addView(R.id.rows, row)
            }
            val more = listOfNotNull(
                (todo.size - MAX_ROWS).takeIf { it > 0 }?.let { "ещё $it" },
                doneCount.takeIf { it > 0 }?.let { "✓ отмечено $it" },
                "Всё куплено 🎉".takeIf { todo.isEmpty() && n.items.isNotEmpty() },
                n.text.takeIf { n.items.isEmpty() && it.isNotBlank() },
            ).joinToString(" · ")
            if (more.isNotEmpty()) big.addView(R.id.rows, RemoteViews(ctx.packageName, R.layout.pin_row).apply {
                setTextViewText(R.id.text, more)
                setOnClickPendingIntent(R.id.text, open)
            })
            val notification = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("📌 " + n.displayTitle)
                .setContentText(if (todo.isEmpty()) more else todo.joinToString(", ") { it.text })
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomBigContentView(big)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .addAction(0, "Открепить", pi(ctx, ACTION_UNPIN, id, 0))
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(nid(id), notification) }
        }
    }

    fun handle(ctx: Context, intent: Intent) {
        val note = intent.getLongExtra("note", -1)
        when (intent.action) {
            ACTION_TOGGLE -> {
                val n = Notes.get(note) ?: return
                val item = intent.getLongExtra("item", 0)
                Notes.upsert(n.copy(items = n.items.map { if (it.id == item) it.copy(checked = !it.checked) else it }))
            }
            ACTION_UNPIN -> set(ctx, note, false)
        }
    }
}

class PinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Pinned.handle(context, intent)
}

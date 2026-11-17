package ru.notes.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Большое окно напоминания: показывается поверх экрана блокировки,
 * экран включается сам. Кнопки — те же, что и у уведомления.
 */
class RingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val noteId = intent.getLongExtra(EXTRA_ID, -1)
        val itemId = intent.getLongExtra(EXTRA_ITEM, 0)
        val content = Notes.get(noteId)?.let { Reminders.contentOf(it, itemId) }

        setContent {
            // Свайп/кнопка «назад»: звук выключаем, уведомление остаётся в шторке
            BackHandler { stopSound() }
            NotesTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Column(
                            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(48.dp))
                            Text("🔔", style = MaterialTheme.typography.displayMedium)
                            Text(
                                content?.first ?: "Напоминание",
                                style = MaterialTheme.typography.headlineMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 16.dp),
                            )
                            val text = content?.second.orEmpty()
                            if (text.isNotBlank() && text != content?.first) {
                                Text(
                                    text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { Reminders.done(this@RingActivity, noteId, itemId); finish() },
                                modifier = Modifier.fillMaxWidth().height(64.dp),
                            ) { Text("Выполнено", style = MaterialTheme.typography.titleLarge) }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { Reminders.snooze(this@RingActivity, noteId, itemId, 10); finish() },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                ) { Text("+10 мин") }
                                OutlinedButton(
                                    onClick = { Reminders.snooze(this@RingActivity, noteId, itemId, 60); finish() },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                ) { Text("+1 час") }
                            }
                            Text(
                                "Назад — выключить звук, напоминание останется в шторке",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun stopSound() {
        sendBroadcast(
            Intent(this, ReminderReceiver::class.java).setAction("ru.notes.app.STOP")
                .putExtra(EXTRA_ID, intent.getLongExtra(EXTRA_ID, -1))
                .putExtra(EXTRA_ITEM, intent.getLongExtra(EXTRA_ITEM, 0))
        )
        finish()
    }
}

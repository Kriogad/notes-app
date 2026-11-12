package ru.notes.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.PackageInfoCompat
import java.io.File

/** Если приложение упало — сохраняем причину и показываем её при следующем запуске. */
object Crash {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val info = app.packageManager.getPackageInfo(app.packageName, 0)
                file(app).writeText(
                    "Заметки ${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)}), " +
                        "Android ${Build.VERSION.RELEASE}, ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
                        e.stackTraceToString().take(6000)
                )
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun take(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
}

@Composable
fun CrashReport() {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(Crash.take(ctx)) }
    val t = text ?: return
    AlertDialog(
        onDismissRequest = { text = null },
        title = { Text("Приложение закрылось с ошибкой") },
        text = {
            Text(
                "Скопируйте текст и отправьте разработчику:\n\n$t",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("crash", t))
                text = null
            }) { Text("Скопировать") }
        },
        dismissButton = { TextButton(onClick = { text = null }) { Text("Закрыть") } },
    )
}

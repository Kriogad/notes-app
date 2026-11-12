package ru.notes.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Обновление из релизов GitHub: тег v1.N, где N — versionCode. */
object Updater {
    private const val API = "https://api.github.com/repos/Kriogad/notes-app/releases/latest"

    class Release(val version: Int, val name: String, val notes: String, val url: String)

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
    }

    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(open(API).inputStream.bufferedReader().use { it.readText() })
            val version = o.getString("tag_name").substringAfterLast('.').toInt()
            val current = PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0))
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .first { it.getString("name").endsWith(".apk") }
            val notes = o.optString("body").lines()
                .filterNot { it.startsWith("Co-Authored-By") || it.startsWith("Claude-Session") }
                .joinToString("\n").trim()
            if (version > current) Release(version, o.optString("name"), notes, apk.getString("browser_download_url"))
            else null
        }.getOrNull()
    }

    suspend fun download(ctx: Context, url: String, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "update.apk")
        val conn = open(url)
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        file
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Один раз Android просит разрешить установку из этого приложения. */
    fun askInstallPermission(ctx: Context) {
        Toast.makeText(ctx, "Разрешите установку и снова нажмите «Обновить»", Toast.LENGTH_LONG).show()
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        )
    }

    fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** Проверка при запуске и окно «Доступна новая версия». */
@Composable
fun UpdateCheck() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) { release = Updater.check(ctx) }

    val r = release ?: return
    AlertDialog(
        onDismissRequest = { if (progress == null) release = null },
        title = { Text("Доступно обновление: ${r.name}") },
        text = {
            Column {
                if (r.notes.isNotBlank()) Text(r.notes)
                progress?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null, onClick = {
                if (!Updater.canInstall(ctx)) {
                    Updater.askInstallPermission(ctx)
                    return@TextButton
                }
                progress = 0f
                scope.launch {
                    try {
                        Updater.install(ctx, Updater.download(ctx, r.url) { progress = it })
                        release = null
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Не удалось скачать обновление", Toast.LENGTH_LONG).show()
                    }
                    progress = null
                }
            }) { Text(if (progress == null) "Обновить" else "Загрузка…") }
        },
        dismissButton = {
            if (progress == null) TextButton(onClick = { release = null }) { Text("Позже") }
        },
    )
}

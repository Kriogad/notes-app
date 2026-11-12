package ru.notes.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Резервная копия в папке приложения на Яндекс Диске (Приложения/Заметки).
 * notes.json и secret.enc (он уже зашифрован) + копия на каждый день в history/.
 */
object Backup {
    /** ID приложения из oauth.yandex.ru (не секрет, его видно в ссылке входа). */
    const val CLIENT_ID = "4f38f89d1ef9446782427fd92e3d1959"
    const val REDIRECT = "notesapp://yandex"
    private const val API = "https://cloud-api.yandex.net/v1/disk/resources"
    private val FILES = listOf("notes.json", Vault.FILE)

    /** Текст для экрана настроек: когда была последняя копия или что сломалось. */
    val status = MutableStateFlow("")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private fun token(ctx: Context) = prefs(ctx).getString("token", null)
    fun loggedIn(ctx: Context) = token(ctx) != null
    fun configured() = CLIENT_ID.isNotBlank()

    fun init(ctx: Context) {
        status.value = prefs(ctx).getString("status", "") ?: ""
    }

    private fun setStatus(ctx: Context, s: String) {
        status.value = s
        prefs(ctx).edit().putString("status", s).apply()
    }

    fun login(ctx: Context) {
        val url = "https://oauth.yandex.ru/authorize?response_type=token&force_confirm=yes" +
            "&client_id=$CLIENT_ID&redirect_uri=" + URLEncoder.encode(REDIRECT, "UTF-8")
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Возврат из браузера: notesapp://yandex#access_token=...&expires_in=... */
    fun handleRedirect(ctx: Context, uri: Uri): Boolean {
        if (uri.scheme != "notesapp" || uri.host != "yandex") return false
        val params = (uri.encodedFragment ?: "").split("&").mapNotNull {
            it.split("=", limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0] to Uri.decode(p[1]) }
        }.toMap()
        val t = params["access_token"]
        if (t == null) {
            setStatus(ctx, "Вход не выполнен: " + (params["error_description"] ?: params["error"] ?: "отменён"))
        } else {
            prefs(ctx).edit().putString("token", t).apply()
            setStatus(ctx, "Яндекс Диск подключён")
        }
        return true
    }

    fun logout(ctx: Context) {
        prefs(ctx).edit().remove("token").apply()
        setStatus(ctx, "")
    }

    /** Копия через минуту после последнего изменения, когда есть интернет. */
    fun schedule(ctx: Context) {
        if (!loggedIn(ctx)) return
        val req = OneTimeWorkRequestBuilder<BackupWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("backup", ExistingWorkPolicy.REPLACE, req)
    }

    suspend fun upload(ctx: Context) = withContext(Dispatchers.IO) {
        val token = token(ctx) ?: return@withContext
        val files = FILES.map { File(ctx.filesDir, it) }.filter { it.exists() }
        // Пустой телефон (например, новый) не должен затереть хорошую копию на Диске
        if (Notes.notes.value.isEmpty() && !Vault.exists) return@withContext
        request("PUT", "$API?path=" + enc("app:/history"), token, okCodes = setOf(201, 409))
        val day = LocalDate.now()
        for (f in files) {
            val bytes = f.readBytes()
            put(token, "app:/${f.name}", bytes)
            put(token, "app:/history/$day-${f.name}", bytes)
        }
        setStatus(ctx, "Последняя копия: " + java.time.LocalDateTime.now().format(
            java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm")))
    }

    /** Восстановить из копии. false — копии на Диске нет. */
    suspend fun restore(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val token = token(ctx) ?: return@withContext false
        var found = false
        for (name in FILES) {
            val href = try {
                link(token, "$API/download?path=" + enc("app:/$name"))
            } catch (e: HttpError) {
                if (e.code == 404) continue else throw e
            }
            val bytes = (URL(href).openConnection() as HttpURLConnection).inputStream.use { it.readBytes() }
            val tmp = File(ctx.filesDir, "$name.tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(File(ctx.filesDir, name))
            found = true
        }
        if (found) {
            withContext(Dispatchers.Main) { Notes.reload(); Vault.reload() }
            setStatus(ctx, "Восстановлено из копии")
        }
        found
    }

    private fun put(token: String, path: String, bytes: ByteArray) {
        val href = link(token, "$API/upload?overwrite=true&path=" + enc(path))
        val c = URL(href).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.doOutput = true
        c.setFixedLengthStreamingMode(bytes.size)
        c.outputStream.use { it.write(bytes) }
        if (c.responseCode !in 200..299) throw HttpError(c.responseCode)
    }

    private fun link(token: String, url: String): String {
        val c = request("GET", url, token)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("href")
    }

    private fun request(method: String, url: String, token: String, okCodes: Set<Int> = (200..299).toSet()): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "OAuth $token")
        if (c.responseCode !in okCodes) throw HttpError(c.responseCode)
        return c
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    class HttpError(val code: Int) : IOException("HTTP $code")

    /** Понятный текст ошибки для экрана настроек. */
    fun fail(ctx: Context, e: Exception) {
        if (e is HttpError && e.code == 401) {
            logout(ctx)
            setStatus(ctx, "Вход в Яндекс устарел — войдите снова")
        } else {
            setStatus(ctx, "Копия не сделана: нет связи с Яндекс Диском (" + e.message + ")")
        }
    }
}

class BackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        Backup.upload(applicationContext)
        Result.success()
    } catch (e: Backup.HttpError) {
        Backup.fail(applicationContext, e)
        if (e.code == 401) Result.failure() else Result.retry()
    } catch (e: Exception) {
        Backup.fail(applicationContext, e)
        Result.retry()
    }
}

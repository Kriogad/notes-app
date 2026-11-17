package ru.notes.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Полная синхронизация между устройствами и браузером (kriogad.github.io/notes-app/sync.html).
 *
 * Данные: sync/{код}/notes/{id} = {j: json заметки, u: время изменения},
 *         sync/{код}/deleted/{id} = время удаления,
 *         sync/{код}/vault = {j: содержимое secret.enc, u: время}.
 *
 * Кто знает код — читает обычные заметки. Секретка хранится в облаке только
 * в зашифрованном виде (файл secret.enc как есть); пароль никуда не отправляется,
 * расшифровка — локально на устройстве или в браузере.
 * Конфликты: побеждает версия с бóльшим временем изменения.
 */
object Sync {
    private const val PREFS = "sync"
    private const val DB_URL = "https://notes-shared-6503f-default-rtdb.europe-west1.firebasedatabase.app"
    private val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // без похожих 0/O, 1/I

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var db: FirebaseDatabase? = null

    private val codeState = MutableStateFlow<String?>(null)
    /** Код синхронизации; null — выключена. */
    val code: StateFlow<String?> = codeState
    /** Текст для настроек: что происходит с синхронизацией. */
    val status = MutableStateFlow("")

    private var root: DatabaseReference? = null
    private var notesListener: ValueEventListener? = null
    private var deletedListener: ValueEventListener? = null
    private var vaultListener: ValueEventListener? = null

    /** Последнее известное облачное состояние: id → (updated to json); защита от эха и детект удалений. */
    private val pushed = mutableMapOf<String, Pair<Long, String>>()
    private var pushedVault: String? = null
    private var pushJob: Job? = null
    private var vaultPushJob: Job? = null

    val available get() = db != null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (FirebaseApp.getApps(app).isEmpty()) return
        db = runCatching { FirebaseDatabase.getInstance(DB_URL) }.getOrNull() ?: return
        prefs().getString("code", null)?.let { start(it) }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- включение / выключение ----------

    /** Включить синхронизацию. [existing] — код с другого устройства; null — создать новый. */
    fun enable(existing: String?): String? {
        if (db == null) return null
        val c = existing?.uppercase()?.replace(Regex("[^A-Z0-9]"), "")?.takeIf { it.length >= 6 }
            ?: (1..12).map { alphabet.random() }.joinToString("")
        prefs().edit().putString("code", c).apply()
        pushed.clear()
        pushedVault = null
        start(c)
        return c
    }

    fun disable() {
        stop()
        prefs().edit().remove("code").remove("vault_u").apply()
        codeState.value = null
        status.value = ""
        pushed.clear()
        pushedVault = null
    }

    private fun start(c: String) {
        val d = db ?: return
        stop()
        val r = d.reference.child("sync").child(c)
        root = r
        codeState.value = c
        status.value = "Синхронизация включена"

        notesListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                for (ch in s.children) {
                    val id = ch.key?.toLongOrNull() ?: continue
                    val j = ch.child("j").getValue(String::class.java) ?: continue
                    val u = ch.child("u").getValue(Long::class.java) ?: continue
                    applyRemoteNote(id, j, u)
                }
            }
            override fun onCancelled(e: DatabaseError) { status.value = "Нет связи с облаком" }
        }
        r.child("notes").addValueEventListener(notesListener!!)

        deletedListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                for (ch in s.children) {
                    val id = ch.key?.toLongOrNull() ?: continue
                    val at = ch.getValue(Long::class.java) ?: continue
                    applyRemoteDelete(id, at)
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        r.child("deleted").addValueEventListener(deletedListener!!)

        vaultListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                val j = s.child("j").getValue(String::class.java) ?: return
                val u = s.child("u").getValue(Long::class.java) ?: return
                applyRemoteVault(j, u)
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        r.child("vault").addValueEventListener(vaultListener!!)

        onNotesChanged()
        onVaultChanged()
    }

    private fun stop() {
        root?.let { r ->
            notesListener?.let { r.child("notes").removeEventListener(it) }
            deletedListener?.let { r.child("deleted").removeEventListener(it) }
            vaultListener?.let { r.child("vault").removeEventListener(it) }
        }
        notesListener = null; deletedListener = null; vaultListener = null; root = null
    }

    // ---------- локальные правки → облако ----------

    /** Вызывается из Notes.commit после каждого изменения. */
    fun onNotesChanged() {
        if (!::app.isInitialized || root == null) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(1500)
            pushNotes()
        }
    }

    private fun pushNotes() {
        val r = root ?: return
        val now = System.currentTimeMillis()
        val current = Notes.notes.value.associateBy { it.id.toString() }
        // Удалённые локально — надгробие, чтобы облако и другие устройства тоже удалили
        (pushed.keys - current.keys).forEach { id ->
            pushed.remove(id)
            r.child("notes").child(id).removeValue()
            r.child("deleted").child(id).setValue(now)
        }
        // Новые и изменённые
        current.forEach { (id, n) ->
            val j = Notes.toJson(n).toString()
            if (pushed[id] != (n.updated to j)) {
                pushed[id] = n.updated to j
                r.child("notes").child(id).setValue(mapOf("j" to j, "u" to n.updated))
            }
        }
    }

    /** Вызывается из Vault при каждой записи секретного файла. */
    fun onVaultChanged() {
        if (!::app.isInitialized || root == null) return
        vaultPushJob?.cancel()
        vaultPushJob = scope.launch {
            delay(1500)
            val f = File(app.filesDir, Vault.FILE)
            if (!f.exists()) return@launch
            val content = f.readText()
            if (content != pushedVault) {
                pushedVault = content
                val now = System.currentTimeMillis()
                prefs().edit().putLong("vault_u", now).apply()
                root?.child("vault")?.setValue(mapOf("j" to content, "u" to now))
            }
        }
    }

    // ---------- облако → локально ----------

    private fun applyRemoteNote(id: Long, j: String, u: Long) {
        val local = Notes.get(id)
        if (local != null && local.updated > u) {
            // Локальная версия новее — зальём её при ближайшей отправке
            pushed[id.toString()] = u to j
            return
        }
        val newJson = runCatching { Notes.toJson(Notes.fromJson(JSONObject(j))).toString() }.getOrNull()
        if (local != null && newJson != null && Notes.toJson(local).toString() == newJson) {
            pushed[id.toString()] = u to j
            return
        }
        pushed[id.toString()] = u to j
        runCatching {
            Notes.upsert(Notes.fromJson(JSONObject(j)).copy(updated = u))
            status.value = "Обновлено с другого устройства"
        }
    }

    private fun applyRemoteDelete(id: Long, at: Long) {
        val local = Notes.get(id) ?: return
        if (local.updated <= at) {
            pushed.remove(id.toString())
            Notes.delete(id)
        }
    }

    private fun applyRemoteVault(j: String, u: Long) {
        if (j == pushedVault) return // эхо своей отправки
        val f = File(app.filesDir, Vault.FILE)
        val local = if (f.exists()) runCatching { f.readText() }.getOrNull() else null
        if (local == j) {
            pushedVault = j
            prefs().edit().putLong("vault_u", u).apply()
            return
        }
        // Наша версия не старее (уже заливали позже) — пропускаем
        if (u <= prefs().getLong("vault_u", 0L) && Vault.exists) return
        runCatching {
            val tmp = File(f.path + ".sync")
            tmp.writeText(j)
            tmp.renameTo(f)
            pushedVault = j
            prefs().edit().putLong("vault_u", u).apply()
            Vault.reloadFromSync()
            status.value = "Секретка обновлена с другого устройства"
        }
    }
}

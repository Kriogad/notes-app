package ru.notes.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.database.ChildEventListener
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
 *         sync/{код}/trash/{id} = {j: json заметки с "del", u: время удаления},
 *         sync/{код}/deleted/{id} = время окончательного удаления (надгробие),
 *         sync/{код}/vault = {j: содержимое secret.enc, u: время},
 *         sync/{код}/files/{имя с %2E вместо точки} = base64 файла вложения.
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
    private var lastFileSync = 0L

    private val codeState = MutableStateFlow<String?>(null)
    /** Код синхронизации; null — выключена. */
    val code: StateFlow<String?> = codeState
    /** Текст для настроек: что происходит с синхронизацией. */
    val status = MutableStateFlow("")

    private var root: DatabaseReference? = null
    private var notesListener: ValueEventListener? = null
    private var trashListener: ValueEventListener? = null
    private var deletedListener: ValueEventListener? = null
    private var vaultListener: ValueEventListener? = null
    private var filesListener: ChildEventListener? = null
    /** Имена файлов, которые уже есть в облаке. */
    private val remoteFiles = mutableSetOf<String>()

    /** Последнее известное облачное состояние: id → (updated to json); защита от эха и детект удалений. */
    private val pushed = mutableMapOf<String, Pair<Long, String>>()
    private val pushedTrash = mutableMapOf<String, Pair<Long, String>>()
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
        prefs().edit().remove("code").remove("vault_u").remove("sent").apply()
        codeState.value = null
        status.value = ""
        pushed.clear()
        pushedTrash.clear()
        pushedVault = null
    }

    /**
     * Сменить код: данные переезжают на новый код, старый стирается из базы —
     * по старому коду войти больше нельзя. null — не удалось (нет сети).
     */
    fun changeCode(onDone: (String?) -> Unit) {
        val r = root ?: run { onDone(null); return }
        val d = db ?: run { onDone(null); return }
        val newCode = (1..12).map { alphabet.random() }.joinToString("")
        status.value = "Переношу данные на новый код…"
        r.get().addOnSuccessListener { snap ->
            d.reference.child("sync").child(newCode).setValue(snap.value).addOnSuccessListener {
                r.removeValue()
                prefs().edit().putString("code", newCode).remove("sent").apply()
                pushed.clear()
                pushedTrash.clear()
                pushedVault = null
                start(newCode)
                onDone(newCode)
            }.addOnFailureListener { status.value = "Не удалось: нет связи"; onDone(null) }
        }.addOnFailureListener { status.value = "Не удалось: нет связи"; onDone(null) }
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

        trashListener = object : ValueEventListener {
            override fun onDataChange(s: DataSnapshot) {
                for (ch in s.children) {
                    val id = ch.key?.toLongOrNull() ?: continue
                    val j = ch.child("j").getValue(String::class.java) ?: continue
                    val u = ch.child("u").getValue(Long::class.java) ?: continue
                    applyRemoteTrash(id, j, u)
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        r.child("trash").addValueEventListener(trashListener!!)

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

        attachFiles(r)

        onNotesChanged()
        onVaultChanged()
        syncFiles(force = true)
    }

    private fun stop() {
        root?.let { r ->
            notesListener?.let { r.child("notes").removeEventListener(it) }
            trashListener?.let { r.child("trash").removeEventListener(it) }
            deletedListener?.let { r.child("deleted").removeEventListener(it) }
            vaultListener?.let { r.child("vault").removeEventListener(it) }
            filesListener?.let { r.child("files").removeEventListener(it) }
        }
        notesListener = null; trashListener = null; deletedListener = null; vaultListener = null
        filesListener = null; remoteFiles.clear(); root = null
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
        val active = Notes.notes.value.associateBy { it.id.toString() }
        val trash = Notes.trash.value.associateBy { it.id.toString() }
        // Исчезли отовсюду — окончательно удалены: надгробие для всех устройств
        ((pushed.keys + pushedTrash.keys) - active.keys - trash.keys).forEach { id ->
            pushed.remove(id)
            pushedTrash.remove(id)
            r.child("notes").child(id).removeValue()
            r.child("trash").child(id).removeValue()
            r.child("deleted").child(id).setValue(now)
        }
        // Ушли из корзины (восстановили) — чистим узел корзины, в notes зальётся ниже
        (pushedTrash.keys - trash.keys).forEach { id ->
            pushedTrash.remove(id)
            r.child("trash").child(id).removeValue()
        }
        // Ушли из активных (в корзину) — чистим узел заметок, в корзину зальётся ниже
        (pushed.keys - active.keys).forEach { id ->
            pushed.remove(id)
            r.child("notes").child(id).removeValue()
        }
        // Новые и изменённые активные
        active.forEach { (id, n) ->
            val j = Notes.toJson(n).toString()
            if (pushed[id] != (n.updated to j)) {
                pushed[id] = n.updated to j
                r.child("notes").child(id).setValue(mapOf("j" to j, "u" to n.updated))
            }
        }
        // Корзина: ключ времени — момент удаления
        trash.forEach { (id, n) ->
            val j = Notes.toJson(n).toString()
            val u = n.deletedAt ?: now
            if (pushedTrash[id] != (u to j)) {
                pushedTrash[id] = u to j
                r.child("trash").child(id).setValue(mapOf("j" to j, "u" to u))
            }
        }
        syncFiles()
    }

    // ---------- фото и вложения (Realtime Database, base64) ----------

    /** Имя файла → ключ узла (в ключах Firebase нельзя точку). */
    private fun encName(n: String) = n.replace(".", "%2E")
    private fun decName(k: String) = k.replace("%2E", ".")

    /** Слушаем облачные файлы: качаем те, которых нет локально. */
    private fun attachFiles(r: DatabaseReference) {
        filesListener = object : ChildEventListener {
            override fun onChildAdded(s: DataSnapshot, prev: String?) = onFile(s)
            override fun onChildChanged(s: DataSnapshot, prev: String?) = onFile(s)
            override fun onChildRemoved(s: DataSnapshot) { s.key?.let { remoteFiles.remove(decName(it)) } }
            override fun onChildMoved(s: DataSnapshot, prev: String?) {}
            override fun onCancelled(e: DatabaseError) {}
            private fun onFile(s: DataSnapshot) {
                val name = s.key?.let { decName(it) } ?: return
                remoteFiles += name
                val local = File(Attachments.dir(app), name)
                if (local.exists()) return
                val b64 = s.getValue(String::class.java) ?: return
                scope.launch {
                    runCatching {
                        local.writeBytes(android.util.Base64.decode(b64, android.util.Base64.NO_WRAP))
                        Notes.refreshWidgets()
                    }
                }
            }
        }
        r.child("files").addChildEventListener(filesListener!!)
    }

    /**
     * Файлы вложений (обычные и зашифрованные .enc) ↔ база: sync/{код}/files/{имя} = base64.
     * Зашифрованные едут как есть — в облаке они нечитаемы. Чаще раза в 30 секунд не дёргаем.
     */
    private fun syncFiles(force: Boolean = false) {
        val r = root ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastFileSync < 30_000) return
        lastFileSync = now
        val dir = Attachments.dir(app)
        val sent = prefs().getStringSet("sent", emptySet())!!.toMutableSet()
        // Залить новые локальные
        dir.listFiles().orEmpty()
            .filter { it.name !in sent && it.name !in remoteFiles }
            .forEach { f ->
                val b64 = runCatching { encodeForSync(f) }.getOrNull() ?: return@forEach
                r.child("files").child(encName(f.name)).setValue(b64).addOnSuccessListener {
                    sent += f.name
                    prefs().edit().putStringSet("sent", sent.toSet()).apply()
                }
            }
    }

    /** Картинки сжимаем (JPEG, до 1280px), остальное — как есть; тяжелее ~7 МБ не отправляем. */
    private fun encodeForSync(f: File): String? {
        var bytes = f.readBytes()
        if (!f.name.endsWith(".enc") && bytes.size > 300_000) {
            Images.decode(bytes, 1280)?.let { bmp ->
                val out = java.io.ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, out)
                if (out.size() < bytes.size) bytes = out.toByteArray()
            }
        }
        if (bytes.size > 7_000_000) return null
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
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
        val remote = runCatching { Notes.fromJson(JSONObject(j)).copy(updated = u, deletedAt = null) }.getOrNull() ?: return
        if (Notes.trash.value.any { it.id == id }) {
            // На другом устройстве восстановили из корзины
            pushedTrash.remove(id.toString())
            pushed[id.toString()] = u to j
            Notes.restoreFromSync(remote)
            return
        }
        val local = Notes.get(id)
        if (local != null && local.updated > u) {
            // Локальная версия новее — зальём её при ближайшей отправке
            pushed[id.toString()] = u to j
            return
        }
        if (local != null && Notes.toJson(local).toString() == Notes.toJson(remote).toString()) {
            pushed[id.toString()] = u to j
            return
        }
        pushed[id.toString()] = u to j
        runCatching {
            Notes.upsert(remote)
            status.value = "Обновлено с другого устройства"
        }
    }

    /** На другом устройстве заметку отправили в корзину. */
    private fun applyRemoteTrash(id: Long, j: String, u: Long) {
        val inTrash = Notes.trash.value.any { it.id == id }
        val local = Notes.get(id)
        if (inTrash) { pushedTrash[id.toString()] = u to j; return }
        if (local != null && local.updated > u) {
            // Наша правка свежее удаления — зальём её обратно
            pushedTrash[id.toString()] = u to j
            return
        }
        pushedTrash[id.toString()] = u to j
        pushed.remove(id.toString())
        runCatching {
            Notes.moveToTrashFromSync(Notes.fromJson(JSONObject(j)))
            status.value = "Обновлено с другого устройства"
        }
    }

    /** Окончательное удаление (очистка корзины или удаление навсегда). */
    private fun applyRemoteDelete(id: Long, at: Long) {
        val inTrash = Notes.trash.value.any { it.id == id }
        val local = Notes.get(id)
        when {
            inTrash -> {
                pushedTrash.remove(id.toString())
                Notes.deleteForever(id)
            }
            local != null && local.updated <= at -> {
                pushed.remove(id.toString())
                Notes.deleteForever(id)
            }
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

package ru.notes.app

import android.app.Application
import android.content.Context
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CheckItem(val text: String, val checked: Boolean = false)

/** Вложение: файл лежит в filesDir/attachments/[file], [name] — исходное имя для показа. */
data class Attachment(val file: String, val name: String, val mime: String) {
    val isImage get() = mime.startsWith("image/")
    /** Зашифровано ключом секретного раздела. */
    val secret get() = file.endsWith(".enc")
}

data class Note(
    val id: Long,
    val title: String = "",
    val text: String = "",
    val items: List<CheckItem> = emptyList(),
    val done: Boolean = false,
    val remindAt: Long? = null,
    val updated: Long = System.currentTimeMillis(),
    val attachments: List<Attachment> = emptyList(),
    val repeat: Repeat = Repeat.NONE,
) {
    val isBlank: Boolean
        get() = title.isBlank() && text.isBlank() && items.all { it.text.isBlank() } && attachments.isEmpty()

    val displayTitle: String
        get() = title.ifBlank {
            text.lineSequence().firstOrNull { it.isNotBlank() }
                ?: items.firstOrNull { it.text.isNotBlank() }?.text
                ?: ""
        }.trim().ifBlank { "Без названия" }

    /** Совпадает ли содержимое (без учёта времени изменения). */
    fun sameContent(other: Note?) = other != null && copy(updated = 0) == other.copy(updated = 0)
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Crash.install(this)
        Vault.init(this)
        Backup.init(this)
        Notes.init(this)
        Reminders.createChannel(this)
    }
}

/** Общий интерфейс для обычных и секретных заметок — редактор работает с любым. */
interface NoteStore {
    val notes: StateFlow<List<Note>>
    fun get(id: Long): Note?
    fun upsert(note: Note)
    fun delete(id: Long)
    fun setDone(id: Long, done: Boolean)
}

/** Хранилище заметок: JSON-файл + StateFlow для экрана и виджета. */
object Notes : NoteStore {
    private lateinit var app: Context
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<List<Note>>(emptyList())
    override val notes: StateFlow<List<Note>> = state

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        file = File(app.filesDir, "notes.json")
        state.value = sort(load())
    }

    override fun get(id: Long): Note? = state.value.find { it.id == id }

    @Synchronized
    override fun upsert(note: Note) {
        commit(state.value.filter { it.id != note.id } + note)
        Reminders.sync(app, note)
    }

    @Synchronized
    override fun delete(id: Long) {
        get(id)?.attachments?.forEach { Attachments.delete(app, it) }
        commit(state.value.filter { it.id != id })
        Reminders.cancel(app, id)
    }

    override fun setDone(id: Long, done: Boolean) {
        val n = get(id) ?: return
        if (n.done != done) upsert(n.copy(done = done, updated = System.currentTimeMillis()))
    }

    fun toggleDone(id: Long) {
        get(id)?.let { setDone(id, !it.done) }
    }

    /** Галочка у пункта списка (из виджета). Время изменения не трогаем, чтобы список не прыгал. */
    fun toggleItem(id: Long, index: Int) {
        val n = get(id) ?: return
        if (index !in n.items.indices) return
        upsert(n.copy(items = n.items.toMutableList().also { it[index] = it[index].copy(checked = !it[index].checked) }))
    }

    fun uncheckAll(id: Long) {
        val n = get(id) ?: return
        upsert(n.copy(items = n.items.map { it.copy(checked = false) }))
    }

    /** Перечитать файл (после восстановления из копии). */
    @Synchronized
    fun reload() {
        state.value = sort(load())
        state.value.forEach { Reminders.sync(app, it) }
        refreshWidgets()
    }

    fun refreshWidgets() {
        scope.launch {
            runCatching { NotesWidget().updateAll(app) }
            runCatching { NoteWidget().updateAll(app) }
        }
    }

    // Невыполненные сверху, внутри — свежие первыми
    private fun sort(list: List<Note>) =
        list.sortedWith(compareBy<Note> { it.done }.thenByDescending { it.updated })

    private fun commit(list: List<Note>) {
        val sorted = sort(list)
        state.value = sorted
        save(sorted)
        refreshWidgets()
        Backup.schedule(app)
    }

    private fun load(): List<Note> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            List(arr.length()) { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            // Файл повреждён — сохраняем копию, чтобы не потерять данные
            file.copyTo(File(file.path + ".bak"), overwrite = true)
            emptyList()
        }
    }

    private fun save(list: List<Note>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun toJson(n: Note) = JSONObject()
        .put("id", n.id)
        .put("title", n.title)
        .put("text", n.text)
        .put("done", n.done)
        .put("updated", n.updated)
        .put("items", JSONArray().apply {
            n.items.forEach { put(JSONObject().put("t", it.text).put("c", it.checked)) }
        })
        .apply { n.remindAt?.let { put("remind", it) } }
        .apply { if (n.repeat != Repeat.NONE) put("rep", n.repeat.key) }
        .apply {
            if (n.attachments.isNotEmpty()) put("att", JSONArray().apply {
                n.attachments.forEach { put(JSONObject().put("f", it.file).put("n", it.name).put("m", it.mime)) }
            })
        }

    fun fromJson(o: JSONObject) = Note(
        id = o.getLong("id"),
        title = o.optString("title"),
        text = o.optString("text"),
        items = o.optJSONArray("items")?.let { a ->
            List(a.length()) { i -> a.getJSONObject(i).let { CheckItem(it.optString("t"), it.optBoolean("c")) } }
        } ?: emptyList(),
        done = o.optBoolean("done"),
        remindAt = if (o.has("remind")) o.getLong("remind") else null,
        updated = o.optLong("updated"),
        repeat = Repeat.of(o.optString("rep")),
        attachments = o.optJSONArray("att")?.let { a ->
            List(a.length()) { i -> a.getJSONObject(i).let { Attachment(it.getString("f"), it.optString("n"), it.optString("m")) } }
        } ?: emptyList(),
    )
}

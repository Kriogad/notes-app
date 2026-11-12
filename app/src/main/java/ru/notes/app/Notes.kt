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

data class CheckItem(
    val text: String,
    val checked: Boolean = false,
    val id: Long = kotlin.random.Random.nextLong(1, Long.MAX_VALUE),
    val remindAt: Long? = null,
    val repeat: Repeat = Repeat.NONE,
    /** Своя мелодия напоминания; null — как в настройках. */
    val sound: String? = null,
    /** Своя громкость, %; null — как в настройках. */
    val volume: Int? = null,
)

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
    val sound: String? = null,
    val volume: Int? = null,
    /** Серия повторяющейся заметки: у всех копий (и в архиве) одно значение. */
    val series: Long = 0,
    /** Когда выполнено (для истории). */
    val doneAt: Long? = null,
    /** Напоминание этого раза уже прозвенело, ждём «Выполнено». */
    val due: Boolean = false,
    /** Место в списке (перетаскивание пальцем). Меньше — выше. */
    val pos: Double? = null,
    /** Показание счётчика (для повторяющихся заметок). */
    val reading: Double? = null,
) {
    val seriesId get() = if (series == 0L) id else series

    /** Есть повтор у заметки или у какого-нибудь пункта. */
    val isRecurring get() = repeat != Repeat.NONE || items.any { it.repeat != Repeat.NONE && it.remindAt != null }

    /** Ближайшее напоминание (заметки или пункта). */
    val nextRemind get() = (listOfNotNull(remindAt) + items.filter { !it.checked }.mapNotNull { it.remindAt }).minOrNull()

    val isBlank: Boolean
        get() = title.isBlank() && text.isBlank() && items.all { it.text.isBlank() } && attachments.isEmpty()

    val displayTitle: String
        get() = title.ifBlank {
            text.lineSequence().firstOrNull { it.isNotBlank() }
                ?: items.firstOrNull { it.text.isNotBlank() }?.text
                ?: ""
        }.trim().ifBlank { "Без названия" }

    /** Совпадает ли содержимое (без учёта времени изменения). */
    fun sameContent(other: Note?) = other != null && copy(updated = 0, pos = null) == other.copy(updated = 0, pos = null)
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
        // Старым заметкам без места — место по текущему порядку
        state.value = sort(load().sortedWith(compareBy<Note> { it.done }.thenByDescending { it.updated })
            .mapIndexed { i, n -> if (n.pos == null) n.copy(pos = i.toDouble()) else n })
        save(state.value)
    }

    override fun get(id: Long): Note? = state.value.find { it.id == id }

    @Synchronized
    override fun upsert(note: Note) {
        val old = get(note.id)
        if (old != null && !old.done && note.done && note.repeat != Repeat.NONE && note.remindAt != null) {
            completeRecurring(note)
            return
        }
        // Место в списке: сохраняем прежнее, новая заметка — наверх
        @Suppress("NAME_SHADOWING")
        val note = note.copy(pos = note.pos ?: old?.pos ?: ((state.value.mapNotNull { it.pos }.minOrNull() ?: 0.0) - 1))
        commit(state.value.filter { it.id != note.id } + note)
        Reminders.sync(app, note)
    }

    @Synchronized
    override fun delete(id: Long) {
        get(id)?.let { n ->
            n.attachments.forEach { Attachments.delete(app, it) }
            Reminders.cancelAll(app, n)
        }
        commit(state.value.filter { it.id != id })
    }

    override fun setDone(id: Long, done: Boolean) {
        val n = get(id) ?: return
        if (n.done != done) upsert(n.copy(done = done, updated = System.currentTimeMillis()))
    }

    /**
     * Повторяющаяся выполнена: эта копия (с фото) — в архив для истории,
     * вместо неё — чистая копия на следующий раз.
     */
    private fun completeRecurring(note: Note) {
        val now = System.currentTimeMillis()
        val at = note.remindAt!!
        val archived = note.copy(
            done = true, doneAt = now, series = note.seriesId, due = false,
            remindAt = null, repeat = Repeat.NONE,
            items = note.items.map { it.copy(remindAt = null, repeat = Repeat.NONE) },
        )
        // Прозвенело (due) — будильник уже стоит на следующий раз. Сделали заранее — переносим на следующий.
        val nextAt = if (note.due && at > now) at else note.repeat.next(at)
        val fresh = note.copy(
            id = now, series = note.seriesId, done = false, doneAt = null, due = false,
            remindAt = nextAt,
            attachments = emptyList(), items = note.items.map { it.copy(checked = false) }, updated = now, reading = null,
        )
        Reminders.cancelAll(app, note)
        commit(state.value.filter { it.id != note.id } + archived + fresh)
        Reminders.sync(app, fresh)
        WidgetPrefs.replaceNote(app, note.id, fresh.id)
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

    // Невыполненные сверху, внутри — в порядке, который задал пользователь
    private fun sort(list: List<Note>) =
        list.sortedWith(compareBy<Note> { it.done }.thenBy { it.pos ?: 0.0 })

    /** Новый порядок после перетаскивания: те же места, переставленные между этими заметками. */
    @Synchronized
    fun reorder(ids: List<Long>) {
        val byId = state.value.associateBy { it.id }
        val slots = ids.mapNotNull { byId[it]?.pos }.sorted()
        if (slots.size != ids.size) return
        val moved = ids.mapIndexed { i, id -> id to slots[i] }.toMap()
        commit(state.value.map { n -> moved[n.id]?.let { n.copy(pos = it) } ?: n })
    }

    private fun commit(list: List<Note>) {
        val sorted = sort(list)
        state.value = sorted
        save(sorted)
        refreshWidgets()
        Pinned.refresh(app)
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
            n.items.forEach {
                put(JSONObject().put("t", it.text).put("c", it.checked).put("i", it.id).apply {
                    it.remindAt?.let { r -> put("r", r) }
                    if (it.repeat != Repeat.NONE) put("rep", it.repeat.key)
                    it.sound?.let { s -> put("snd", s) }
                    it.volume?.let { v -> put("vol", v) }
                })
            }
        })
        .apply { n.remindAt?.let { put("remind", it) } }
        .apply { if (n.repeat != Repeat.NONE) put("rep", n.repeat.key) }
        .apply { if (n.series != 0L) put("s", n.series) }
        .apply { n.sound?.let { put("snd", it) } }
        .apply { n.volume?.let { put("vol", it) } }
        .apply { n.doneAt?.let { put("da", it) } }
        .apply { if (n.due) put("due", true) }
        .apply { n.pos?.let { put("pos", it) } }
        .apply { n.reading?.let { put("rd", it) } }
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
            List(a.length()) { i ->
                a.getJSONObject(i).let {
                    val item = CheckItem(it.optString("t"), it.optBoolean("c"),
                        remindAt = if (it.has("r")) it.getLong("r") else null, repeat = Repeat.of(it.optString("rep")),
                        sound = if (it.has("snd")) it.getString("snd") else null,
                        volume = if (it.has("vol")) it.getInt("vol") else null)
                    if (it.has("i")) item.copy(id = it.getLong("i")) else item
                }
            }
        } ?: emptyList(),
        done = o.optBoolean("done"),
        remindAt = if (o.has("remind")) o.getLong("remind") else null,
        updated = o.optLong("updated"),
        repeat = Repeat.of(o.optString("rep")),
        series = o.optLong("s"),
        sound = if (o.has("snd")) o.getString("snd") else null,
        volume = if (o.has("vol")) o.getInt("vol") else null,
        doneAt = if (o.has("da")) o.getLong("da") else null,
        due = o.optBoolean("due"),
        pos = if (o.has("pos")) o.getDouble("pos") else null,
        reading = if (o.has("rd")) o.getDouble("rd") else null,
        attachments = o.optJSONArray("att")?.let { a ->
            List(a.length()) { i -> a.getJSONObject(i).let { Attachment(it.getString("f"), it.optString("n"), it.optString("m")) } }
        } ?: emptyList(),
    )
}

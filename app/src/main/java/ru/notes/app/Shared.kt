package ru.notes.app

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Общие списки через Firebase Realtime Database.
 * Инвайт = код проекта (8 букв/цифр): кто знает код — тот участник.
 * Данные: projects/{code}/info {title} и projects/{code}/items/{id} {text, checked, updated}.
 * Изменения подтягиваются сами (realtime), правки без интернета уходят при появлении сети.
 */
object Shared {
    class Project(val code: String, val title: String)
    class Item(val id: String, val text: String, val checked: Boolean, val updated: Long)

    private const val PREFS = "shared"
    private const val DB_URL = "https://notes-shared-6503f-default-rtdb.europe-west1.firebasedatabase.app"
    private val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789" // без похожих 0/O, 1/I

    private lateinit var app: Context

    /** Мои проекты (локальный список кодов). */
    private val projectsState = MutableStateFlow<List<Project>>(emptyList())
    val projects: StateFlow<List<Project>> = projectsState

    /** Пункты по коду проекта. */
    private val itemsState = MutableStateFlow<Map<String, List<Item>>>(emptyMap())
    val items: StateFlow<Map<String, List<Item>>> = itemsState

    private var db: FirebaseDatabase? = null
    private val listeners = mutableMapOf<String, ValueEventListener>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        // Если Firebase не настроен в сборке — раздел покажет подсказку, краша не будет
        if (FirebaseApp.getApps(app).isEmpty()) return
        db = runCatching {
            // URL явно: в google-services.json его нет, если файл скачан до создания базы
            FirebaseDatabase.getInstance(DB_URL).apply { setPersistenceEnabled(true) }
        }.getOrNull() ?: return
        loadLocal()
        projectsState.value.forEach { listen(it.code) }
    }

    val available get() = ::app.isInitialized && db != null

    // ---------- локальный список моих проектов ----------

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadLocal() {
        val arr = JSONArray(prefs().getString("projects", "[]"))
        projectsState.value = List(arr.length()) {
            val o = arr.getJSONObject(it)
            Project(o.getString("code"), o.optString("title", "Список"))
        }
    }

    private fun saveLocal() {
        val arr = JSONArray()
        projectsState.value.forEach { p -> arr.put(JSONObject().put("code", p.code).put("title", p.title)) }
        prefs().edit().putString("projects", arr.toString()).apply()
    }

    // ---------- проекты ----------

    private fun newCode() = (1..8).map { alphabet.random() }.joinToString("")

    /** Создать общий список, вернуть его код для инвайта. */
    fun create(title: String): String? {
        val d = db ?: return null
        val code = newCode()
        d.reference.child("projects").child(code).child("info")
            .setValue(mapOf("title" to title, "created" to System.currentTimeMillis()))
        projectsState.value = projectsState.value + Project(code, title)
        saveLocal()
        listen(code)
        return code
    }

    /** Присоединиться по инвайт-коду. false — такого кода нет. */
    fun join(codeRaw: String, onResult: (Boolean) -> Unit) {
        val d = db ?: run { onResult(false); return }
        val code = codeRaw.uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (projectsState.value.any { it.code == code }) { onResult(true); return }
        d.reference.child("projects").child(code).child("info").get()
            .addOnSuccessListener { snap ->
                val title = snap.child("title").getValue(String::class.java)
                if (title == null) onResult(false)
                else {
                    projectsState.value = projectsState.value + Project(code, title)
                    saveLocal()
                    listen(code)
                    onResult(true)
                }
            }
            .addOnFailureListener { onResult(false) }
    }

    /** Покинуть список (у остальных участников он останется). */
    fun leave(code: String) {
        listeners.remove(code)?.let { db?.reference?.child("projects")?.child(code)?.child("items")?.removeEventListener(it) }
        projectsState.value = projectsState.value.filter { it.code != code }
        saveLocal()
        itemsState.value = itemsState.value - code
    }

    // ---------- пункты ----------

    private fun listen(code: String) {
        if (listeners.containsKey(code)) return
        val ref = db?.reference?.child("projects")?.child(code)?.child("items") ?: return
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { c ->
                    val text = c.child("text").getValue(String::class.java) ?: return@mapNotNull null
                    Item(
                        c.key!!, text,
                        c.child("checked").getValue(Boolean::class.java) ?: false,
                        c.child("updated").getValue(Long::class.java) ?: 0L,
                    )
                }.sortedWith(compareBy({ it.checked }, { it.updated }))
                itemsState.value = itemsState.value + (code to list)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(l)
        listeners[code] = l
    }

    fun addItem(code: String, text: String) {
        val d = db ?: return
        d.reference.child("projects").child(code).child("items").push().setValue(
            mapOf("text" to text, "checked" to false, "updated" to System.currentTimeMillis())
        )
    }

    fun toggle(code: String, item: Item) {
        db?.reference?.child("projects")?.child(code)?.child("items")?.child(item.id)
            ?.updateChildren(mapOf("checked" to !item.checked, "updated" to System.currentTimeMillis()))
    }

    fun deleteItem(code: String, itemId: String) {
        db?.reference?.child("projects")?.child(code)?.child("items")?.child(itemId)?.removeValue()
    }

    fun rename(code: String, title: String) {
        db?.reference?.child("projects")?.child(code)?.child("info")?.child("title")?.setValue(title)
        projectsState.value = projectsState.value.map { if (it.code == code) Project(code, title) else it }
        saveLocal()
    }
}

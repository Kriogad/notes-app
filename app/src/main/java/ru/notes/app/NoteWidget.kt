package ru.notes.app

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn as GlanceLazyColumn
import androidx.glance.appwidget.lazy.items as glanceItems
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column as GlanceColumn
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize as glanceFillMaxSize
import androidx.glance.layout.fillMaxWidth as glanceFillMaxWidth
import androidx.glance.layout.padding as glancePadding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.text.Text as GlanceText

private val KeyNote = ActionParameters.Key<Long>("note")
private val KeyIndex = ActionParameters.Key<Int>("index")
private val KeyOpenNote = ActionParameters.Key<Long>(EXTRA_OPEN)

/** Какой заметке принадлежит какой виджет. */
object WidgetPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("note_widgets", Context.MODE_PRIVATE)
    fun get(ctx: Context, widgetId: Int) = prefs(ctx).getLong("w$widgetId", -1L)
    fun set(ctx: Context, widgetId: Int, noteId: Long) = prefs(ctx).edit().putLong("w$widgetId", noteId).apply()
    fun remove(ctx: Context, widgetId: Int) = prefs(ctx).edit().remove("w$widgetId").apply()
}

/** Виджет одной заметки: пункты списка с галочками. */
class NoteWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val widgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val notes by Notes.notes.collectAsState()
            val noteId = WidgetPrefs.get(context, widgetId)
            GlanceTheme { Content(notes.find { it.id == noteId }) }
        }
    }

    @Composable
    private fun Content(note: Note?) {
        val onSurface = GlanceTheme.colors.onSurface
        GlanceColumn(
            GlanceModifier.glanceFillMaxSize().appWidgetBackground().cornerRadius(20.dp)
                .background(GlanceTheme.colors.widgetBackground).glancePadding(12.dp)
        ) {
            if (note == null) {
                GlanceText(
                    "Заметка удалена. Удалите виджет или выберите другую заметку (долгое нажатие на виджет).",
                    style = TextStyle(onSurface),
                )
            } else {
                NoteBody(note)
            }
        }
    }

    @Composable
    private fun NoteBody(note: Note) {
        val onSurface = GlanceTheme.colors.onSurface
        run {
            val open = actionStartActivity<MainActivity>(actionParametersOf(KeyOpenNote to note.id))
            Row(GlanceModifier.glanceFillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val count = if (note.items.isEmpty()) "" else
                    "  ${note.items.count { it.checked }}/${note.items.size}"
                GlanceText(
                    note.displayTitle + count,
                    GlanceModifier.defaultWeight().clickable(open),
                    TextStyle(onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                if (note.items.any { it.checked }) {
                    Image(
                        ImageProvider(R.drawable.ic_reset), "Снять все галочки",
                        GlanceModifier.size(40.dp).glancePadding(8.dp)
                            .clickable(actionRunCallback<UncheckAll>(actionParametersOf(KeyNote to note.id))),
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                    )
                }
            }
            // Невыполненные пункты сверху, отмеченные — вниз (как в списке покупок)
            val rows = note.items.withIndex().filter { it.value.text.isNotBlank() }.sortedBy { it.value.checked }
            GlanceLazyColumn {
                if (note.text.isNotBlank()) item(-1L) {
                    GlanceText(note.text, GlanceModifier.glancePadding(vertical = 4.dp).clickable(open),
                        TextStyle(onSurface, fontSize = 14.sp), maxLines = 6)
                }
                glanceItems(rows, itemId = { it.index.toLong() }) { (index, item) ->
                    CheckBox(
                        checked = item.checked,
                        onCheckedChange = actionRunCallback<ToggleItem>(
                            actionParametersOf(KeyNote to note.id, KeyIndex to index)
                        ),
                        text = item.text,
                        style = TextStyle(
                            onSurface, fontSize = 15.sp,
                            textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
                        ),
                        maxLines = 2,
                        modifier = GlanceModifier.glanceFillMaxWidth(),
                    )
                }
                if (rows.isEmpty() && note.text.isBlank()) item(-2L) {
                    GlanceText("Пусто — нажмите на заголовок, чтобы добавить пункты",
                        GlanceModifier.clickable(open), TextStyle(onSurface))
                }
            }
        }
    }
}

class ToggleItem : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val note = parameters[KeyNote] ?: return
        val index = parameters[KeyIndex] ?: return
        Notes.toggleItem(note, index)
    }
}

class UncheckAll : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[KeyNote]?.let { Notes.uncheckAll(it) }
    }
}

class NoteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NoteWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { WidgetPrefs.remove(context, it) }
    }
}

/** Экран выбора заметки при добавлении виджета. */
@OptIn(ExperimentalMaterial3Api::class)
class NoteWidgetConfig : ComponentActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, result())
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val colors = when {
                android.os.Build.VERSION.SDK_INT >= 31 ->
                    if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                val notes by Notes.notes.collectAsState()
                Scaffold(topBar = { TopAppBar(title = { Text("Заметка для виджета") }) }) { pad ->
                    LazyColumn(
                        Modifier.fillMaxSize().padding(pad),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item { PickCard("＋ Новый список", "Создать и сразу открыть для заполнения") { newList() } }
                        items(notes, key = { it.id }) { n ->
                            val sub = if (n.items.isEmpty()) n.text.lineSequence().firstOrNull().orEmpty()
                            else "Пунктов: ${n.items.size}"
                            PickCard(n.displayTitle, sub) { bind(n.id) }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PickCard(title: String, subtitle: String, onClick: () -> Unit) {
        Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            }
        }
    }

    private fun result() = android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)

    private fun bind(noteId: Long) {
        WidgetPrefs.set(this, widgetId, noteId)
        Notes.refreshWidgets()
        setResult(RESULT_OK, result())
        finish()
    }

    private fun newList() {
        val note = Note(System.currentTimeMillis(), title = "Список", items = listOf(CheckItem("")))
        Notes.upsert(note)
        bind(note.id)
        startActivity(
            android.content.Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_OPEN, note.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

package ru.notes.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle

private val KeyId = ActionParameters.Key<Long>("id")
private val KeyOpen = ActionParameters.Key<Long>(EXTRA_OPEN)
private val KeyNew = ActionParameters.Key<Boolean>(EXTRA_NEW)

class NotesWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val notes by Notes.notes.collectAsState()
            GlanceTheme { Content(notes.filter { !it.done }) } // выполненные — в архиве
        }
    }

    @Composable
    private fun Content(notes: List<Note>) {
        val onSurface = GlanceTheme.colors.onSurface
        Column(
            GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(20.dp)
                .background(GlanceTheme.colors.widgetBackground).padding(12.dp)
        ) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Заметки",
                    GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
                    TextStyle(onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                )
                Image(
                    ImageProvider(R.drawable.ic_mic), "Надиктовать заметку",
                    GlanceModifier.size(40.dp).padding(8.dp).clickable(actionStartActivity<VoiceActivity>()),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                )
                Image(
                    ImageProvider(R.drawable.ic_add), "Новая заметка",
                    GlanceModifier.size(40.dp).padding(8.dp)
                        .clickable(actionStartActivity<MainActivity>(actionParametersOf(KeyNew to true))),
                    colorFilter = ColorFilter.tint(GlanceTheme.colors.primary),
                )
            }
            if (notes.isEmpty()) {
                Text("Пока пусто — нажмите +", GlanceModifier.padding(top = 8.dp), TextStyle(onSurface))
            } else {
                LazyColumn {
                    items(notes, itemId = { it.id }) { note ->
                        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            CheckBox(
                                checked = note.done,
                                onCheckedChange = actionRunCallback<ToggleDone>(actionParametersOf(KeyId to note.id)),
                            )
                            val progress = if (note.items.isEmpty()) "" else
                                "  ${note.items.count { it.checked }}/${note.items.size}"
                            Text(
                                note.displayTitle + progress,
                                GlanceModifier.defaultWeight().padding(vertical = 6.dp)
                                    .clickable(actionStartActivity<MainActivity>(actionParametersOf(KeyOpen to note.id))),
                                TextStyle(
                                    onSurface, fontSize = 15.sp,
                                    textDecoration = if (note.done) TextDecoration.LineThrough else TextDecoration.None,
                                ),
                                maxLines = 2,
                            )
                        }
                    }
                }
            }
        }
    }
}

class ToggleDone : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[KeyId]?.let { Notes.toggleDone(it) }
    }
}

class NotesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NotesWidget()
}

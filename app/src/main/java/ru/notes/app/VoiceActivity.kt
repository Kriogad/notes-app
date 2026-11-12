package ru.notes.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale

/**
 * Голос с виджета: прозрачный экран, сразу распознавание речи, сохранили — и обратно на рабочий стол.
 * EXTRA_VOICE_TO — id заметки, куда добавить пункты; без него создаётся новая заметка.
 */
class VoiceActivity : ComponentActivity() {
    private val voice = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { save(it) }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val to = intent.getLongExtra(EXTRA_VOICE_TO, -1)
        val prompt = Notes.get(to)?.let { "Добавить в «${it.displayTitle}»" } ?: "Новая заметка"
        try {
            voice.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Голосовой ввод недоступен на этом устройстве", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun save(text: String) {
        val note = Notes.get(intent.getLongExtra(EXTRA_VOICE_TO, -1))
        if (note != null) {
            val new = splitItems(text).map { DateParser.item(it) }
            Notes.upsert(note.copy(items = note.items.filter { it.text.isNotBlank() } + new))
            Toast.makeText(this, "Добавлено: " + new.joinToString(", ") { it.text }, Toast.LENGTH_SHORT).show()
            DateParser.toast(this, new)
        } else {
            val n = DateParser.apply(Note(System.currentTimeMillis(), text = text.replaceFirstChar { it.uppercase() }), text)
            Notes.upsert(n)
            Toast.makeText(this, "Заметка сохранена", Toast.LENGTH_SHORT).show()
            DateParser.toast(this, emptyList(), n)
        }
    }
}

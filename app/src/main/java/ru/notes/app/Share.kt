package ru.notes.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import kotlinx.coroutines.launch

/** Отправить заметку в мессенджер: текст со списком, фото — вложениями. */
object Share {
    fun text(n: Note): String = buildString {
        if (n.title.isNotBlank()) appendLine(n.title)
        if (n.text.isNotBlank()) appendLine(n.text)
        n.items.filter { it.text.isNotBlank() }.forEach { appendLine((if (it.checked) "✅ " else "⬜ ") + it.text) }
    }.trim()

    fun send(ctx: Context, n: Note, secret: Boolean) {
        val files = if (secret) emptyList() else n.attachments.map {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", Attachments.file(ctx, it))
        }
        val intent = if (files.isEmpty()) Intent(Intent.ACTION_SEND).setType("text/plain")
        else Intent(Intent.ACTION_SEND_MULTIPLE).setType(if (n.attachments.all { it.isImage }) "image/*" else "*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
            .apply {
                clipData = ClipData.newRawUri("", files.first()).also { c -> files.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        intent.putExtra(Intent.EXTRA_TEXT, text(n))
        ctx.startActivity(Intent.createChooser(intent, "Отправить заметку").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- экспорт файлом ----------

    private fun safeName(n: Note) = n.displayTitle.replace(Regex("[^\\wА-Яа-яЁё .\\-]"), "").trim()
        .ifBlank { "заметка" }.take(60)

    private fun exportDir(ctx: Context) = java.io.File(ctx.cacheDir, "exports").apply { mkdirs() }

    private fun shareFile(ctx: Context, f: java.io.File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val intent = Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Заметка текстовым файлом. */
    fun exportTxt(ctx: Context, n: Note) {
        runCatching {
            val f = java.io.File(exportDir(ctx), safeName(n) + ".txt")
            f.writeText(text(n))
            shareFile(ctx, f, "text/plain", "Экспорт заметки")
        }.onFailure { Toast.makeText(ctx, "Не удалось сохранить файл", Toast.LENGTH_LONG).show() }
    }

    /** Заметка в PDF (текст и пункты; вложения не включаются). */
    fun exportPdf(ctx: Context, n: Note) {
        runCatching {
            val pageW = 595; val pageH = 842; val margin = 56f
            val doc = android.graphics.pdf.PdfDocument()
            val titlePaint = android.graphics.Paint().apply {
                textSize = 22f; isFakeBoldText = true; color = android.graphics.Color.BLACK
            }
            val bodyPaint = android.graphics.Paint().apply { textSize = 14f; color = android.graphics.Color.BLACK }
            val dimPaint = android.graphics.Paint().apply { textSize = 11f; color = android.graphics.Color.GRAY }
            var pageNum = 1
            var page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).build())
            var y = margin

            fun newPage() {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).build())
                y = margin
            }

            fun drawLines(raw: String, paint: android.graphics.Paint, indent: Float = 0f) {
                val maxW = pageW - margin * 2 - indent
                raw.split("\n").forEach { paragraph ->
                    var line = paragraph
                    if (line.isEmpty()) { y += paint.textSize * 0.7f; return@forEach }
                    while (line.isNotEmpty()) {
                        val count = paint.breakText(line, true, maxW, null)
                        if (y > pageH - margin) newPage()
                        page.canvas.drawText(line.substring(0, count), margin + indent, y, paint)
                        y += paint.textSize * 1.35f
                        line = line.substring(count)
                    }
                }
            }

            if (n.title.isNotBlank()) { drawLines(n.title, titlePaint); y += 10f }
            if (n.text.isNotBlank()) { drawLines(n.text, bodyPaint); y += 8f }
            n.items.filter { it.text.isNotBlank() }.forEach {
                drawLines((if (it.checked) "☑ " else "☐ ") + it.text, bodyPaint)
            }
            if (n.attachments.isNotEmpty()) {
                y += 8f
                drawLines("Вложений: ${n.attachments.size} (в PDF не включаются)", dimPaint)
            }
            y = pageH - margin / 2
            page.canvas.drawText("Приложение «Заметки» · Kriogad", margin, y, dimPaint)
            doc.finishPage(page)

            val f = java.io.File(exportDir(ctx), safeName(n) + ".pdf")
            f.outputStream().use { doc.writeTo(it) }
            doc.close()
            shareFile(ctx, f, "application/pdf", "Экспорт заметки")
        }.onFailure { Toast.makeText(ctx, "Не удалось собрать PDF", Toast.LENGTH_LONG).show() }
    }
}

/** «Поделиться» из других приложений → в новую или выбранную заметку. */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        }
        if (text.isEmpty() && uris.isEmpty()) { finish(); return }

        setContent {
            NotesTheme {
                val scope = rememberCoroutineScope()
                val notes by Notes.notes.collectAsState()
                var busy by remember { mutableStateOf(false) }
                fun save(target: Note?) {
                    busy = true
                    scope.launch {
                        val att = uris.mapNotNull { Attachments.import(this@ShareActivity, it, secret = false) }
                        if (target == null) {
                            val n = Note(System.currentTimeMillis(), title = subject, text = text, attachments = att)
                            Notes.upsert(DateParser.apply(n, text))
                        } else {
                            val n = Notes.get(target.id) ?: target
                            // В список покупок короткая строка ложится пунктом, остальное — в текст
                            val asItem = n.items.isNotEmpty() && text.isNotEmpty() && '\n' !in text && text.length <= 100
                            Notes.upsert(
                                n.copy(
                                    text = if (asItem || text.isEmpty()) n.text else listOf(n.text, text).filter { it.isNotBlank() }.joinToString("\n"),
                                    items = if (asItem) n.items + DateParser.item(text) else n.items,
                                    attachments = n.attachments + att,
                                )
                            )
                        }
                        Toast.makeText(this@ShareActivity, "Сохранено в Заметки", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text("Сохранить в Заметки") },
                    text = {
                        Column {
                            val preview = listOf(subject, text).filter { it.isNotBlank() }.joinToString(" — ")
                            if (preview.isNotBlank()) Text(preview, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium)
                            if (uris.isNotEmpty()) Text("📎 файлов: ${uris.size}", style = MaterialTheme.typography.bodyMedium)
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                item {
                                    TextButton(enabled = !busy, onClick = { save(null) }, modifier = Modifier.fillMaxWidth()) {
                                        Text("＋ Новая заметка", Modifier.fillMaxWidth())
                                    }
                                }
                                items(notes.filter { !it.done }, key = { it.id }) { n ->
                                    TextButton(enabled = !busy, onClick = { save(n) }, modifier = Modifier.fillMaxWidth()) {
                                        Text(n.displayTitle, Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Отмена") } },
                )
            }
        }
    }
}

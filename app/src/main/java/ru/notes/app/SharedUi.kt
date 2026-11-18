package ru.notes.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// ---------------- Список общих проектов ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedScreen(host: Host, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val projects by Shared.projects.collectAsState()
    val allItems by Shared.items.collectAsState()
    var askJoin by remember { mutableStateOf(false) }
    var askCreate by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<Shared.Project?>(null) }
    var askLeave by remember { mutableStateOf<Shared.Project?>(null) }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Общие списки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
        floatingActionButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { askJoin = true }) { Text("По коду") }
                FloatingActionButton(onClick = { askCreate = true }) { Icon(Icons.Filled.Add, "Создать общий список") }
            }
        },
    ) { pad ->
        if (!Shared.available) {
            Text(
                "Синхронизация не настроена в этой сборке.\nНужен файл google-services.json от Firebase.",
                color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(pad).padding(32.dp),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (projects.isEmpty()) item {
                    Text(
                        "Здесь будут списки, общие с другими людьми.\n\n" +
                            "Создайте список и отправьте код другу — или нажмите «По коду» и введите присланный вам код.",
                        color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
                items(projects, key = { it.code }) { p ->
                    val its = allItems[p.code].orEmpty()
                    Card(
                        onClick = { onOpen(p.code) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (its.isEmpty()) "Код: ${p.code}" else
                                        "${its.count { it.checked }}/${its.size} · код: ${p.code}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            IconButton(onClick = {
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_TEXT,
                                        "Присоединяйся к списку «${p.title}» в приложении «Заметки» — код: ${p.code}\n" +
                                            "(в приложении: Общие списки → По коду)\n" +
                                            "На компьютере: https://kriogad.github.io/notes-app/?code=${p.code}")
                                ctx.startActivity(android.content.Intent.createChooser(send, "Пригласить"))
                            }) { Icon(Icons.Filled.Share, "Пригласить") }
                        }
                    }
                }
            }
        }
    }

    if (askCreate) {
        var title by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askCreate = false },
            title = { Text("Новый общий список") },
            text = {
                OutlinedTextField(title, { title = it }, label = { Text("Название") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = title.isNotBlank(), onClick = {
                    askCreate = false
                    val code = Shared.create(title.trim())
                    if (code == null) Toast.makeText(ctx, "Нет соединения с облаком", Toast.LENGTH_LONG).show()
                    else created = Shared.projects.value.firstOrNull { it.code == code }
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { askCreate = false }) { Text("Отмена") } },
        )
    }

    if (askJoin) {
        var code by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { askJoin = false },
            title = { Text("Присоединиться") },
            text = {
                OutlinedTextField(code, { code = it }, label = { Text("Код приглашения") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = code.isNotBlank() && !busy, onClick = {
                    busy = true
                    Shared.join(code) { ok ->
                        busy = false
                        if (ok) askJoin = false
                        else Toast.makeText(ctx, "Список с таким кодом не найден", Toast.LENGTH_LONG).show()
                    }
                }) { Text(if (busy) "Проверяю…" else "Войти") }
            },
            dismissButton = { TextButton(onClick = { askJoin = false }) { Text("Отмена") } },
        )
    }

    created?.let { p ->
        AlertDialog(
            onDismissRequest = { created = null },
            title = { Text("Список «${p.title}» создан") },
            text = { Text("Код для приглашения:\n\n${p.code}\n\nОтправьте его тому, с кем делитесь списком.\n\nНа компьютере список открывается по ссылке:\nhttps://kriogad.github.io/notes-app/?code=${p.code}") },
            confirmButton = {
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT,
                            "Присоединяйся к списку «${p.title}» в приложении «Заметки» — код: ${p.code}\n" +
                                "(в приложении: Общие списки → По коду)\n" +
                                "На компьютере: https://kriogad.github.io/notes-app/?code=${p.code}")
                    ctx.startActivity(android.content.Intent.createChooser(send, "Пригласить"))
                    created = null
                }) { Text("Отправить код") }
            },
            dismissButton = { TextButton(onClick = { created = null }) { Text("Позже") } },
        )
    }
}

// ---------------- Общий список: пункты ----------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SharedProjectScreen(host: Host, code: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val projects by Shared.projects.collectAsState()
    val allItems by Shared.items.collectAsState()
    val project = projects.firstOrNull { it.code == code }
    val its = allItems[code].orEmpty()
    var newText by remember { mutableStateOf("") }
    var askDelete by remember { mutableStateOf<Shared.Item?>(null) }
    var askLeave by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.title ?: "Список", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    TextButton(onClick = { askLeave = true }) { Text("Покинуть") }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 12.dp),
            ) {
                if (its.isEmpty()) item {
                    Text(
                        "Пусто. Добавьте первый пункт внизу — он сразу появится у всех участников.",
                        color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    )
                }
                items(its, key = { it.id }) { item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { Shared.toggle(code, item) },
                                onLongClick = { askDelete = item },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = item.checked, onCheckedChange = { Shared.toggle(code, item) })
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                textDecoration = if (item.checked) TextDecoration.LineThrough else TextDecoration.None,
                            ),
                            color = if (item.checked) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            // Добавление пункта: текстом или голосом
            Row(
                Modifier.fillMaxWidth().imePadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    newText, { newText = it }, placeholder = { Text("Новый пункт…") },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    host.listen { t ->
                        splitItems(t).forEach { Shared.addItem(code, it) }
                    }
                }) { Text("🎤") }
                Button(
                    enabled = newText.isNotBlank(),
                    onClick = { Shared.addItem(code, newText.trim()); newText = "" },
                ) { Text("→") }
            }
        }
    }

    askDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text("Удалить пункт?") },
            text = { Text("«${item.text}» — исчезнет у всех участников.") },
            confirmButton = {
                TextButton(onClick = { Shared.deleteItem(code, item.id); askDelete = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text("Отмена") } },
        )
    }

    if (askLeave) AlertDialog(
        onDismissRequest = { askLeave = false },
        title = { Text("Покинуть список?") },
        text = { Text("Список пропадёт у вас, у остальных участников останется. Вернуться можно по коду.") },
        confirmButton = {
            TextButton(onClick = { Shared.leave(code); askLeave = false; onBack() }) { Text("Покинуть") }
        },
        dismissButton = { TextButton(onClick = { askLeave = false }) { Text("Отмена") } },
    )
}

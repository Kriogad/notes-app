@file:OptIn(ExperimentalMaterial3Api::class)

package ru.notes.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val timeFmt = DateTimeFormatter.ofPattern("d MMM, HH:mm")
private fun formatTime(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)

@Composable
fun NotesApp(host: Host, openRequest: Long?, onOpenHandled: () -> Unit) {
    val notes by Notes.notes.collectAsState()
    val vaultOpen by Vault.unlocked.collectAsState()
    var section by rememberSaveable { mutableStateOf(Section.Main) }
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(openRequest) {
        openRequest?.let {
            section = Section.Main
            editing = if (it == NEW_NOTE) System.currentTimeMillis() else it
            onOpenHandled()
        }
    }

    val secret = section == Section.Vault
    val id = editing
    when {
        id != null && (!secret || vaultOpen) -> key(id) {
            EditorScreen(id, if (secret) Vault else Notes, secret, host, onClose = { editing = null })
        }
        section == Section.Settings -> SettingsScreen(onBack = { section = Section.Main })
        secret -> VaultScreen(
            host,
            onBack = { Vault.lock(); section = Section.Main },
            onOpen = { editing = it },
            onNew = { editing = System.currentTimeMillis() },
        )
        else -> ListScreen(
            notes,
            onOpen = { editing = it },
            onNew = { editing = System.currentTimeMillis() },
            onVault = { section = Section.Vault },
            onSettings = { section = Section.Settings },
        )
    }
}

enum class Section { Main, Vault, Settings }

/** Что экранам нужно от Activity. */
interface Host {
    fun listen(onText: (String) -> Unit)
    fun askNotificationPermission()
    fun bioAvailable(): Boolean
    fun biometric(cipher: javax.crypto.Cipher, title: String, onOk: (javax.crypto.Cipher) -> Unit)
}

// ---------------- Список, архив, поиск ----------------

fun Note.matches(q: String) = title.contains(q, true) || text.contains(q, true) ||
    items.any { it.text.contains(q, true) }

@Composable
private fun ListScreen(
    notes: List<Note>,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    onVault: () -> Unit,
    onSettings: () -> Unit,
) {
    var archive by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val archived = notes.count { it.done }

    // Поиск идёт по всем заметкам, включая архив
    val shown = when {
        searching -> if (query.isBlank()) emptyList() else notes.filter { it.matches(query.trim()) }
        archive -> notes.filter { it.done }
        else -> notes.filter { !it.done }
    }
    BackHandler(enabled = searching || archive) {
        if (searching) { searching = false; query = "" } else archive = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when {
                        searching -> {
                            val focus = remember { FocusRequester() }
                            LaunchedEffect(Unit) { focus.requestFocus() }
                            PlainField(query, { query = it }, "Поиск", MaterialTheme.typography.titleMedium,
                                Modifier.fillMaxWidth().focusRequester(focus))
                        }
                        archive -> Text("Архив")
                        else -> Text("Заметки")
                    }
                },
                navigationIcon = {
                    if (searching || archive) IconButton(onClick = {
                        if (searching) { searching = false; query = "" } else archive = false
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    if (!searching) IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Поиск") }
                    if (!searching && !archive) {
                        TextButton(onClick = { archive = true }) { Text("Архив ($archived)") }
                        IconButton(onClick = onVault) { Icon(Icons.Filled.Lock, "Секретные заметки") }
                        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Настройки и резервная копия") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!searching && !archive) {
                FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "Новая заметка") }
            }
        },
    ) { pad ->
        if (shown.isEmpty()) {
            val empty = when {
                searching -> if (query.isBlank()) "Введите слово для поиска" else "Ничего не найдено"
                archive -> "В архиве пусто.\nСюда попадают выполненные заметки."
                else -> "Заметок пока нет.\nНажмите +, чтобы добавить."
            }
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text(empty, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(pad).imePadding(),
                contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { it.id }) { note ->
                    NoteCard(note, Notes, Modifier.animateItem()) { onOpen(note.id) }
                }
            }
        }
    }
}

@Composable
fun NoteCard(note: Note, store: NoteStore, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(end = 12.dp, top = 4.dp, bottom = 8.dp)) {
            Checkbox(checked = note.done, onCheckedChange = { store.setDone(note.id, it) })
            Column(Modifier.weight(1f).padding(top = 12.dp).alpha(if (note.done) 0.6f else 1f)) {
                Text(
                    note.displayTitle,
                    style = MaterialTheme.typography.titleMedium.strike(note.done),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (note.title.isNotBlank() && note.text.isNotBlank()) {
                    Text(note.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                note.items.take(4).forEach {
                    Text(
                        (if (it.checked) "☑ " else "☐ ") + it.text,
                        style = MaterialTheme.typography.bodyMedium.strike(it.checked),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (note.items.size > 4) {
                    Text("… ещё ${note.items.size - 4}", style = MaterialTheme.typography.bodySmall)
                }
                note.remindAt?.let {
                    val past = it < System.currentTimeMillis()
                    Text(
                        "🔔 " + formatTime(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (past) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** Делит надиктованную фразу на пункты: по запятым и союзам «и», «потом», «затем». */
fun splitItems(phrase: String): List<String> =
    phrase.split(Regex("[,;.]|\\s+(?:и|потом|затем|а также)\\s+", RegexOption.IGNORE_CASE))
        .map { it.trim() }.filter { it.isNotEmpty() }
        .map { it.replaceFirstChar { c -> c.uppercase() } }

private fun TextStyle.strike(on: Boolean) = if (on) copy(textDecoration = TextDecoration.LineThrough) else this

// ---------------- Редактор ----------------

@Composable
private fun EditorScreen(id: Long, store: NoteStore, secret: Boolean, host: Host, onClose: () -> Unit) {
    var note by remember { mutableStateOf(store.get(id) ?: Note(id)) }
    var pickDate by remember { mutableStateOf(false) }
    var pickedDay by remember { mutableStateOf<Long?>(null) }
    var askDelete by remember { mutableStateOf(false) }

    // Автосохранение: через полсекунды после правки и при выходе
    fun persist() {
        val stored = store.get(id)
        when {
            note.isBlank -> if (stored != null) store.delete(id)
            !note.sameContent(stored) -> store.upsert(note.copy(updated = System.currentTimeMillis()))
        }
    }
    LaunchedEffect(note) { delay(500); persist() }
    DisposableEffect(Unit) { onDispose { persist() } }
    BackHandler(onBack = onClose)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    IconButton(onClick = {
                        host.listen { t -> note = note.copy(text = if (note.text.isBlank()) t else note.text.trimEnd() + "\n" + t) }
                    }) { Icon(painterResource(R.drawable.ic_mic), "Надиктовать текст") }
                    // Напоминание показало бы заголовок секретной заметки в уведомлении — там его нет
                    if (!secret) IconButton(onClick = { pickDate = true }) { Icon(Icons.Filled.Notifications, "Напоминание") }
                    IconButton(onClick = { askDelete = true }) { Icon(Icons.Filled.Delete, "Удалить") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)
        ) {
            PlainField(note.title, { note = note.copy(title = it) }, "Заголовок", MaterialTheme.typography.titleLarge)

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Checkbox(note.done, { note = note.copy(done = it) })
                Text(if (secret) "Выполнено" else "Выполнено (в архив)")
                note.remindAt?.let { at ->
                    AssistChip(
                        onClick = { pickDate = true },
                        label = { Text(formatTime(at)) },
                        leadingIcon = { Icon(Icons.Filled.Notifications, null) },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    IconButton(onClick = { note = note.copy(remindAt = null) }) {
                        Icon(Icons.Filled.Close, "Убрать напоминание")
                    }
                }
            }

            PlainField(note.text, { note = note.copy(text = it) }, "Текст заметки", MaterialTheme.typography.bodyLarge)

            note.items.forEachIndexed { i, item ->
                fun update(new: CheckItem?) {
                    note = note.copy(items = note.items.toMutableList().apply { if (new == null) removeAt(i) else set(i, new) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                    Checkbox(item.checked, { update(item.copy(checked = it)) })
                    PlainField(
                        item.text, { update(item.copy(text = it)) }, "Пункт",
                        MaterialTheme.typography.bodyLarge.strike(item.checked), Modifier.weight(1f),
                    )
                    IconButton(onClick = { update(null) }) { Icon(Icons.Filled.Close, "Удалить пункт") }
                }
            }
            Row(Modifier.padding(start = 8.dp)) {
                TextButton(onClick = { note = note.copy(items = note.items + CheckItem("")) }) {
                    Icon(Icons.Filled.Add, null)
                    Text("Пункт с галочкой")
                }
                // «молоко, хлеб и яйца» → три пункта
                TextButton(onClick = {
                    host.listen { t ->
                        val new = splitItems(t).map { CheckItem(it) }
                        note = note.copy(items = note.items.filter { it.text.isNotBlank() } + new)
                    }
                }) {
                    Icon(painterResource(R.drawable.ic_mic), null)
                    Text("Надиктовать пункты")
                }
            }
        }
    }

    if (askDelete) AlertDialog(
        onDismissRequest = { askDelete = false },
        title = { Text("Удалить заметку?") },
        confirmButton = {
            TextButton(onClick = { note = Note(id); store.delete(id); onClose() }) { Text("Удалить") }
        },
        dismissButton = { TextButton(onClick = { askDelete = false }) { Text("Отмена") } },
    )
    if (pickDate) DayDialog(
        initial = note.remindAt,
        onDismiss = { pickDate = false },
        onPick = { pickDate = false; pickedDay = it },
    )
    pickedDay?.let { day ->
        TimeDialog(
            initial = note.remindAt,
            onDismiss = { pickedDay = null },
            onPick = { h, m ->
                pickedDay = null
                val date = Instant.ofEpochMilli(day).atZone(ZoneOffset.UTC).toLocalDate()
                val at = LocalDateTime.of(date, LocalTime.of(h, m))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                note = note.copy(remindAt = at, done = false)
                host.askNotificationPermission()
            },
        )
    }
}

@Composable
fun PlainField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    style: TextStyle,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, style = style) },
        textStyle = style,
        modifier = modifier,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun DayDialog(initial: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val todayUtc = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val initialUtc = initial?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = maxOf(initialUtc ?: todayUtc, todayUtc),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayUtc
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let(onPick) }) { Text("Далее") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    ) { DatePicker(state) }
}

@Composable
private fun TimeDialog(initial: Long?, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val t = initial?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() }
        ?: LocalTime.now().plusHours(1).withMinute(0)
    val state = rememberTimePickerState(t.hour, t.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onPick(state.hour, state.minute) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        text = { TimePicker(state) },
    )
}

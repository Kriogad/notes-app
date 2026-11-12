@file:OptIn(ExperimentalMaterial3Api::class)

package ru.notes.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.rememberDatePickerState
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
            EditorScreen(id, if (secret) Vault else Notes, secret, host, onClose = { editing = null }, onOpen = { editing = it })
        }
        section == Section.Settings -> SettingsScreen(host, onBack = { section = Section.Main })
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
    fun pickPhotos(onPicked: (List<android.net.Uri>) -> Unit)
    fun pickFiles(onPicked: (List<android.net.Uri>) -> Unit)
    fun pickSound(current: android.net.Uri?, onPicked: (android.net.Uri?) -> Unit)
    fun pickAudio(onPicked: (android.net.Uri) -> Unit)
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
    val ctx = LocalContext.current
    val bg by Background.image.collectAsState()
    LaunchedEffect(Unit) { Background.load(ctx) }

    Box(Modifier.fillMaxSize()) {
    bg?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    Scaffold(
        containerColor = if (bg != null) Color.Transparent else MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = if (bg != null) 0.85f else 1f)
                ),
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
            BoxWithConstraints(Modifier.fillMaxSize().padding(pad).imePadding()) {
                // Делим высоту экрана между заметками: пока места хватает, список виден целиком
                val perNote = (maxHeight - 88.dp) / shown.size - 64.dp
                val maxItems = maxOf(4, (perNote / 24.dp).toInt())
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shown, key = { it.id }) { note ->
                        NoteCard(note, Notes, Modifier.animateItem(), maxItems) { onOpen(note.id) }
                    }
                }
            }
        }
    }
    }
}

@Composable
fun NoteCard(note: Note, store: NoteStore, modifier: Modifier, maxItems: Int = 4, onClick: () -> Unit) {
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
                // Невыполненные пункты первыми — их важнее видеть
                note.items.sortedBy { it.checked }.take(maxItems).forEach {
                    Text(
                        (if (it.checked) "☑ " else "☐ ") + it.text +
                            (it.remindAt?.let { r -> "  🔔 " + formatTime(r) } ?: ""),
                        style = MaterialTheme.typography.bodyMedium.strike(it.checked),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (note.items.size > maxItems) {
                    Text("… ещё ${note.items.size - maxItems}", style = MaterialTheme.typography.bodySmall)
                }
                if (note.attachments.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        note.attachments.filter { it.isImage }.take(3).forEach { Thumb(it, 56.dp) }
                        Text("📎 ${note.attachments.size}", style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 4.dp))
                    }
                }
                note.doneAt?.let {
                    Text("✓ выполнено " + formatTime(it), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
                }
                note.remindAt?.let {
                    val past = it < System.currentTimeMillis()
                    Text(
                        "🔔 " + formatTime(it) + if (note.repeat != Repeat.NONE) " · ${note.repeat.label.lowercase()}" else "",
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
private fun EditorScreen(id: Long, store: NoteStore, secret: Boolean, host: Host, onClose: () -> Unit, onOpen: (Long) -> Unit) {
    var note by remember { mutableStateOf(store.get(id) ?: Note(id)) }
    var pickDate by remember { mutableStateOf(false) }
    // Для чего ставим напоминание: 0 — вся заметка, иначе id пункта
    var target by remember { mutableStateOf(0L) }
    val targetItem = note.items.find { it.id == target }
    val targetAt = if (target == 0L) note.remindAt else targetItem?.remindAt
    val targetRepeat = if (target == 0L) note.repeat else targetItem?.repeat ?: Repeat.NONE
    val targetSound = if (target == 0L) note.sound else targetItem?.sound
    val targetVolume = if (target == 0L) note.volume else targetItem?.volume
    var pickedDay by remember { mutableStateOf<Long?>(null) }
    var pickedTime by remember { mutableStateOf<Long?>(null) }
    var askDelete by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val onPicked: (List<android.net.Uri>) -> Unit = { uris ->
        scope.launch {
            val new = uris.mapNotNull { Attachments.import(ctx, it, secret) }
            if (new.size < uris.size) Toast.makeText(ctx, "Не все файлы удалось прикрепить", Toast.LENGTH_LONG).show()
            note = note.copy(attachments = note.attachments + new)
        }
    }

    // Автосохранение: через полсекунды после правки и при выходе
    var finished by remember { mutableStateOf(false) }
    fun persist() {
        if (finished) return
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
                    Box {
                        IconButton(onClick = { attachMenu = true }) { Icon(painterResource(R.drawable.ic_attach), "Прикрепить") }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            DropdownMenuItem(text = { Text("Фото из галереи") }, onClick = { attachMenu = false; host.pickPhotos(onPicked) })
                            DropdownMenuItem(text = { Text("Файл") }, onClick = { attachMenu = false; host.pickFiles(onPicked) })
                        }
                    }
                    // Напоминание показало бы заголовок секретной заметки в уведомлении — там его нет
                    if (!secret) IconButton(onClick = { target = 0L; pickDate = true }) { Icon(Icons.Filled.Notifications, "Напоминание") }
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
                Checkbox(note.done, { checked ->
                    note = note.copy(done = checked)
                    // Повторяющаяся: копия уходит в историю, открывать дальше нечего
                    if (checked && !secret && note.repeat != Repeat.NONE && note.remindAt != null) {
                        persist(); finished = true; onClose()
                    }
                })
                Text(if (secret) "Выполнено" else "Выполнено (в архив)")
                note.remindAt?.let { at ->
                    AssistChip(
                        onClick = { target = 0L; pickDate = true },
                        label = { Text(formatTime(at) + if (note.repeat != Repeat.NONE) " ↻" else "") },
                        leadingIcon = { Icon(Icons.Filled.Notifications, null) },
                        modifier = Modifier.padding(start = 12.dp),
                    )
                    IconButton(onClick = { note = note.copy(remindAt = null, repeat = Repeat.NONE) }) {
                        Icon(Icons.Filled.Close, "Убрать напоминание")
                    }
                }
            }

            PlainField(note.text, { note = note.copy(text = it) }, "Текст заметки", MaterialTheme.typography.bodyLarge)

            fun remove(a: Attachment) {
                Attachments.delete(ctx, a)
                note = note.copy(attachments = note.attachments - a)
            }
            val images = note.attachments.filter { it.isImage }
            if (images.isNotEmpty()) Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                images.forEach { a ->
                    Box {
                        Thumb(a, 110.dp) { Attachments.open(ctx, a) }
                        IconButton(onClick = { remove(a) }, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) {
                            Icon(Icons.Filled.Close, "Убрать фото", tint = Color.White)
                        }
                    }
                }
            }
            note.attachments.filter { !it.isImage }.forEach { a ->
                Card(onClick = { Attachments.open(ctx, a) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
                        Icon(painterResource(R.drawable.ic_attach), null)
                        Text(a.name, Modifier.weight(1f).padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { remove(a) }) { Icon(Icons.Filled.Close, "Убрать файл") }
                    }
                }
            }

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
                    if (!secret) IconButton(onClick = { target = item.id; pickDate = true }) {
                        Icon(Icons.Filled.Notifications, "Напоминание для пункта",
                            tint = if (item.remindAt != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { update(null) }) { Icon(Icons.Filled.Close, "Удалить пункт") }
                }
                item.remindAt?.let { at ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 52.dp)) {
                        TextButton(onClick = { target = item.id; pickDate = true }) {
                            Text("🔔 " + formatTime(at) + if (item.repeat != Repeat.NONE) " · ${item.repeat.label.lowercase()}" else "",
                                style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = { update(item.copy(remindAt = null, repeat = Repeat.NONE)) }) {
                            Text("убрать", style = MaterialTheme.typography.labelMedium)
                        }
                    }
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
            if (!secret) History(note, onOpen)
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
        initial = targetAt,
        onDismiss = { pickDate = false },
        onPick = { pickDate = false; pickedDay = it },
    )
    pickedDay?.let { day ->
        TimeDialog(
            initial = targetAt,
            onDismiss = { pickedDay = null },
            onPick = { h, m ->
                pickedDay = null
                val date = Instant.ofEpochMilli(day).atZone(ZoneOffset.UTC).toLocalDate()
                val at = LocalDateTime.of(date, LocalTime.of(h, m))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                pickedTime = at
            },
        )
    }
    pickedTime?.let { at ->
        RepeatDialog(
            initial = targetRepeat,
            initialSound = targetSound,
            initialVolume = targetVolume,
            host = host,
            onDismiss = { pickedTime = null },
            onPick = { rep, snd, vol ->
                pickedTime = null
                note = if (target == 0L) note.copy(remindAt = at, repeat = rep, sound = snd, volume = vol, done = false)
                else note.copy(items = note.items.map {
                    if (it.id == target) it.copy(remindAt = at, repeat = rep, sound = snd, volume = vol, checked = false) else it
                })
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

/** Прошлые разы повторяющейся заметки (с фото) — например, показания счётчика по месяцам. */
@Composable
private fun History(note: Note, onOpen: (Long) -> Unit) {
    val all by Notes.notes.collectAsState()
    val history = all.filter { it.done && it.doneAt != null && it.id != note.id && it.seriesId == note.seriesId }
        .sortedByDescending { it.doneAt }
    if (history.isEmpty()) return
    Text("История (${history.size})", style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
    history.forEach { h ->
        Card(onClick = { onOpen(h.id) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                h.attachments.firstOrNull { it.isImage }?.let { Thumb(it, 56.dp) }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("✓ " + formatTime(h.doneAt!!), style = MaterialTheme.typography.titleSmall)
                    val about = h.text.lineSequence().firstOrNull { it.isNotBlank() }
                        ?: "📎 ${h.attachments.size}".takeIf { h.attachments.isNotEmpty() } ?: ""
                    if (about.isNotBlank()) Text(about, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun RepeatDialog(
    initial: Repeat,
    initialSound: String?,
    initialVolume: Int?,
    host: Host,
    onDismiss: () -> Unit,
    onPick: (Repeat, String?, Int?) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sel by remember { mutableStateOf(initial) }
    var snd by remember { mutableStateOf(initialSound) }
    var vol by remember { mutableStateOf(initialVolume) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Повторять") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Repeat.entries.forEach { r ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(selected = sel == r, onClick = { sel = r }),
                    ) {
                        RadioButton(selected = sel == r, onClick = { sel = r })
                        Text(r.label)
                    }
                }
                Text("Мелодия: " + Reminders.titleOf(ctx, snd), style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                TextButton(onClick = {
                    host.pickSound(snd?.let(android.net.Uri::parse) ?: Reminders.soundUri(ctx)) { u -> if (u != null) snd = u.toString() }
                }) { Text("Из телефона") }
                TextButton(onClick = {
                    host.pickAudio { src ->
                        scope.launch {
                            val u = Reminders.importSound(ctx, src)
                            if (u == null) Toast.makeText(ctx, "Не удалось взять этот файл", Toast.LENGTH_LONG).show()
                            else snd = u.toString()
                        }
                    }
                }) { Text("Свой MP3") }
                if (snd != null) TextButton(onClick = { snd = null }) { Text("Как в настройках") }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                    Text("Своя громкость" + (vol?.let { " — $it%" } ?: ""), Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall)
                    Switch(checked = vol != null, onCheckedChange = { vol = if (it) Reminders.volume(ctx) else null })
                }
                vol?.let { v ->
                    Slider(value = v.toFloat(), onValueChange = { vol = it.toInt() }, valueRange = 10f..100f, steps = 8)
                }
                TextButton(onClick = { Reminders.test(ctx, snd, vol) }) { Text("▶ Послушать") }
            }
        },
        confirmButton = { TextButton(onClick = { SoundService.stop(ctx); onPick(sel, snd, vol) }) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun TimeDialog(initial: Long?, onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    val t = initial?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() }
        ?: LocalTime.now().plusHours(1).withMinute(0)
    var h by remember { mutableStateOf(t.hour) }
    var m by remember { mutableStateOf(t.minute) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Время") },
        confirmButton = { TextButton(onClick = { onPick(h, m) }) { Text("Далее") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Wheel(24, t.hour) { h = it }
                Text(":", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 8.dp))
                Wheel(60, t.minute) { m = it }
            }
        },
    )
}

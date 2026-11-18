@file:OptIn(ExperimentalMaterial3Api::class)

package ru.notes.app

import android.os.Build
import android.widget.Toast
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.launch

// ---------------- Секретный раздел ----------------

@Composable
fun VaultScreen(host: Host, onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: (String) -> Unit) {
    val unlocked by Vault.unlocked.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(0) } // 0 — заметки, 1 — фото
    var cat by remember { mutableStateOf<String?>(null) } // null — список категорий, "*" — все заметки
    var importing by remember { mutableStateOf(false) }
    var askNewCat by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var vaultQuery by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    BackHandler(enabled = searching) { searching = false; vaultQuery = "" }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searching) {
                        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        PlainField(vaultQuery, { vaultQuery = it }, "Поиск по секретке",
                            MaterialTheme.typography.titleMedium,
                            Modifier.fillMaxWidth().focusRequester(focus))
                    } else Text("Секретный раздел")
                },
                navigationIcon = {
                    if (searching) IconButton(onClick = { searching = false; vaultQuery = "" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    } else IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = {
                    if (unlocked && !searching && tab == 0) {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, "Поиск")
                        }
                    }
                    if (unlocked) IconButton(onClick = onBack) { Icon(Icons.Filled.Lock, "Закрыть раздел") }
                },
            )
        },
        floatingActionButton = {
            if (unlocked) {
                var addMenu by remember { mutableStateOf(false) }
                Box {
                    FloatingActionButton(onClick = {
                        when {
                            tab == 1 -> addMenu = true
                            cat == null -> askNewCat = true
                            else -> onNew(cat!!)
                        }
                    }) {
                        Icon(Icons.Filled.Add, when {
                            tab == 1 -> "Добавить фото"
                            cat == null -> "Новая категория"
                            else -> "Новая секретная заметка"
                        })
                    }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Снять фото") }, onClick = {
                            addMenu = false
                            host.takePhoto { uri ->
                                if (uri == null) return@takePhoto
                                scope.launch {
                                    importing = true
                                    val n = Vault.importPhotos(ctx, listOf(uri))
                                    importing = false
                                    // временный снимок из кэша больше не нужен
                                    ctx.contentResolver.delete(uri, null, null)
                                    if (n == 0) Toast.makeText(ctx, "Не удалось добавить фото", Toast.LENGTH_LONG).show()
                                }
                            }
                        })
                        DropdownMenuItem(text = { Text("Из галереи") }, onClick = {
                            addMenu = false
                            host.pickPhotos { uris ->
                                scope.launch {
                                    importing = true
                                    val n = Vault.importPhotos(ctx, uris)
                                    importing = false
                                    if (n < uris.size) Toast.makeText(ctx, "Не все фото удалось добавить", Toast.LENGTH_LONG).show()
                                }
                            }
                        })
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            if (unlocked) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }) { Text("Заметки", Modifier.padding(14.dp)) }
                    Tab(selected = tab == 1, onClick = { tab = 1 }) { Text("Фото", Modifier.padding(14.dp)) }
                }
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    !unlocked -> if (!Vault.exists) CreateVault() else UnlockVault(host)
                    searching -> VaultSearchResults(vaultQuery, onOpen)
                    tab == 1 -> VaultPhotos()
                    cat != null -> VaultList(host, cat!!, onBackToCats = { cat = null }, onOpen)
                    else -> VaultCategories(host, onPick = { cat = it })
                }
                if (importing) Text(
                    "Шифрую фото…", color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp),
                )
            }
        }
    }

    if (askNewCat) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askNewCat = false },
            title = { Text("Новая категория") },
            text = {
                OutlinedTextField(name, { name = it }, label = { Text("Название: Пароли, Сайты…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    Vault.addCategory(name)
                    askNewCat = false
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { askNewCat = false }) { Text("Отмена") } },
        )
    }
}

// ---------------- Категории сейфа ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultCategories(host: Host, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val notes by Vault.notes.collectAsState()
    val cats by Vault.categories.collectAsState()
    var bio by remember { mutableStateOf(Vault.bioEnabled) }
    var manage by remember { mutableStateOf<String?>(null) } // категория под меню
    var renameCat by remember { mutableStateOf<String?>(null) }
    var deleteCat by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (Build.VERSION.SDK_INT >= 30 && host.bioAvailable()) item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text("Вход по отпечатку", Modifier.weight(1f))
                Switch(checked = bio, onCheckedChange = { on ->
                    if (on) {
                        try {
                            host.biometric(Vault.bioEncryptCipher(), "Включить вход по отпечатку") { c ->
                                Vault.bioSave(c); bio = true
                            }
                        } catch (e: Exception) {
                            Toast.makeText(ctx, "Сначала добавьте отпечаток в настройках телефона", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Vault.disableBio(); bio = false
                    }
                })
            }
        }
        item {
            CategoryCard("📒 Все заметки", notes.size, onClick = { onPick("*") })
        }
        items(cats, key = { it }) { c ->
            CategoryCard(
                "🗂 $c", notes.count { it.category == c },
                onLongClick = { manage = c },
                onClick = { onPick(c) },
            )
        }
        if (notes.any { it.category.isBlank() }) item {
            CategoryCard("📄 Без категории", notes.count { it.category.isBlank() }, onClick = { onPick("") })
        }
        item {
            Text(
                "Кнопка + создаёт категорию. Долгий тап по категории — переименовать или удалить.",
                color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
        }
    }

    manage?.let { c ->
        AlertDialog(
            onDismissRequest = { manage = null },
            title = { Text(c) },
            text = { Text("Заметок: ${notes.count { it.category == c }}") },
            confirmButton = { TextButton(onClick = { renameCat = c; manage = null }) { Text("Переименовать") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleteCat = c; manage = null }) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { manage = null }) { Text("Отмена") }
                }
            },
        )
    }

    renameCat?.let { c ->
        var name by remember { mutableStateOf(c) }
        AlertDialog(
            onDismissRequest = { renameCat = null },
            title = { Text("Переименовать категорию") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    Vault.renameCategory(c, name); renameCat = null
                }) { Text("Готово") }
            },
            dismissButton = { TextButton(onClick = { renameCat = null }) { Text("Отмена") } },
        )
    }

    deleteCat?.let { c ->
        AlertDialog(
            onDismissRequest = { deleteCat = null },
            title = { Text("Удалить категорию «$c»?") },
            text = { Text("Сами заметки останутся — перейдут в «Без категории».") },
            confirmButton = {
                TextButton(onClick = { Vault.deleteCategory(c); deleteCat = null }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteCat = null }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CategoryCard(title: String, count: Int, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------------- Секретные фото ----------------

@Composable
private fun VaultPhotos() {
    val ctx = LocalContext.current
    val photos by Vault.photos.collectAsState()
    var viewing by remember { mutableStateOf<Vault.VaultPhoto?>(null) }
    var askDelete by remember { mutableStateOf<Vault.VaultPhoto?>(null) }

    if (photos.isEmpty()) {
        Text(
            "Здесь пока пусто. Нажмите +, чтобы добавить фото — они шифруются и не видны в галерее телефона.",
            color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(32.dp),
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        gridItems(photos, key = { it.file }) { p ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // тап — открыть, долгий тап — удалить
                Thumb(Attachment(p.file, "фото", "image/*"), 110.dp,
                    onClick = { viewing = p }, onLongClick = { askDelete = p })
            }
        }
    }

    // Просмотр на весь экран
    viewing?.let { p ->
        Dialog(
            onDismissRequest = { viewing = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                val bmp by produceState<ImageBitmap?>(null, p.file) {
                    value = withContext(Dispatchers.IO) {
                        Vault.decrypt(File(Attachments.dir(ctx), p.file))
                            ?.let { Images.decode(it, 2000)?.asImageBitmap() }
                    }
                }
                bmp?.let { Image(it, "Секретное фото", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                Row(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    IconButton(onClick = { askDelete = p; viewing = null }) {
                        Icon(Icons.Filled.Delete, "Удалить", tint = Color.White)
                    }
                    IconButton(onClick = { viewing = null }) {
                        Icon(Icons.Filled.Close, "Закрыть", tint = Color.White)
                    }
                }
            }
        }
    }

    askDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            title = { Text("Удалить фото?") },
            text = { Text("Восстановить будет нельзя.") },
            confirmButton = {
                TextButton(onClick = { Vault.deletePhoto(ctx, p); askDelete = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { askDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CreateVault() {
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Придумайте пароль для секретных заметок", style = MaterialTheme.typography.titleMedium)
        Text(
            "Заметки шифруются этим паролем. Если его забыть, открыть их будет невозможно — " +
                "ни вам, ни разработчику. Подсказка поможет вспомнить.",
            color = MaterialTheme.colorScheme.error,
        )
        PasswordField(pw, { pw = it; error = "" }, "Пароль")
        PasswordField(pw2, { pw2 = it; error = "" }, "Повторите пароль")
        OutlinedTextField(
            hint, { hint = it }, label = { Text("Подсказка (видна всем на экране входа)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
            error = when {
                pw.length < 4 -> "Пароль — не меньше 4 символов"
                pw != pw2 -> "Пароли не совпадают"
                hint.isNotBlank() && pw.contains(hint.trim(), ignoreCase = true) -> "Подсказка не должна содержать сам пароль"
                else -> ""
            }
            if (error.isEmpty()) scope.launch { busy = true; Vault.create(pw, hint.trim()); busy = false }
        }) { Text(if (busy) "Создаю…" else "Создать раздел") }
    }
}

@Composable
private fun UnlockVault(host: Host) {
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val hint = remember { Vault.hint }

    fun tryBio() {
        if (!Vault.bioEnabled || !host.bioAvailable()) return
        val cipher = Vault.bioDecryptCipher()
        if (cipher == null) { error = "Отпечатки на телефоне изменились — войдите по паролю и включите вход по отпечатку снова"; return }
        host.biometric(cipher, "Секретные заметки") { c -> if (!Vault.bioUnlock(c)) error = "Не удалось открыть по отпечатку" }
    }
    LaunchedEffect(Unit) { tryBio() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Введите пароль", style = MaterialTheme.typography.titleMedium)
        if (hint.isNotBlank()) Text("Подсказка: $hint", color = MaterialTheme.colorScheme.outline)
        PasswordField(pw, { pw = it; error = "" }, "Пароль")
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(enabled = !busy && pw.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                busy = true
                if (!Vault.unlock(pw)) error = "Неверный пароль"
                busy = false
            }
        }) { Text(if (busy) "Проверяю…" else "Открыть") }
        if (Vault.bioEnabled && host.bioAvailable()) {
            OutlinedButton(onClick = { tryBio() }, modifier = Modifier.fillMaxWidth()) { Text("Войти по отпечатку") }
        }
    }
}

@Composable
private fun VaultList(host: Host, cat: String, onBackToCats: () -> Unit, onOpen: (Long) -> Unit) {
    val notes by Vault.notes.collectAsState()
    val shown = when (cat) {
        "*" -> notes
        "" -> notes.filter { it.category.isBlank() }
        else -> notes.filter { it.category == cat }
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBackToCats) { Text("← Категории") }
            Text(
                when (cat) {
                    "*" -> "Все заметки"
                    "" -> "Без категории"
                    else -> cat
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (shown.isEmpty()) item {
                Text(
                    "Здесь пока пусто. Нажмите +, чтобы добавить заметку в эту категорию.",
                    color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                )
            }
            items(shown, key = { it.id }) { n -> NoteCard(n, Vault, Modifier.animateItem()) { onOpen(n.id) } }
        }
    }
}

/** Поиск по всем секретным заметкам (название, текст, пункты). */
@Composable
private fun VaultSearchResults(query: String, onOpen: (Long) -> Unit) {
    val notes by Vault.notes.collectAsState()
    val found = if (query.isBlank()) emptyList() else notes.filter { it.matches(query.trim()) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (found.isEmpty()) item {
            Text(
                if (query.isBlank()) "Введите слово для поиска — ищется по названиям, тексту и пунктам."
                else "Ничего не найдено",
                color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            )
        }
        items(found, key = { it.id }) { n -> NoteCard(n, Vault, Modifier.animateItem()) { onOpen(n.id) } }
    }
}

// ---------------- Настройки: напоминания ----------------

@Composable
private fun RemindSettings(host: Host) {
    val ctx = LocalContext.current
    var sound by remember { mutableStateOf(Reminders.soundTitle(ctx)) }
    var insistent by remember { mutableStateOf(Reminders.insistent(ctx)) }
    var nag by remember { mutableStateOf(Reminders.nagMinutes(ctx)) }
    var nagMenu by remember { mutableStateOf(false) }
    val nagOptions = listOf(0 to "Не напоминать", 5 to "Через 5 минут", 10 to "Через 10 минут",
        15 to "Через 15 минут", 30 to "Через 30 минут", 60 to "Через час")

    Text("Напоминания", style = MaterialTheme.typography.titleMedium)
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        host.pickSound(Reminders.soundUri(ctx)) { uri ->
            Reminders.setSound(ctx, uri); sound = Reminders.soundTitle(ctx)
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Мелодия: $sound") }
    OutlinedButton(onClick = {
        host.pickAudio { src ->
            scope.launch {
                val uri = Reminders.importSound(ctx, src)
                if (uri == null) Toast.makeText(ctx, "Не удалось взять этот файл", Toast.LENGTH_LONG).show()
                else { Reminders.setSound(ctx, uri); sound = Reminders.soundTitle(ctx) }
            }
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Свой MP3 из файлов") }
    var volume by remember { mutableStateOf(Reminders.volume(ctx)) }
    Text("Громкость напоминаний: $volume%")
    Slider(value = volume.toFloat(), onValueChange = { volume = it.toInt() },
        onValueChangeFinished = { Reminders.setVolume(ctx, volume) }, valueRange = 10f..100f, steps = 8)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Звенеть, пока не нажму")
            Text("Мелодия повторяется, пока не тронете уведомление", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = insistent, onCheckedChange = { insistent = it; Reminders.setInsistent(ctx, it) })
    }
    Text("Если смахнул и не отметил «Выполнено» — напомнить снова:")
    Box {
        OutlinedButton(onClick = { nagMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Text(nagOptions.first { it.first == nag }.second)
        }
        DropdownMenu(expanded = nagMenu, onDismissRequest = { nagMenu = false }) {
            nagOptions.forEach { (m, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { nag = m; Reminders.setNagMinutes(ctx, m); nagMenu = false })
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        var overlay by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(ctx)) }
        LaunchedEffect(Unit) {
            // обновить статус после возврата из системных настроек
            while (true) { overlay = android.provider.Settings.canDrawOverlays(ctx); kotlinx.coroutines.delay(1000) }
        }
        Column(Modifier.weight(1f)) {
            Text("Большое окно на весь экран")
            Text(
                if (overlay) "Разрешено: окно вылетает и поверх блокировки, и на разблокированном телефоне"
                else "Разрешите «поверх других приложений» — тогда окно вылетает поверх любого экрана",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        Switch(
            checked = overlay,
            onCheckedChange = {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${ctx.packageName}"),
                        )
                    )
                }
            },
        )
    }
    if (Build.VERSION.SDK_INT >= 34) {
        // Android 14+: полноэкранные уведомления выдаются вручную (на случай, если «поверх приложений» нельзя)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            var fsi by remember { mutableStateOf(nm?.canUseFullScreenIntent() == true) }
            LaunchedEffect(Unit) {
                while (true) { fsi = nm?.canUseFullScreenIntent() == true; kotlinx.coroutines.delay(1000) }
            }
            Column(Modifier.weight(1f)) {
                Text("Полноэкранные уведомления")
                Text(
                    if (fsi) "Разрешено системой" else "Запасной способ показать окно поверх блокировки",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Switch(
                checked = fsi,
                onCheckedChange = {
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                android.net.Uri.parse("package:${ctx.packageName}"),
                            )
                        )
                    }
                },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            host.askNotificationPermission()
            if (!Reminders.test(ctx)) Toast.makeText(ctx, "Разрешите уведомления для приложения", Toast.LENGTH_LONG).show()
        }) { Text("Проверить звук") }
        OutlinedButton(onClick = {
            // Некоторые телефоны (realme, Xiaomi…) усыпляют приложения и напоминания опаздывают
            runCatching {
                ctx.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${ctx.packageName}"))
                )
            }
        }) { Text("Не усыплять") }
    }
}

// ---------------- Настройки: синхронизация с компьютером ----------------

@Composable
fun SyncSettings() {
    val ctx = LocalContext.current
    val code by Sync.code.collectAsState()
    val status by Sync.status.collectAsState()
    var askCode by remember { mutableStateOf(false) }
    var askChange by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf("") }

    Text("Синхронизация с компьютером", style = MaterialTheme.typography.titleMedium)
    Text(
        "Обычные заметки и секретка синхронизируются между телефоном и браузером на компьютере. " +
            "Секретка передаётся только в зашифрованном виде — на компьютере она открывается вашим паролем.",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (!Sync.available) {
        Text("Синхронизация не настроена в этой сборке (нет Firebase).", color = MaterialTheme.colorScheme.error)
        return
    }
    val c = code
    if (c == null) {
        Button(onClick = {
            if (Sync.enable(null) == null) Toast.makeText(ctx, "Нет соединения с облаком", Toast.LENGTH_LONG).show()
        }, modifier = Modifier.fillMaxWidth()) { Text("Включить синхронизацию") }
        TextButton(onClick = { askCode = true }) { Text("Ввести код с другого устройства") }
    } else {
        val url = "https://kriogad.github.io/notes-app/sync.html?code=$c"
        Text("Код синхронизации:", style = MaterialTheme.typography.bodyMedium)
        Text(c, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text("На компьютере откройте:\n$url", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = {
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT,
                    "Мои заметки на компьютере: $url\n(код синхронизации: $c)")
            ctx.startActivity(android.content.Intent.createChooser(send, "Отправить ссылку"))
        }, modifier = Modifier.fillMaxWidth()) { Text("Отправить ссылку себе") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { askCode = true }) { Text("Другой код") }
            OutlinedButton(onClick = { askChange = true }) { Text("Сменить код") }
            TextButton(onClick = { Sync.disable() }) { Text("Выключить") }
        }
    }
    if (askChange) AlertDialog(
        onDismissRequest = { askChange = false },
        title = { Text("Сменить код синхронизации?") },
        text = { Text("Данные переедут на новый код, а старый перестанет работать — если он попал не туда, доступ будет отрезан. На компьютере нужно будет войти с новым кодом.") },
        confirmButton = {
            TextButton(onClick = {
                askChange = false
                Sync.changeCode { ok ->
                    Toast.makeText(ctx,
                        if (ok != null) "Код сменён, старый больше не работает" else "Не удалось: нет связи",
                        Toast.LENGTH_LONG).show()
                }
            }) { Text("Сменить") }
        },
        dismissButton = { TextButton(onClick = { askChange = false }) { Text("Отмена") } },
    )
    if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.outline)

    if (askCode) AlertDialog(
        onDismissRequest = { askCode = false },
        title = { Text("Код синхронизации") },
        text = {
            OutlinedTextField(entered, { entered = it.uppercase() },
                label = { Text("Код с другого устройства") }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = {
                val res = Sync.enable(entered)
                if (res == null) Toast.makeText(ctx, "Код короткий или нет связи", Toast.LENGTH_LONG).show()
                askCode = false
                entered = ""
            }) { Text("Подключить") }
        },
        dismissButton = { TextButton(onClick = { askCode = false; entered = "" }) { Text("Отмена") } },
    )
}

// ---------------- Настройки: резервная копия ----------------

@Composable
fun SettingsScreen(host: Host, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by Backup.status.collectAsState()
    val logged = remember(status) { Backup.loggedIn(ctx) }
    var busy by remember { mutableStateOf(false) }
    var askRestore by remember { mutableStateOf(false) }
    val version = remember {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Тема оформления", style = MaterialTheme.typography.titleMedium)
            val themeMode by ThemePrefs.mode.collectAsState()
            val themeOptions = listOf(ThemePrefs.SYSTEM, ThemePrefs.LIGHT, ThemePrefs.DARK)
            SegmentedTabs(
                tabs = listOf("Системная", "Светлая", "Тёмная"),
                selected = themeOptions.indexOf(themeMode).coerceAtLeast(0),
                onSelect = { ThemePrefs.set(themeOptions[it]) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Фон главного экрана", style = MaterialTheme.typography.titleMedium)
            val bg by Background.image.collectAsState()
            LaunchedEffect(Unit) { Background.load(ctx) }
            bg?.let {
                Image(it, "Текущий фон", Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Crop)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    host.pickPhotos { uris ->
                        uris.firstOrNull()?.let { u ->
                            scope.launch {
                                if (!Background.set(ctx, u)) Toast.makeText(ctx, "Не удалось открыть фото", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) { Text(if (bg == null) "Выбрать фото" else "Другое фото") }
                if (bg != null) OutlinedButton(onClick = { Background.clear(ctx) }) { Text("Убрать фон") }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Шрифт виджетов", style = MaterialTheme.typography.titleMedium)
            var fontScale by remember { mutableStateOf(WidgetPrefs.fontScale(ctx).value) }
            var fontMenu by remember { mutableStateOf(false) }
            val fontOptions = listOf(
                0.7f to "−30%", 0.8f to "−20%", 0.9f to "−10%", 1.0f to "Обычный", 1.1f to "+10%",
            )
            Box {
                OutlinedButton(onClick = { fontMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(fontOptions.firstOrNull { it.first == fontScale }?.second ?: "Обычный")
                }
                DropdownMenu(expanded = fontMenu, onDismissRequest = { fontMenu = false }) {
                    fontOptions.forEach { (v, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            fontScale = v
                            WidgetPrefs.setFontScale(ctx, v)
                            Notes.refreshWidgets()
                            fontMenu = false
                        })
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            RemindSettings(host)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            SyncSettings()
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Резервная копия на Яндекс Диске", style = MaterialTheme.typography.titleMedium)
            Text(
                "Копия делается сама через минуту после любых изменений, когда есть интернет. " +
                    "Лежит на вашем Диске в папке «Приложения», плюс копия за каждый день в папке history. " +
                    "Секретные заметки отправляются в зашифрованном виде.",
                style = MaterialTheme.typography.bodyMedium,
            )
            when {
                !Backup.configured() -> Text("Вход в Яндекс ещё не настроен в этой версии.", color = MaterialTheme.colorScheme.error)
                !logged -> Button(onClick = { Backup.login(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Войти с Яндексом") }
                else -> {
                    Button(enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = {
                        scope.launch {
                            busy = true
                            try { Backup.upload(ctx) } catch (e: Exception) { Backup.fail(ctx, e) }
                            busy = false
                        }
                    }) { Text(if (busy) "Отправляю…" else "Сделать копию сейчас") }
                    OutlinedButton(enabled = !busy, onClick = { askRestore = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Восстановить из копии")
                    }
                    TextButton(onClick = { Backup.logout(ctx) }) { Text("Отключить Яндекс Диск") }
                }
            }
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.outline)
            Column(Modifier.padding(top = 24.dp)) {
                Text("Версия $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Text(
                    "Разработчик: Kriogad",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp).clickable {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://github.com/Kriogad")))
                    },
                )
            }
        }
    }

    if (askRestore) AlertDialog(
        onDismissRequest = { askRestore = false },
        title = { Text("Восстановить из копии?") },
        text = { Text("Заметки на телефоне будут заменены последней копией с Яндекс Диска.") },
        confirmButton = {
            TextButton(onClick = {
                askRestore = false
                scope.launch {
                    busy = true
                    try {
                        if (!Backup.restore(ctx)) Toast.makeText(ctx, "На Диске пока нет копии", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) { Backup.fail(ctx, e) }
                    busy = false
                }
            }) { Text("Восстановить") }
        },
        dismissButton = { TextButton(onClick = { askRestore = false }) { Text("Отмена") } },
    )
}

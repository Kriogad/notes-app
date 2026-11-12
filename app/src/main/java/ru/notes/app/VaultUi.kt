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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.launch

// ---------------- Секретный раздел ----------------

@Composable
fun VaultScreen(host: Host, onBack: () -> Unit, onOpen: (Long) -> Unit, onNew: () -> Unit) {
    val unlocked by Vault.unlocked.collectAsState()
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Секретные заметки") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    if (unlocked) IconButton(onClick = onBack) { Icon(Icons.Filled.Lock, "Закрыть раздел") }
                },
            )
        },
        floatingActionButton = {
            if (unlocked) FloatingActionButton(onClick = onNew) { Icon(Icons.Filled.Add, "Новая секретная заметка") }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).imePadding()) {
            when {
                unlocked -> VaultList(host, onOpen)
                !Vault.exists -> CreateVault()
                else -> UnlockVault(host)
            }
        }
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
private fun VaultList(host: Host, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val notes by Vault.notes.collectAsState()
    var bio by remember { mutableStateOf(Vault.bioEnabled) }
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
        if (notes.isEmpty()) item {
            Text(
                "Здесь пока пусто. Нажмите +, чтобы добавить пароль или другую секретную заметку.",
                color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            )
        }
        items(notes, key = { it.id }) { n -> NoteCard(n, Vault, Modifier.animateItem()) { onOpen(n.id) } }
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

            RemindSettings(host)
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
            Text("Версия $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 24.dp))
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

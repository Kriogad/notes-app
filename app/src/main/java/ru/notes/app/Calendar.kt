@file:OptIn(ExperimentalMaterial3Api::class)

package ru.notes.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ru = Locale("ru")

/** Событие в календаре: заметка или пункт с напоминанием. */
private class Event(val noteId: Long, val title: String, val time: LocalTime, val repeat: Repeat)

/** На какие дни месяца приходится напоминание (с учётом повтора). */
private fun daysIn(at: Long, repeat: Repeat, month: YearMonth): List<LocalDate> {
    val start = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
    val all = (1..month.lengthOfMonth()).map { month.atDay(it) }
    return when (repeat) {
        Repeat.NONE -> all.filter { it == start }
        Repeat.DAY -> all.filter { !it.isBefore(start) }
        Repeat.WEEK -> all.filter { !it.isBefore(start) && it.dayOfWeek == start.dayOfWeek }
        Repeat.MONTH -> if (month.isBefore(YearMonth.from(start))) emptyList()
            else listOf(month.atDay(minOf(start.dayOfMonth, month.lengthOfMonth())))
        Repeat.YEAR -> if (month.monthValue != start.monthValue || month.year < start.year) emptyList()
            else listOf(month.atDay(minOf(start.dayOfMonth, month.lengthOfMonth())))
    }
}

@Composable
fun CalendarScreen(host: Host, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val notes by Notes.notes.collectAsState()
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.now().let { it.year * 12 + it.monthValue - 1 }) }
    val month = YearMonth.of(monthKey / 12, monthKey % 12 + 1)
    var selected by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val day = LocalDate.ofEpochDay(selected)
    BackHandler(onBack = onBack)

    // Все события месяца: день → список
    val events: Map<LocalDate, List<Event>> = remember(notes, monthKey) {
        val res = mutableMapOf<LocalDate, MutableList<Event>>()
        fun add(at: Long?, rep: Repeat, id: Long, title: String) {
            if (at == null) return
            val time = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime()
            daysIn(at, rep, month).forEach { res.getOrPut(it) { mutableListOf() } += Event(id, title, time, rep) }
        }
        notes.filter { !it.done }.forEach { n ->
            add(n.remindAt, n.repeat, n.id, n.displayTitle)
            n.items.filter { !it.checked }.forEach { add(it.remindAt, it.repeat, n.id, it.text.ifBlank { n.displayTitle }) }
        }
        res.mapValues { (_, v) -> v.sortedBy { it.time } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Календарь") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).imePadding()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                    IconButton(onClick = { monthKey-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Прошлый месяц") }
                    Text(
                        month.month.getDisplayName(TextStyle.FULL_STANDALONE, ru).replaceFirstChar { it.uppercase() } + " " + month.year,
                        style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { monthKey++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Следующий месяц") }
                }
            }
            item { MonthGrid(month, day, events.keys) { selected = it.toEpochDay() } }
            item {
                Text(
                    day.format(DateTimeFormatter.ofPattern("d MMMM, EEEE", ru)),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            val list = if (YearMonth.from(day) == month) events[day].orEmpty() else emptyList()
            items(list) { e ->
                Card(onClick = { onOpen(e.noteId) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.time.format(DateTimeFormatter.ofPattern("HH:mm")), fontWeight = FontWeight.Bold)
                        Text(e.title, Modifier.weight(1f).padding(start = 12.dp), maxLines = 2)
                        if (e.repeat != Repeat.NONE) Text("↻", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (list.isEmpty()) item {
                Text("Событий нет", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            }
            item { AddEvent(day, host) }
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, selected: LocalDate, marked: Set<LocalDate>, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val shift = month.atDay(1).dayOfWeek.value - 1 // неделя с понедельника
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row {
            DayOfWeek.entries.forEach {
                Text(
                    it.getDisplayName(TextStyle.SHORT_STANDALONE, ru), Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        val cells = shift + month.lengthOfMonth()
        for (week in 0 until (cells + 6) / 7) {
            Row {
                for (d in 0 until 7) {
                    val n = week * 7 + d - shift + 1
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (n in 1..month.lengthOfMonth()) {
                            val date = month.atDay(n)
                            val isSel = date == selected
                            Box(
                                Modifier.fillMaxSize().background(
                                    if (isSel) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                    CircleShape,
                                ).let {
                                    if (date == today && !isSel) it.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape) else it
                                }.clickable { onPick(date) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$n",
                                    color = if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (date in marked) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (date in marked) Box(
                                    Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp).size(5.dp)
                                        .background(if (isSel) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary, CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Вписать событие на выбранный день: текст (или голосом), время, повтор. */
@Composable
private fun AddEvent(day: LocalDate, host: Host) {
    var text by remember { mutableStateOf("") }
    var time by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var rep by remember { mutableStateOf(Repeat.NONE) }
    var pickTime by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it }, Modifier.weight(1f),
                label = { Text("Событие на " + day.format(DateTimeFormatter.ofPattern("d MMMM", ru))) },
            )
            IconButton(onClick = { host.listen { text = listOf(text, it).filter { s -> s.isNotBlank() }.joinToString(" ") } }) {
                Icon(painterResource(R.drawable.ic_mic), "Надиктовать")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            FilterChip(selected = false, onClick = { pickTime = true }, label = { Text("🕘 " + time.format(DateTimeFormatter.ofPattern("HH:mm"))) })
            listOf(Repeat.NONE to "Один раз", Repeat.YEAR to "Каждый год", Repeat.MONTH to "Каждый месяц").forEach { (r, l) ->
                FilterChip(selected = rep == r, onClick = { rep = r }, label = { Text(l) })
            }
        }
        Button(
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            onClick = {
                val at = day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                Notes.upsert(Note(System.currentTimeMillis(), title = text.trim().replaceFirstChar { it.uppercase() }, remindAt = at, repeat = rep))
                host.askNotificationPermission()
                android.widget.Toast.makeText(ctx, "Событие добавлено", android.widget.Toast.LENGTH_SHORT).show()
                text = ""; rep = Repeat.NONE
            },
        ) { Text("Добавить событие") }
    }
    if (pickTime) TimeDialog(
        initial = day.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        onDismiss = { pickTime = false },
        onPick = { h, m -> time = LocalTime.of(h, m); pickTime = false },
    )
}

package ru.notes.app

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Находит дату и время в надиктованной фразе:
 * «1-го числа», «первого числа», «16 августа», «в пятницу», «завтра», «через 3 дня»,
 * «в 10», «в 10:30», «в 7 вечера», «каждый месяц», «ежегодно»…
 */
object DateParser {
    class Result(val at: Long, val repeat: Repeat, val label: String)

    private const val DEFAULT_HOUR = 9

    private val months = listOf("январ", "феврал", "март", "апрел", "ма[яй]", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")

    private val ordinals = listOf(
        "первого", "второго", "третьего", "четв[её]ртого", "пятого", "шестого", "седьмого", "восьмого", "девятого", "десятого",
        "одиннадцатого", "двенадцатого", "тринадцатого", "четырнадцатого", "пятнадцатого", "шестнадцатого",
        "семнадцатого", "восемнадцатого", "девятнадцатого", "двадцатого",
    )

    private val days = mapOf(
        "понедельник" to DayOfWeek.MONDAY, "вторник" to DayOfWeek.TUESDAY, "среду" to DayOfWeek.WEDNESDAY, "среда" to DayOfWeek.WEDNESDAY,
        "четверг" to DayOfWeek.THURSDAY, "пятницу" to DayOfWeek.FRIDAY, "пятница" to DayOfWeek.FRIDAY,
        "субботу" to DayOfWeek.SATURDAY, "суббота" to DayOfWeek.SATURDAY, "воскресенье" to DayOfWeek.SUNDAY,
    )

    /** «первого», «двадцать пятого», «12», «12-го» → число месяца. */
    private fun dayNumber(s: String): Int? {
        s.trim().removeSuffix("-го").removeSuffix("го").toIntOrNull()?.let { return it.takeIf { d -> d in 1..31 } }
        val w = s.trim().lowercase()
        val tens = when {
            w.startsWith("двадцать ") -> 20
            w.startsWith("тридцать ") -> 30
            else -> 0
        }
        val rest = if (tens > 0) w.substringAfter(' ') else w
        val i = ordinals.indexOfFirst { Regex("^$it$").matches(rest) }
        if (i >= 0) return (tens + i + 1).takeIf { it in 1..31 }
        if (rest == "тридцатого") return 30
        return null
    }

    private val ordinalWord = "(?:(?:двадцать|тридцать)\\s+)?(?:${ordinals.joinToString("|")}|тридцатого)"

    fun parse(text: String, now: LocalDateTime = LocalDateTime.now()): Result? {
        val t = " " + text.lowercase().replace('ё', 'е') + " "
        var date: LocalDate? = null
        var rep = Repeat.NONE

        // Повтор
        when {
            Regex("кажд(ый|ое|ую|ого)\\s+(день|дня)|ежедневно").containsMatchIn(t) -> rep = Repeat.DAY
            Regex("кажд(ую|ый)\\s+недел|еженедельно").containsMatchIn(t) -> rep = Repeat.WEEK
            Regex("кажд(ый|ое)\\s+(месяц|\\d+|$ordinalWord)|ежемесячно").containsMatchIn(t) -> rep = Repeat.MONTH
            Regex("кажд(ый|ое)\\s+год|ежегодно").containsMatchIn(t) -> rep = Repeat.YEAR
        }

        // «16 августа», «первого марта»
        val monthRe = Regex("(\\d{1,2}|$ordinalWord)(?:-?го)?\\s+(${months.joinToString("|")})[а-я]*")
        monthRe.find(t)?.let { m ->
            val d = dayNumber(m.groupValues[1]) ?: return@let
            val mon = months.indexOfFirst { Regex("^$it").containsMatchIn(m.groupValues[2]) } + 1
            var cand = runCatching { LocalDate.of(now.year, mon, d) }.getOrNull() ?: return@let
            if (cand.isBefore(now.toLocalDate())) cand = cand.plusYears(1)
            date = cand
        }

        // «1-го числа», «первого числа», «каждое 12 число»
        if (date == null) Regex("(\\d{1,2}|$ordinalWord)(?:-?го)?\\s+числ").find(t)?.let { m ->
            val d = dayNumber(m.groupValues[1]) ?: return@let
            var cand = now.toLocalDate().withDayOfMonth(1)
            // ближайшее такое число, не раньше сегодня
            repeat(13) {
                val day = runCatching { cand.withDayOfMonth(d) }.getOrNull()
                if (day != null && !day.isBefore(now.toLocalDate())) { date = day; return@let }
                cand = cand.plusMonths(1)
            }
        }

        if (date == null) when {
            Regex("\\sпослезавтра\\s").containsMatchIn(t) -> date = now.toLocalDate().plusDays(2)
            Regex("\\sзавтра\\s").containsMatchIn(t) -> date = now.toLocalDate().plusDays(1)
            Regex("\\sсегодня\\s").containsMatchIn(t) -> date = now.toLocalDate()
        }

        // «через 3 дня», «через неделю», «через месяц»
        var relTime: LocalDateTime? = null
        if (date == null) Regex("через\\s+(\\d+\\s+)?(дн|день|недел|месяц|час|минут)").find(t)?.let { m ->
            val n = m.groupValues[1].trim().toLongOrNull() ?: 1
            when {
                m.groupValues[2].startsWith("дн") || m.groupValues[2] == "день" -> date = now.toLocalDate().plusDays(n)
                m.groupValues[2].startsWith("недел") -> date = now.toLocalDate().plusWeeks(n)
                m.groupValues[2].startsWith("месяц") -> date = now.toLocalDate().plusMonths(n)
                m.groupValues[2].startsWith("час") -> relTime = now.plusHours(n).withSecond(0).withNano(0)
                m.groupValues[2].startsWith("минут") -> relTime = now.plusMinutes(n).withSecond(0).withNano(0)
            }
        }

        // «в пятницу», «в понедельник»
        if (date == null && relTime == null) {
            Regex("\\s(?:в|во)\\s+(${days.keys.joinToString("|")})\\s").find(t)?.let { m ->
                val dow = days[m.groupValues[1]]!!
                date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(dow))
            }
        }

        // Время: «в 10», «в 10:30», «в 10.30», «в 7 вечера», «в 10 часов»
        var time: LocalTime? = null
        Regex("\\s(?:в|к)\\s+(\\d{1,2})(?:[:.](\\d{2})|\\s+(\\d{2})\\s+минут)?(?:\\s*(час[а-я]*))?(?:\\s+(утра|дня|вечера|ночи))?(?=[\\s,.!?])")
            .findAll(t).firstOrNull { m ->
                // «в 2026 году» и «в 16 августа» — не время
                val after = t.substring(m.range.last + 1).trimStart()
                !after.startsWith("числ") && months.none { Regex("^$it").containsMatchIn(after) }
            }?.let { m ->
                var h = m.groupValues[1].toInt()
                val min = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: 0
                when (m.groupValues[5]) {
                    "вечера", "дня" -> if (h < 12) h += 12
                    "ночи" -> if (h == 12) h = 0
                    "утра" -> {}
                    else -> if (h in 1..6) h += 12 // «в 2 позвонить» — это днём
                }
                if (h in 0..23 && min in 0..59) time = LocalTime.of(h, min)
            }
        if (Regex("\\sв\\s+полдень").containsMatchIn(t)) time = LocalTime.NOON

        if (relTime != null) return result(relTime!!, rep)
        if (date == null && time == null && rep == Repeat.NONE) return null
        // Только время: сегодня, а если уже прошло — завтра
        var at = LocalDateTime.of(date ?: now.toLocalDate(), time ?: LocalTime.of(DEFAULT_HOUR, 0))
        if (!at.isAfter(now)) {
            at = when {
                rep != Repeat.NONE -> LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(rep.next(ms(at))), ZoneId.systemDefault())
                date == null || date == now.toLocalDate() -> at.plusDays(1)
                else -> at
            }
        }
        return result(at, rep)
    }

    /** Пункт с напоминанием, если во фразе есть дата/время. */
    fun item(text: String): CheckItem {
        val r = parse(text) ?: return CheckItem(text)
        return CheckItem(text, remindAt = r.at, repeat = r.repeat)
    }

    /** Заметке без напоминания — напоминание из текста. */
    fun apply(n: Note, text: String): Note {
        if (n.remindAt != null) return n
        val r = parse(text) ?: return n
        return n.copy(remindAt = r.at, repeat = r.repeat)
    }

    /** Подсказка для всплывающего сообщения: «🔔 1 декабря, 09:00». */
    fun toast(ctx: android.content.Context, items: List<CheckItem>, note: Note? = null) {
        val times = (listOfNotNull(note?.remindAt) + items.mapNotNull { it.remindAt })
        if (times.isEmpty()) return
        val f = java.time.format.DateTimeFormatter.ofPattern("d MMMM, HH:mm", java.util.Locale("ru"))
        val txt = times.joinToString(", ") { LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), ZoneId.systemDefault()).format(f) }
        android.widget.Toast.makeText(ctx, "🔔 Напомню: $txt", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun result(at: LocalDateTime, repeat: Repeat): Result {
        val label = at.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM, HH:mm", java.util.Locale("ru"))) +
            if (repeat != Repeat.NONE) " · ${repeat.label.lowercase()}" else ""
        return Result(ms(at), repeat, label)
    }
}

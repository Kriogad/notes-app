package ru.notes.app

/**
 * Превращает числительные из распознанной речи в цифры:
 * «купить двадцать пять яиц» → «купить 25 яиц», «в пять вечера» → «в 5 вечера».
 * Побочный плюс: DateParser понимает время только цифрами, так что напоминания
 * «в пять вечера» теперь тоже распознаются.
 */
object NumberWords {
    private val values = mapOf(
        "ноль" to 0L, "нуль" to 0L,
        "один" to 1L, "одна" to 1L, "одно" to 1L,
        "два" to 2L, "две" to 2L,
        "три" to 3L, "четыре" to 4L, "пять" to 5L, "шесть" to 6L,
        "семь" to 7L, "восемь" to 8L, "девять" to 9L,
        "десять" to 10L, "одиннадцать" to 11L, "двенадцать" to 12L,
        "тринадцать" to 13L, "четырнадцать" to 14L, "пятнадцать" to 15L,
        "шестнадцать" to 16L, "семнадцать" to 17L, "восемнадцать" to 18L, "девятнадцать" to 19L,
        "двадцать" to 20L, "тридцать" to 30L, "сорок" to 40L, "пятьдесят" to 50L,
        "шестьдесят" to 60L, "семьдесят" to 70L, "восемьдесят" to 80L, "девяносто" to 90L,
        "сто" to 100L, "двести" to 200L, "триста" to 300L, "четыреста" to 400L,
        "пятьсот" to 500L, "шестьсот" to 600L, "семьсот" to 700L, "восемьсот" to 800L, "девятьсот" to 900L,
    )
    private val multipliers = mapOf(
        "тысяча" to 1_000L, "тысячи" to 1_000L, "тысяч" to 1_000L, "тысячу" to 1_000L,
        "миллион" to 1_000_000L, "миллиона" to 1_000_000L, "миллионов" to 1_000_000L,
    )

    // Слова и всё остальное (пробелы, знаки) — отдельными токенами
    private val tokenRe = Regex("[а-яё]+|[^а-яё]+", RegexOption.IGNORE_CASE)

    fun convert(text: String): String {
        val out = StringBuilder()
        var group = 0L      // накопленная часть после последнего множителя
        var total = 0L      // сумма по множителям (тысячи, миллионы)
        var inGroup = false
        var pendingSep = "" // пробел между словами: выводим, только если группа закончилась
        var lastVal = Long.MAX_VALUE // последняя часть группы: разряды идут по убыванию

        fun flush() {
            if (inGroup) {
                out.append(total + group)
                total = 0; group = 0; inGroup = false
            }
        }

        for (tok in tokenRe.findAll(text)) {
            val s = tok.value
            val w = s.lowercase().replace('ё', 'е')
            val isWord = w.isNotEmpty() && w.all { it in 'а'..'я' }
            val v = if (isWord) values[w] else null
            val m = if (isWord) multipliers[w] else null
            when {
                v != null -> {
                    // «семь тридцать»: разряд вырос — это уже новое число, а не продолжение
                    if (inGroup && group > 0 && v >= lastVal) { flush(); out.append(pendingSep) }
                    pendingSep = "" // пробел между разрядами проглатываем: «двадцать пять» → «25»
                    group += v; lastVal = v; inGroup = true
                }
                m != null && (inGroup || w == "тысяча" || w == "миллион") -> {
                    pendingSep = ""
                    total += (if (group == 0L) 1L else group) * m
                    group = 0; lastVal = Long.MAX_VALUE; inGroup = true
                }
                isWord -> { flush(); out.append(pendingSep); pendingSep = ""; out.append(s) }
                s.isBlank() -> if (inGroup) pendingSep = s else out.append(s)
                else -> { flush(); out.append(pendingSep); pendingSep = ""; out.append(s) }
            }
        }
        flush(); out.append(pendingSep)
        return out.toString()
    }
}

package pl.detailing.crm.ownerreport.pdf

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Formatowanie liczb raportu po polsku. Czyste funkcje — testowane bez PDF-a.
 *
 * Znak ujemny to zwykły dywiz, nie typograficzny minus (U+2212): [pl.detailing.crm.shared.pdf.DocumentSheet]
 * wycina znaki, których font nie ma, a zgubiony minus zamieniłby stratę w zysk.
 *
 * Kwoty wypisujemy z groszy co do grosza, bez zaokrąglania do złotówek: raport
 * pokazuje te same kwoty, co ekran wizyty i faktura, inaczej właściciel zaczyna
 * szukać, która liczba jest prawdziwa.
 */
object ReportFormat {

    /** Zmiana względem zera nie ma procentu — pokazujemy samą wartość odniesienia. */
    const val NO_CHANGE_BASE = "—"

    const val UNCHANGED = "bez zmian"

    private val DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val DAY_SHORT = DateTimeFormatter.ofPattern("dd.MM")

    fun money(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = abs(cents)
        return "$sign${grouped(abs / 100)},${(abs % 100).toString().padStart(2, '0')} zł"
    }

    fun count(value: Long): String = grouped(value)

    fun count(value: Int): String = grouped(value.toLong())

    /** „1 h 20 min", „2 dni 3 h" — mediana czasu odpowiedzi. */
    fun duration(minutes: Long): String = when {
        minutes < 60 -> "$minutes min"
        minutes < 24 * 60 -> {
            val m = minutes % 60
            if (m == 0L) "${minutes / 60} h" else "${minutes / 60} h $m min"
        }
        else -> {
            val days = minutes / (24 * 60)
            val hours = (minutes % (24 * 60)) / 60
            val dayWord = if (days == 1L) "dzień" else "dni"
            if (hours == 0L) "$days $dayWord" else "$days $dayWord $hours h"
        }
    }

    fun percent(part: Int, whole: Int): String =
        if (whole <= 0) "—" else "${(part * 100.0 / whole).roundToLong()}%"

    fun day(date: LocalDate): String = DAY.format(date)

    /** „15.09–21.09.2026"; przez przełom roku pełne daty po obu stronach. */
    fun range(from: LocalDate, to: LocalDate): String =
        if (from.year == to.year) "${DAY_SHORT.format(from)}–${DAY.format(to)}" else "${DAY.format(from)}–${DAY.format(to)}"

    /**
     * Zmiana względem poprzedniego okresu.
     *
     * Procent tylko wtedy, gdy jest od czego go liczyć: z zera na 5 to nie „+∞%",
     * tylko [NO_CHANGE_BASE] — obok stoi wartość odniesienia (0), która mówi resztę.
     * „Nowe" zostawiało pytanie „względem czego?". Przy małych liczbach (np. 2 → 3
     * wizyty) procent też nic nie mówi, więc do tego progu pokazujemy różnicę w sztukach.
     */
    fun change(current: Long, previous: Long, smallCountThreshold: Long = 0): String = when {
        current == previous -> UNCHANGED
        previous == 0L -> NO_CHANGE_BASE
        abs(previous) < smallCountThreshold || abs(current) < smallCountThreshold ->
            (if (current > previous) "+" else "-") + grouped(abs(current - previous))
        else -> {
            val pct = ((current - previous) * 100.0 / abs(previous)).roundToLong()
            when {
                pct > 0 -> "+$pct%"
                pct < 0 -> "-${abs(pct)}%"
                else -> if (current > previous) "+<1%" else "-<1%"
            }
        }
    }

    private fun grouped(value: Long): String {
        val digits = abs(value).toString()
        val out = StringBuilder()
        digits.forEachIndexed { index, ch ->
            if (index > 0 && (digits.length - index) % 3 == 0) out.append(' ')
            out.append(ch)
        }
        return (if (value < 0) "-" else "") + out
    }
}

package pl.detailing.crm.instagram.ads

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Liczby stojące za kalendarzem. Czysta funkcja, bez bazy — bo to jest miejsce,
 * w którym najłatwiej pomylić się o jeden dzień, a najtaniej to sprawdzić testem.
 */
object AdCalendarMath {

    /**
     * Dni emisji w oknie, licząc oba końce.
     *
     * Kampania od 14 do 16 marca to trzy dni, nie dwa — właściciel liczy dni,
     * w których jego konkurent był widoczny, a nie różnicę dat. Kampania trwająca
     * kończy się „dziś", bo jutra jeszcze nie było.
     */
    fun daysInWindow(
        start: LocalDate,
        stop: LocalDate?,
        windowStart: LocalDate,
        windowEnd: LocalDate
    ): Int {
        val from = maxOf(start, windowStart)
        val to = minOf(stop ?: windowEnd, windowEnd)
        if (to.isBefore(from)) return 0
        return (ChronoUnit.DAYS.between(from, to) + 1).toInt()
    }

    /**
     * Rozdziela kampanie na tory tak, żeby nachodzące na siebie nigdy nie trafiły
     * na ten sam. Zwraca numer toru dla każdej pozycji wejścia, w tej samej kolejności.
     *
     * Reguła podwójnego liczenia dni („4 kampanie × 3 dni = 12") jest wtedy widoczna
     * wprost: cztery paski jeden pod drugim w tych samych trzech dniach.
     */
    fun assignLanes(intervals: List<Pair<LocalDate, LocalDate>>): List<Int> {
        val lanes = mutableListOf<LocalDate>() // ostatni zajęty dzień każdego toru
        val result = IntArray(intervals.size)

        intervals.withIndex()
            .sortedWith(compareBy({ it.value.first }, { it.value.second }))
            .forEach { (index, interval) ->
                val (start, end) = interval
                val free = lanes.indexOfFirst { it.isBefore(start) }
                if (free >= 0) {
                    lanes[free] = end
                    result[index] = free
                } else {
                    lanes += end
                    result[index] = lanes.size - 1
                }
            }

        return result.toList()
    }

    /**
     * Czy przedział wiekowy Meta mieści się w wieku ustawionym przez reklamodawcę.
     *
     * Różnica między „chciał" a „wyszło" jest tu całą treścią: reklama ustawiona
     * na 25-54 i tak dociera do dwudziestolatków, bo Meta dobiera odbiorców sama.
     * Gdy ustawienia nie znamy, nie udajemy, że wszystko jest poza zakresem.
     */
    fun inTargetAge(bucket: String, targetAges: String?): Boolean {
        val target = parseRange(targetAges) ?: return true
        val range = parseRange(bucket) ?: return true
        return range.first <= target.second && range.second >= target.first
    }

    /** „25-54" → 25..54, „65+" → 65..200, „18" → 18..18. */
    private fun parseRange(raw: String?): Pair<Int, Int>? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val parts = value.split("-", limit = 2)
        val low = parts[0].filter { it.isDigit() }.toIntOrNull() ?: return null
        val highRaw = parts.getOrNull(1) ?: parts[0]
        val high = if (highRaw.contains("+")) OPEN_ENDED
        else highRaw.filter { it.isDigit() }.toIntOrNull() ?: low
        return low to high
    }

    /** „65+" nie ma górnej granicy; 200 wystarczy za nieskończoność. */
    private const val OPEN_ENDED = 200
}

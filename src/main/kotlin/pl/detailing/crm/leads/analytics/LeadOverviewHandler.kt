package pl.detailing.crm.leads.analytics

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.shared.DateRangeFilter
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.temporal.ChronoUnit

/**
 * Dane ekranu startowego modułu Leady: cztery kafle kontekstu i wykres roku.
 *
 * ── Dlaczego to nie jedzie z /analytics ────────────────────────────────────
 *
 * Bo pełna analityka to timeline, macierz dni tygodnia, segmenty aut, odstające
 * marki i kilkaset surowych faktów do filtrowania w przeglądarce. Cały ten rachunek
 * wisiałby na ścieżce WEJŚCIA do modułu — trzydzieści razy dziennie, żeby narysować
 * dwanaście punktów i cztery liczby. Tutaj są cztery zapytania po dwie kolumny.
 *
 * ── Dwie jednostki wykresu, jedna prawda ───────────────────────────────────
 *
 * Wykres przełącza się między złotówkami a sztukami, więc oba pola jadą razem
 * w jednej odpowiedzi: przełącznik ma przerysowywać wykres, a nie iść po dane.
 * Te dwie liczby opowiadają różne rzeczy i dlatego obie są potrzebne — miesiąc
 * z jedną wyceną na dziesięć tysięcy i miesiąc z dwudziestoma zapytaniami bez
 * wyceny wyglądają w złotówkach podobnie, a pracy jest w nich dwadzieścia razy
 * więcej.
 *
 * [LeadIntakeMonthDto.count] i [LeadIntakeMonthDto.value] są null dla miesięcy,
 * które jeszcze nie nadeszły — „nic nie przyszło" i „miesiąc nie nadszedł" to dwie
 * różne rzeczy i linia ma się w tym drugim przypadku urwać, a nie spaść do zera.
 */
@Service
class LeadOverviewHandler(
    private val leadRepository: LeadRepository,
    private val historyRepository: LeadStatusHistoryRepository
) {

    @Transactional(readOnly = true)
    fun handle(studioId: StudioId, year: Int): LeadOverviewDto {
        val from = LocalDate.of(year, 1, 1).atStartOfDay(DateRangeFilter.ZONE).toInstant()
        val to = LocalDate.of(year + 1, 1, 1).atStartOfDay(DateRangeFilter.ZONE).toInstant()

        val counts = IntArray(12)
        val values = LongArray(12)
        leadRepository.findIntakeFacts(studioId.value, from, to).forEach { row ->
            val createdAt = row[0] as Instant
            val value = (row[1] as Number).toLong()
            // Miesiąc w strefie studia, nie w UTC: zapytanie z 1 marca o 00:30 czasu
            // polskiego jest marcowe, choć w UTC wpadło jeszcze w luty.
            val month = createdAt.atZone(DateRangeFilter.ZONE).monthValue - 1
            counts[month] += 1
            values[month] += value
        }

        val today = LocalDate.now(DateRangeFilter.ZONE)
        val lastLivedMonth = if (year < today.year) 12 else if (year > today.year) 0 else today.monthValue

        return LeadOverviewDto(
            year = year,
            months = (1..12).map { month ->
                if (month > lastLivedMonth) LeadIntakeMonthDto(month, null, null)
                else LeadIntakeMonthDto(month, counts[month - 1], values[month - 1])
            },
            confirmedValueThisWeek = confirmedValueThisWeek(studioId),
            recent = recent(studioId)
        )
    }

    /**
     * Ostatnie dwa tygodnie i punkt odniesienia dla nich.
     *
     * Czternaście dni, a nie miesiąc kalendarzowy: okno ma być tej samej długości
     * przy każdym wejściu na ekran, żeby porównanie z poprzednim okresem znaczyło
     * to samo pierwszego i trzydziestego dnia miesiąca. Miesiąc kalendarzowy
     * porównywany „do poprzedniego" pierwszego dnia pokazuje spadek o 97%.
     *
     * Dwa tygodnie łapią też pełne dwa cykle tygodniowe, a ruch w detailingu jest
     * tygodniowy — porównanie siedmiu dni z siedmioma różniłoby głównie to, czy
     * w oknie wypadł długi weekend.
     */
    private fun recent(studioId: StudioId): LeadRecentDto {
        val now = Instant.now()
        val windowStart = now.minus(WINDOW_DAYS, ChronoUnit.DAYS)
        val previousStart = windowStart.minus(WINDOW_DAYS, ChronoUnit.DAYS)

        val inWindow = leadRepository.findIntakeFacts(studioId.value, windowStart, now).size
        val inPrevious = leadRepository.findIntakeFacts(studioId.value, previousStart, windowStart).size

        /*
         * Czas reakcji liczymy z pary (wpłynęło, pierwsza odpowiedź) — niezależnie
         * od kanału, bo firstResponseAt stempluje też odnotowany telefon.
         *
         * Odniesieniem jest CAŁA historia studia, nie poprzednie dwa tygodnie:
         * przy kilkunastu odpowiedziach w oknie średnia skacze o godziny z tygodnia
         * na tydzień, więc porównanie dwóch takich średnich mierzyłoby głównie szum.
         * Historia jest stabilna i mówi „tak wygląda to u nas zwykle".
         */
        val allDurations = leadRepository.findResponseFacts(studioId.value, Instant.EPOCH)
            .map { row -> Duration.between(row[0] as Instant, row[1] as Instant) }
            .filterNot { it.isNegative }
        val windowDurations = leadRepository.findResponseFacts(studioId.value, windowStart)
            .map { row -> Duration.between(row[0] as Instant, row[1] as Instant) }
            .filterNot { it.isNegative }

        return LeadRecentDto(
            days = WINDOW_DAYS.toInt(),
            inquiries = inWindow,
            inquiriesPrevious = inPrevious,
            averageResponseMinutes = windowDurations.averageMinutes(),
            averageResponseMinutesAllTime = allDurations.averageMinutes(),
            p95ResponseMinutes = allDurations.percentileMinutes(P95),
            answered = windowDurations.size,
            answeredAllTime = allDurations.size
        )
    }

    /**
     * Ile pieniędzy zamieniło się w rezerwacje od poniedziałku.
     *
     * Jedyna kwota na ekranie startowym, która nie jest zadaniem, tylko pokwitowaniem:
     * bez niej widok wyłącznie wymaga i nigdy nie mówi „zrobione". Jedzie tą samą
     * odpowiedzią co reszta, bo należy do tego samego ekranu.
     *
     * Moment decyzji bierze się z historii statusów, a nie z samego leada: lead
     * niesie stan bieżący, więc „kiedy klient się zdecydował" wie wyłącznie wpis
     * o przejściu na REZERWACJĘ.
     */
    private fun confirmedValueThisWeek(studioId: StudioId): Long {
        val today = LocalDate.now(DateRangeFilter.ZONE)
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
            .atStartOfDay(DateRangeFilter.ZONE).toInstant()
        val now = Instant.now()

        val confirmedLeadIds = historyRepository
            .findByStudioIdAndCreatedAtBetween(studioId.value, monday, now)
            .filter { it.toStatus == LeadStatus.CONFIRMED }
            // Lead potrafi wrócić do rezerwacji po odwołaniu; liczy się raz.
            .map { it.leadId }
            .distinct()
        if (confirmedLeadIds.isEmpty()) return 0

        return leadRepository.findAllById(confirmedLeadIds).sumOf { it.estimatedValue }
    }

    /** Domyślnie rok bieżący — wykres startowy nie pyta użytkownika o zakres. */
    fun currentYear(): Int = Year.now(DateRangeFilter.ZONE).value

    private fun List<Duration>.averageMinutes(): Long? =
        if (isEmpty()) null else sumOf { it.toMinutes() } / size

    /**
     * Percentyl metodą „najbliższej rangi": posortuj, weź element o randze ceil(p·n).
     *
     * Bez interpolacji — przy kilkudziesięciu pomiarach interpolacja tworzy wartość,
     * której nigdy nie było, a tutaj chodzi o zdanie „tyle trwała jedna z najgorszych
     * odpowiedzi", nie o estymację rozkładu.
     */
    private fun List<Duration>.percentileMinutes(p: Double): Long? {
        if (isEmpty()) return null
        val sorted = sorted()
        val rank = Math.ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1].toMinutes()
    }

    private companion object {
        const val WINDOW_DAYS = 14L
        const val P95 = 0.95
    }
}

data class LeadOverviewDto(
    val year: Int,
    /** Zawsze dwanaście pozycji, od stycznia do grudnia. */
    val months: List<LeadIntakeMonthDto>,
    /** Ile pieniędzy zamieniło się w rezerwacje od poniedziałku — pokwitowanie, nie zadanie. */
    val confirmedValueThisWeek: Long,
    val recent: LeadRecentDto
)

data class LeadIntakeMonthDto(
    /** 1 = styczeń … 12 = grudzień. */
    val month: Int,
    /** Liczba zapytań; null, gdy miesiąc jeszcze nie nadszedł. */
    val count: Int?,
    /** Suma wycen zapytań tego miesiąca w groszach; null, gdy miesiąc nie nadszedł. */
    val value: Long?
)

/**
 * Ostatnie [days] dni na tle punktu odniesienia.
 *
 * Każda liczba jedzie z LICZNOŚCIĄ, z której powstała ([inquiriesPrevious], [answered],
 * [answeredAllTime]). To nie jest nadmiarowość: interfejs musi móc zamilczeć tam, gdzie
 * próba jest za mała, a tej decyzji nie da się podjąć, mając samą średnią. „Średnia
 * z trzech odpowiedzi" wygląda na ekranie identycznie jak „średnia ze stu".
 */
data class LeadRecentDto(
    val days: Int,
    val inquiries: Int,
    val inquiriesPrevious: Int,
    /** Średni czas pierwszej odpowiedzi w oknie, w minutach; null, gdy nikt nie odpowiedział. */
    val averageResponseMinutes: Long?,
    /** To samo z całej historii studia — punkt odniesienia dla okna. */
    val averageResponseMinutesAllTime: Long?,
    /** P95 z całej historii: 95% odpowiedzi poszło szybciej niż to. */
    val p95ResponseMinutes: Long?,
    /** Ile zapytań z okna doczekało się odpowiedzi — podstawa średniej okna. */
    val answered: Int,
    /** Ile zapytań w całej historii doczekało się odpowiedzi — podstawa średniej i P95. */
    val answeredAllTime: Int
)

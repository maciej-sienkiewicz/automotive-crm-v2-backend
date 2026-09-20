package pl.detailing.crm.leads.analytics

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.shared.DateRangeFilter
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.time.Year

/**
 * Ile zapytań wpływa miesiąc po miesiącu — jedyny wykres na domyślnym ekranie Leadów.
 *
 * ── Dlaczego to nie jedzie z /analytics ────────────────────────────────────
 *
 * Bo pełna analityka to timeline, macierz dni tygodnia, segmenty aut, odstające
 * marki i kilkaset surowych faktów do filtrowania w przeglądarce. Cały ten rachunek
 * wisiałby na ścieżce WEJŚCIA do modułu — trzydzieści razy dziennie, żeby narysować
 * dwanaście punktów. Ten endpoint czyta dwie kolumny i grupuje je po miesiącach.
 *
 * ── Dwie jednostki, jedna prawda ───────────────────────────────────────────
 *
 * Wykres przełącza się między złotówkami a sztukami, więc oba pola jadą razem
 * w jednej odpowiedzi: przełącznik ma przerysowywać wykres, a nie iść po dane.
 * Te dwie liczby opowiadają różne rzeczy i dlatego obie są potrzebne — czternaście
 * zapytań o mycie i trzy o folię to ta sama sztuka i zupełnie inny miesiąc, ale
 * miesiąc z jedną wyceną na dziesięć tysięcy i miesiąc z dwudziestoma zapytaniami
 * bez wyceny wyglądają w złotówkach tak samo, choć pracy jest w nich dwadzieścia razy
 * więcej.
 *
 * [LeadIntakeMonthDto.count] i [LeadIntakeMonthDto.value] są null dla miesięcy,
 * które jeszcze nie nadeszły — „nic nie przyszło" i „miesiąc nie nadszedł" to dwie
 * różne rzeczy i linia ma się w tym drugim przypadku urwać, a nie spaść do zera.
 */
@Service
class LeadIntakeYearHandler(
    private val leadRepository: LeadRepository,
    private val historyRepository: LeadStatusHistoryRepository
) {

    @Transactional(readOnly = true)
    fun handle(studioId: StudioId, year: Int): LeadIntakeYearDto {
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

        return LeadIntakeYearDto(
            year = year,
            months = (1..12).map { month ->
                if (month > lastLivedMonth) LeadIntakeMonthDto(month, null, null)
                else LeadIntakeMonthDto(month, counts[month - 1], values[month - 1])
            },
            confirmedValueThisWeek = confirmedValueThisWeek(studioId)
        )
    }

    /**
     * Ile pieniędzy zamieniło się w rezerwacje od poniedziałku.
     *
     * Jedyna kwota na ekranie startowym, która nie jest zadaniem, tylko pokwitowaniem:
     * bez niej widok wyłącznie wymaga i nigdy nie mówi „zrobione". Jedzie tą samą
     * odpowiedzią co wykres, bo należy do tego samego ekranu — osobne zapytanie
     * o jedną liczbę byłoby drugim żądaniem na wejściu do modułu.
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
}

data class LeadIntakeYearDto(
    val year: Int,
    /** Zawsze dwanaście pozycji, od stycznia do grudnia. */
    val months: List<LeadIntakeMonthDto>,
    /** Ile pieniędzy zamieniło się w rezerwacje od poniedziałku — pokwitowanie, nie zadanie. */
    val confirmedValueThisWeek: Long
)

data class LeadIntakeMonthDto(
    /** 1 = styczeń … 12 = grudzień. */
    val month: Int,
    /** Liczba zapytań; null, gdy miesiąc jeszcze nie nadszedł. */
    val count: Int?,
    /** Suma wycen zapytań tego miesiąca w groszach; null, gdy miesiąc nie nadszedł. */
    val value: Long?
)

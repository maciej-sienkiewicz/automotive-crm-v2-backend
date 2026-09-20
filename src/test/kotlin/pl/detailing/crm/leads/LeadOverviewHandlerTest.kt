package pl.detailing.crm.leads

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.leads.analytics.LeadOverviewHandler
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.shared.DateRangeFilter
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Wykres „co miesiąc wpływa" — dwanaście punktów w dwóch jednostkach.
 *
 * Testujemy trzy rzeczy, których nie widać po kształcie kodu: że miesiąc liczy się
 * w strefie studia, że miesiąc nieprzeżyty to dziura, a nie zero, i że sztuki idą
 * obok złotówek, a nie zamiast nich.
 */
class LeadOverviewHandlerTest {

    private val leadRepository = mockk<LeadRepository>()
    private val historyRepository = mockk<LeadStatusHistoryRepository>()
    private val handler = LeadOverviewHandler(leadRepository, historyRepository)

    private val studioId = StudioId(UUID.randomUUID())

    /** Wpłynięcie o podanej porze CZASU POLSKIEGO — tak, jak widzi je właściciel. */
    private fun fact(local: LocalDateTime, valueCents: Long): Array<Any> =
        arrayOf(local.atZone(DateRangeFilter.ZONE).toInstant(), valueCents)

    /** Studio bez rezerwacji w tym tygodniu i bez ani jednej odpowiedzi w historii. */
    private fun noConfirmations() {
        every { historyRepository.findByStudioIdAndCreatedAtBetween(any(), any(), any()) } returns emptyList()
        every { leadRepository.findResponseFacts(any(), any()) } returns emptyList()
    }

    /** Pary (wpłynęło, odpowiedziano) o zadanym czasie reakcji w godzinach. */
    private fun responses(vararg hours: Long): List<Array<Any>> = hours.map { h ->
        val created = Instant.now().minusSeconds(30 * 86_400)
        arrayOf<Any>(created, created.plusSeconds(h * 3600))
    }

    @Test
    fun `sztuki i zlotowki jada obok siebie, miesiac po miesiacu`() {
        noConfirmations()
        every { leadRepository.findIntakeFacts(any(), any(), any()) } returns listOf(
            fact(LocalDateTime.of(2024, 3, 4, 10, 0), 190_000),
            fact(LocalDateTime.of(2024, 3, 27, 16, 30), 0),
            fact(LocalDateTime.of(2024, 7, 1, 9, 15), 890_000)
        )

        val result = handler.handle(studioId, 2024)

        assertEquals(12, result.months.size)
        val march = result.months[2]
        assertEquals(2, march.count)
        assertEquals(190_000L, march.value)

        // Lipiec: jedno zapytanie, ale najdroższe w roku. Sztuki i kwota mówią tu
        // dwie różne rzeczy i o to właśnie chodzi w przełączniku nad wykresem.
        val july = result.months[6]
        assertEquals(1, july.count)
        assertEquals(890_000L, july.value)

        assertEquals(0, result.months[0].count)
        assertEquals(0L, result.months[0].value)
    }

    /**
     * Zapytanie z 1 marca o 00:30 czasu polskiego jest marcowe, choć w UTC wpadło
     * jeszcze w luty. Grupowanie po stronie bazy (`date_part` bez strefy) przerzucałoby
     * takie zapytania do poprzedniego miesiąca — po jednym na każdą granicę miesiąca.
     */
    @Test
    fun `miesiac liczy sie w strefie studia, nie w UTC`() {
        noConfirmations()
        every { leadRepository.findIntakeFacts(any(), any(), any()) } returns listOf(
            fact(LocalDateTime.of(2024, 3, 1, 0, 30), 50_000)
        )

        val result = handler.handle(studioId, 2024)

        assertEquals(0, result.months[1].count)
        assertEquals(1, result.months[2].count)
    }

    /**
     * „Nic nie przyszło" i „miesiąc nie nadszedł" to dwie różne rzeczy: pierwsze jest
     * zerem na wykresie, drugie dziurą w linii. Zlanie ich w jedno rysowałoby spadek
     * do zera w każdym roku bieżącym, od dzisiaj do grudnia.
     */
    @Test
    fun `miesiace jeszcze nieprzezyte sa dziura, nie zerem`() {
        noConfirmations()
        every { leadRepository.findIntakeFacts(any(), any(), any()) } returns emptyList()

        val thisYear = Year.now(DateRangeFilter.ZONE).value
        val thisMonth = LocalDate.now(DateRangeFilter.ZONE).monthValue
        val result = handler.handle(studioId, thisYear)

        assertEquals(0, result.months[thisMonth - 1].count)
        if (thisMonth < 12) {
            assertNull(result.months[thisMonth].count)
            assertNull(result.months[thisMonth].value)
        }
    }

    // ── Ostatnie dwa tygodnie ───────────────────────────────────────────────

    /**
     * Okno i poprzednie okno mają tę samą długość, więc porównanie znaczy to samo
     * pierwszego i trzydziestego dnia miesiąca.
     */
    @Test
    fun `okno czternastu dni jedzie razem z poprzednim oknem`() {
        every { historyRepository.findByStudioIdAndCreatedAtBetween(any(), any(), any()) } returns emptyList()
        every { leadRepository.findResponseFacts(any(), any()) } returns emptyList()

        val now = Instant.now()
        val windowStart = now.minus(14, ChronoUnit.DAYS)
        // Rok: trzy zapytania. Okno: dwa. Poprzednie okno: pięć.
        every { leadRepository.findIntakeFacts(any(), any(), any()) } returns emptyList()
        every {
            leadRepository.findIntakeFacts(any(), match { it.isAfter(windowStart.minusSeconds(60)) }, any())
        } returns List(2) { fact(LocalDateTime.now(), 0) }
        every {
            leadRepository.findIntakeFacts(
                any(),
                match { it.isBefore(windowStart.minusSeconds(60)) && it.isAfter(now.minus(29, ChronoUnit.DAYS)) },
                match { it.isBefore(now.minusSeconds(60)) }
            )
        } returns List(5) { fact(LocalDateTime.now(), 0) }

        val recent = handler.handle(studioId, 2024).recent

        assertEquals(14, recent.days)
        assertEquals(2, recent.inquiries)
        assertEquals(5, recent.inquiriesPrevious)
    }

    /**
     * Średnia z okna i P95 z całej historii to dwie różne odpowiedzi na dwa różne
     * pytania: „ile trwa zwykle" i „ile trwało, gdy poszło źle". Kafel pokazuje obie,
     * bo sama średnia przy jednym zapomnianym zapytaniu wygląda tak samo, jak przy
     * dwudziestu obsłużonych na czas.
     */
    @Test
    fun `srednia liczy sie z okna, a P95 z calej historii`() {
        every { historyRepository.findByStudioIdAndCreatedAtBetween(any(), any(), any()) } returns emptyList()
        every { leadRepository.findIntakeFacts(any(), any(), any()) } returns emptyList()

        // Historia: nagminnie szybko, raz fatalnie. Okno: same szybkie.
        every { leadRepository.findResponseFacts(any(), Instant.EPOCH) } returns
            responses(1, 1, 2, 2, 2, 3, 3, 4, 4, 100)
        every { leadRepository.findResponseFacts(any(), match { it != Instant.EPOCH }) } returns
            responses(1, 3)

        val recent = handler.handle(studioId, 2024).recent

        assertEquals(2 * 60L, recent.averageResponseMinutes)
        assertEquals(2, recent.answered)
        // 10 pomiarów: ranga ceil(0,95 × 10) = 10, czyli ten jeden fatalny.
        assertEquals(100 * 60L, recent.p95ResponseMinutes)
        assertEquals(12 * 60L + 12, recent.averageResponseMinutesAllTime)
        assertEquals(10, recent.answeredAllTime)
    }

    @Test
    fun `bez ani jednej odpowiedzi czasy sa puste, a nie zerowe`() {
        noConfirmations()
        every { leadRepository.findIntakeFacts(any(), any(), any()) } returns emptyList()

        val recent = handler.handle(studioId, 2024).recent

        assertNull(recent.averageResponseMinutes)
        assertNull(recent.averageResponseMinutesAllTime)
        assertNull(recent.p95ResponseMinutes)
        assertEquals(0, recent.answered)
    }

    @Test
    fun `rok przyszly jest pusty w calosci`() {
        noConfirmations()
        every { leadRepository.findIntakeFacts(any(), any(), any()) } returns emptyList()

        val result = handler.handle(studioId, Year.now(DateRangeFilter.ZONE).value + 1)

        assertEquals(12, result.months.count { it.count == null })
    }
}

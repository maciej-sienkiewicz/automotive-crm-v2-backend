package pl.detailing.crm.leads

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.leads.analytics.LeadIntakeYearHandler
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.shared.DateRangeFilter
import pl.detailing.crm.shared.StudioId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year
import java.util.UUID

/**
 * Wykres „co miesiąc wpływa" — dwanaście punktów w dwóch jednostkach.
 *
 * Testujemy trzy rzeczy, których nie widać po kształcie kodu: że miesiąc liczy się
 * w strefie studia, że miesiąc nieprzeżyty to dziura, a nie zero, i że sztuki idą
 * obok złotówek, a nie zamiast nich.
 */
class LeadIntakeYearHandlerTest {

    private val leadRepository = mockk<LeadRepository>()
    private val historyRepository = mockk<LeadStatusHistoryRepository>()
    private val handler = LeadIntakeYearHandler(leadRepository, historyRepository)

    private val studioId = StudioId(UUID.randomUUID())

    /** Wpłynięcie o podanej porze CZASU POLSKIEGO — tak, jak widzi je właściciel. */
    private fun fact(local: LocalDateTime, valueCents: Long): Array<Any> =
        arrayOf(local.atZone(DateRangeFilter.ZONE).toInstant(), valueCents)

    private fun noConfirmations() {
        every { historyRepository.findByStudioIdAndCreatedAtBetween(any(), any(), any()) } returns emptyList()
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

    @Test
    fun `rok przyszly jest pusty w calosci`() {
        noConfirmations()
        every { leadRepository.findIntakeFacts(any(), any(), any()) } returns emptyList()

        val result = handler.handle(studioId, Year.now(DateRangeFilter.ZONE).value + 1)

        assertEquals(12, result.months.count { it.count == null })
    }
}

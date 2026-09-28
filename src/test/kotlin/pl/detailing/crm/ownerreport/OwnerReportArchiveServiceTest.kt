package pl.detailing.crm.ownerreport

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.ownerreport.domain.ClosedTotals
import pl.detailing.crm.ownerreport.domain.ReportComparison
import pl.detailing.crm.ownerreport.domain.ReportLength
import pl.detailing.crm.ownerreport.domain.ReportPeriod
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.visit.domain.VisitFixtures
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.VisitServiceItemEntity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Tabela raportów czyta same pozycje, a PDF całe wizyty. Kwota musi wyjść ta sama:
 * te testy składają wizytę z pozycji w każdym stanie i porównują z [pl.detailing.crm.visit.domain.Visit.calculateTotalGross].
 */
class OwnerReportArchiveServiceTest {

    private val studioId = StudioId.random()
    private val today = LocalDate.of(2026, 9, 28)
    private val lastWeekWednesday = LocalDate.of(2026, 9, 23).atTime(LocalTime.NOON).atZone(ReportPeriod.ZONE).toInstant()

    private val pickups = mutableListOf<Array<Any>>()
    private val items = mutableListOf<Array<Any>>()
    private val from = slot<Instant>()
    private val visits: VisitRepository = mockk {
        every { findHandedOverPickups(any(), capture(from), any()) } answers { pickups }
        every { findHandedOverItems(any(), any(), any()) } answers { items }
    }
    private val studio: StudioEntity = mockk {
        every { createdAt } returns LocalDate.of(2026, 9, 3).atStartOfDay(ReportPeriod.ZONE).toInstant()
    }
    private val studios: StudioRepository = mockk { every { findByStudioId(studioId.value) } returns studio }
    private val service = OwnerReportArchiveService(visits, studios)

    private fun handedOver(serviceItems: List<VisitServiceItem>, at: Instant = lastWeekWednesday): Long {
        val visit = VisitFixtures.visit(studioId = studioId, items = serviceItems)
        val entity = VisitEntity.fromDomain(visit)
        pickups += arrayOf<Any>(entity.id, at)
        serviceItems.forEach { items += arrayOf<Any>(entity.id, VisitServiceItemEntity.fromDomain(it, entity)) }
        return visit.calculateTotalGross().amountInCents
    }

    private fun lastWeek(comparison: ReportComparison = ReportComparison.PREVIOUS) =
        service.archive(studioId, ReportLength.WEEK, comparison, today).rows[1]

    @Test
    fun `kwota jak na wizycie - 1900,00 zl zostaje 1900,00, odrzucone i dopiero proponowane nie wchodza`() {
        val expected = handedOver(
            listOf(
                VisitFixtures.serviceItem(finalPriceNet = 154_472, finalPriceGross = 190_000),
                VisitFixtures.serviceItem(finalPriceNet = 50_000, finalPriceGross = 61_500, status = VisitServiceStatus.REJECTED),
                VisitFixtures.serviceItem(finalPriceNet = 30_000, finalPriceGross = 36_900).copy(
                    status = VisitServiceStatus.PENDING,
                    pendingOperation = pl.detailing.crm.shared.PendingOperation.ADD
                )
            )
        )

        assertEquals(190_000, expected)
        assertEquals(ClosedTotals(1, 190_000), lastWeek().closed)
    }

    @Test
    fun `zmiana czekajaca na zgode klienta - liczy sie cena potwierdzona, jak na wizycie`() {
        val confirmed = VisitFixtures.serviceItem(finalPriceNet = 154_472, finalPriceGross = 190_000)
        val pendingEdit = confirmed.toPending(newBasePriceNet = Money(100_000))
        val expected = handedOver(listOf(pendingEdit))

        assertEquals(190_000, expected)
        assertEquals(190_000, lastWeek().closed.grossCents)
    }

    @Test
    fun `wizyta bez zaliczonych pozycji jest wizyta zamknieta za 0 zl`() {
        handedOver(emptyList())
        handedOver(listOf(VisitFixtures.serviceItem(finalPriceGross = 12_300)))

        assertEquals(ClosedTotals(2, 12_300), lastWeek().closed)
    }

    @Test
    fun `mediana pobiera dane szesc tygodni przed tygodniem zalozenia konta`() {
        service.archive(studioId, ReportLength.WEEK, ReportComparison.MEDIAN, today)

        assertEquals(ReportPeriod.containing(ReportLength.WEEK, LocalDate.of(2026, 7, 20)).startInclusive, from.captured)
    }

    @Test
    fun `brak studia - tylko trwajacy okres, bez wyjatku`() {
        every { studios.findByStudioId(studioId.value) } returns null

        val archive = service.archive(studioId, ReportLength.MONTH, ReportComparison.PREVIOUS, today)

        assertEquals(today, archive.since)
        assertEquals(1, archive.rows.size)
    }
}

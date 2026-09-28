package pl.detailing.crm.protocol.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.PendingOperation
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.visit.domain.VisitFixtures

/**
 * Lista usług na protokole przyjęcia: domyślnie same nazwy (część studiów świadomie
 * pokazuje tylko kwotę łączną), po włączeniu ustawienia - cena brutto każdej usługi.
 */
class CrmDataResolverServicesListTest {

    private val ceramic = VisitFixtures.serviceItem(finalPriceNet = 154_472, finalPriceGross = 190_000)
        .copy(serviceName = "Powłoka ceramiczna 3 lata", basePriceGross = Money(190_000))
    private val ppf = VisitFixtures.serviceItem(finalPriceNet = 300_000, finalPriceGross = 369_000)
        .copy(serviceName = "Folia PPF - pakiet przód", customNote = "bez lusterek")
    private val rejected = VisitFixtures.serviceItem(status = VisitServiceStatus.REJECTED).copy(serviceName = "Odrzucona")
    private val proposed = VisitFixtures.serviceItem(status = VisitServiceStatus.PENDING)
        .copy(serviceName = "Propozycja", pendingOperation = PendingOperation.ADD)
    private val visit = VisitFixtures.visit(items = listOf(ceramic, ppf, rejected, proposed))

    @Test
    fun `domyslnie same nazwy i notatki, bez cen`() {
        assertEquals(
            "Powłoka ceramiczna 3 lata\nFolia PPF - pakiet przód (bez lusterek)",
            CrmDataResolver.servicesList(visit, withPrices = false)
        )
    }

    @Test
    fun `z cenami - brutto wpisane 1900,00 zl zostaje 1900,00, nie 1900,01`() {
        assertEquals(
            "Powłoka ceramiczna 3 lata (1900.00 PLN brutto)\n" +
                "Folia PPF - pakiet przód (bez lusterek) (3690.00 PLN brutto)",
            CrmDataResolver.servicesList(visit, withPrices = true)
        )
    }

    @Test
    fun `ceny w nawiasach sumuja sie do kwoty lacznej co do grosza`() {
        val listed = Regex("""\((\d+)\.(\d{2}) PLN brutto\)""")
            .findAll(CrmDataResolver.servicesList(visit, withPrices = true))
            .sumOf { it.groupValues[1].toLong() * 100 + it.groupValues[2].toLong() }

        assertEquals(visit.calculateTotalGross().amountInCents, listed)
    }

    @Test
    fun `wariant zwiezly - po przecinku, cena bez slowa brutto przy kazdej pozycji`() {
        assertEquals(
            "Powłoka ceramiczna 3 lata (1900.00 PLN), Folia PPF - pakiet przód (bez lusterek) (3690.00 PLN)",
            CrmDataResolver.servicesListInline(visit, withPrices = true)
        )
        assertEquals(
            "Powłoka ceramiczna 3 lata, Folia PPF - pakiet przód (bez lusterek)",
            CrmDataResolver.servicesListInline(visit, withPrices = false)
        )
    }
}

package pl.detailing.crm.visit.get

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.Money
import pl.detailing.crm.visit.domain.VisitFixtures

/**
 * „Wydał łącznie" w karcie klienta na widoku wizyty. Wcześniej pole brutto odpowiedzi
 * niosło sumę NETTO — klient z wizytą za 1900,00 zł „wydał" 1544,72 zł.
 */
class CustomerSpendTest {

    @Test
    fun `brutto to suma brutto wizyt, a nie suma netto`() {
        val visits = listOf(
            VisitFixtures.visit(items = listOf(VisitFixtures.serviceItem(finalPriceNet = 154_472, finalPriceGross = 190_000))),
            VisitFixtures.visit(items = listOf(VisitFixtures.serviceItem(finalPriceNet = 100_000, finalPriceGross = 108_000)))
        )

        val (net, gross) = customerSpend(visits)

        assertEquals(Money(254_472), net)
        assertEquals(Money(298_000), gross)
    }

    @Test
    fun `brak wizyt to zero po obu stronach`() {
        assertEquals(Money.ZERO to Money.ZERO, customerSpend(emptyList()))
    }
}

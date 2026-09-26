package pl.detailing.crm.visit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.shared.VisitServiceItemId
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.shared.VisitStatus
import java.util.UUID

/**
 * Zmiana cen wizyty wydanej — jedyna droga to poprawka rozliczenia. Pilnuje §1
 * (wpisane brutto zostaje co do grosza) i tego, że nic poza wskazanymi pozycjami
 * się nie rusza.
 */
class VisitCorrectSettledPricesTest {

    private val user = UserId.random()
    private val confirmed = VisitFixtures.serviceItem(finalPriceNet = 50_000, finalPriceGross = 61_500)
    private val discounted = VisitFixtures.serviceItem(finalPriceNet = 90_000, finalPriceGross = 110_700)
        .copy(basePriceNet = Money(100_000), adjustmentType = AdjustmentType.PERCENT, adjustmentValue = -1_000)
    private val rejected = VisitFixtures.serviceItem(status = VisitServiceStatus.REJECTED)
    private val completed = VisitFixtures.visit(status = VisitStatus.COMPLETED, items = listOf(confirmed, discounted, rejected))

    @Test
    fun `cena wpisana od brutto 1900 zl zostaje co do grosza`() {
        val after = completed.correctSettledPrices(
            mapOf(confirmed.id to SettledPrice(net = 154_472, gross = 190_000, vatRate = VatRate.VAT_23)), user
        )
        val item = after.serviceItems.first { it.id == confirmed.id }
        assertEquals(190_000, item.finalPriceGross.amountInCents)
        assertEquals(154_472, item.finalPriceNet.amountInCents)
        assertEquals(Money(190_000), item.basePriceGross)
    }

    @Test
    fun `cena od netta - brutto liczone z netta, bez dokladnego brutto`() {
        val after = completed.correctSettledPrices(
            mapOf(confirmed.id to SettledPrice(net = 154_472, gross = null, vatRate = VatRate.VAT_23)), user
        )
        val item = after.serviceItems.first { it.id == confirmed.id }
        assertEquals(190_001, item.finalPriceGross.amountInCents, "netto → brutto nie jest „na\" — dlatego brutto się przechowuje")
        assertNull(item.basePriceGross)
    }

    @Test
    fun `zmiana stawki na 8 procent i zw`() {
        val at8 = completed.correctSettledPrices(mapOf(confirmed.id to SettledPrice(50_000, null, VatRate.VAT_8)), user)
        assertEquals(54_000, at8.serviceItems.first().finalPriceGross.amountInCents)
        val zw = completed.correctSettledPrices(mapOf(confirmed.id to SettledPrice(50_000, 50_000, VatRate.VAT_ZW)), user)
        assertEquals(50_000, zw.serviceItems.first().finalPriceGross.amountInCents)
        assertEquals(VatRate.VAT_ZW, zw.serviceItems.first().vatRate)
    }

    @Test
    fun `cena wprost kasuje rabat pozycji, a pozostale pozycje zostaja nietkniete`() {
        val after = completed.correctSettledPrices(mapOf(discounted.id to SettledPrice(80_000, null, VatRate.VAT_23)), user)
        val changed = after.serviceItems.first { it.id == discounted.id }
        assertEquals(AdjustmentType.FIXED_NET, changed.adjustmentType)
        assertEquals(0, changed.adjustmentValue)
        assertEquals(80_000, changed.finalPriceNet.amountInCents)
        assertSame(confirmed, after.serviceItems.first { it.id == confirmed.id })
        assertSame(rejected, after.serviceItems.first { it.id == rejected.id })
        assertEquals(VisitServiceStatus.CONFIRMED, changed.status)
    }

    @Test
    fun `cena zero jest dozwolona (usluga gratis po fakcie)`() {
        val after = completed.correctSettledPrices(mapOf(confirmed.id to SettledPrice(0, null, VatRate.VAT_23)), user)
        assertEquals(0, after.serviceItems.first().finalPriceGross.amountInCents)
    }

    @Test
    fun `brutto rozjechane z netto o wiecej niz grosz - odmowa, o grosz - przyjete`() {
        assertThrows<ValidationException> {
            completed.correctSettledPrices(mapOf(confirmed.id to SettledPrice(154_472, 190_010, VatRate.VAT_23)), user)
        }
        completed.correctSettledPrices(mapOf(confirmed.id to SettledPrice(154_472, 190_000, VatRate.VAT_23)), user)
    }

    @Test
    fun `ujemna cena - odmowa`() {
        assertThrows<ValidationException> {
            completed.correctSettledPrices(mapOf(confirmed.id to SettledPrice(-1, null, VatRate.VAT_23)), user)
        }
    }

    @Test
    fun `pozycja odrzucona albo spoza wizyty nie nalezy do rozliczenia`() {
        assertThrows<ValidationException> {
            completed.correctSettledPrices(mapOf(rejected.id to SettledPrice(1_000, null, VatRate.VAT_23)), user)
        }
        assertThrows<ValidationException> {
            completed.correctSettledPrices(mapOf(VisitServiceItemId(UUID.randomUUID()) to SettledPrice(1_000, null, VatRate.VAT_23)), user)
        }
    }

    @Test
    fun `tylko wizyta wydana - w toku, gotowa, zarchiwizowana i odrzucona nie`() {
        listOf(VisitStatus.IN_PROGRESS, VisitStatus.READY_FOR_PICKUP, VisitStatus.ARCHIVED, VisitStatus.REJECTED).forEach { status ->
            assertThrows<IllegalStateTransitionException>("status $status") {
                completed.copy(status = status).correctSettledPrices(emptyMap(), user)
            }
        }
    }

    @Test
    fun `zwykla edycja uslug wizyty wydanej nadal jest zablokowana`() {
        assertThrows<IllegalStateTransitionException> {
            completed.saveServicesChanges(emptyList(), emptyList(), emptyList(), user)
        }
    }

    @Test
    fun `status wizyty sie nie zmienia, zmienia sie autor i czas zmiany`() {
        val after = completed.correctSettledPrices(mapOf(confirmed.id to SettledPrice(40_000, null, VatRate.VAT_23)), user)
        assertEquals(VisitStatus.COMPLETED, after.status)
        assertEquals(user, after.updatedBy)
        assertTrue(after.updatedAt >= completed.updatedAt, "indeks podobnych wizyt odświeża się po updatedAt")
        assertEquals(49_200 + 110_700, after.calculateTotalGross().amountInCents, "odrzucona pozycja dalej poza kwotą")
    }
}

package pl.detailing.crm.visitcard.upsell.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AdjustmentType
import java.util.UUID

/**
 * Brutto bazowe sugestii upsellu. Akceptacja przez klienta liczy pozycję wizyty od ceny
 * bazowej — wcześniej podawała tam brutto KOŃCOWE, więc rabat od brutto odejmował się
 * drugi raz. Sugestie sprzed kolumny base_price_gross odzyskują bazę z ceny końcowej
 * tylko tam, gdzie to jednoznaczne.
 */
class VisitUpsellSuggestionBaseGrossTest {

    private fun suggestion(
        adjustmentType: AdjustmentType,
        adjustmentValue: Long,
        finalPriceGross: Long,
        basePriceGross: Long? = null
    ) = VisitUpsellSuggestionEntity(
        id = UUID.randomUUID(),
        studioId = UUID.randomUUID(),
        visitId = UUID.randomUUID(),
        serviceId = UUID.randomUUID(),
        serviceName = "Powłoka ceramiczna",
        basePriceNet = 154_472,
        vatRate = 23,
        adjustmentType = adjustmentType,
        adjustmentValue = adjustmentValue,
        finalPriceNet = 0,
        finalPriceGross = finalPriceGross,
        basePriceGross = basePriceGross,
        note = null,
        createdBy = UUID.randomUUID()
    )

    @Test
    fun `zapisane brutto bazowe ma pierwszenstwo`() {
        val s = suggestion(AdjustmentType.FIXED_GROSS, 10_000, finalPriceGross = 180_000, basePriceGross = 190_000)

        assertEquals(190_000L, s.exactBaseGross())
    }

    @Test
    fun `stara sugestia bez rabatu - baza to cena koncowa`() {
        val s = suggestion(AdjustmentType.PERCENT, 0, finalPriceGross = 190_000)

        assertEquals(190_000L, s.exactBaseGross())
    }

    @Test
    fun `stara sugestia z rabatem od brutto - baza to cena koncowa plus rabat, nie cena koncowa`() {
        val s = suggestion(AdjustmentType.FIXED_GROSS, 10_000, finalPriceGross = 180_000)

        assertEquals(190_000L, s.exactBaseGross())
    }

    @Test
    fun `stara sugestia z rabatem od netta - bazy nie da sie odwrocic`() {
        val s = suggestion(AdjustmentType.PERCENT, -1_000, finalPriceGross = 171_001)

        assertNull(s.exactBaseGross())
    }

    @Test
    fun `SET_GROSS z zerem to cena 0 zl, nie brak korekty`() {
        val s = suggestion(AdjustmentType.SET_GROSS, 0, finalPriceGross = 0)

        assertNull(s.exactBaseGross())
    }

    // ── Cena „przed rabatem" pokazywana klientowi ─────────────────────────────

    @Test
    fun `cena przed rabatem to dokladne brutto bazy`() {
        val s = suggestion(AdjustmentType.PERCENT, -1_000, finalPriceGross = 171_001, basePriceGross = 190_000)

        assertEquals(190_000L, s.originalPriceGross())
    }

    @Test
    fun `stara sugestia bez rabatu nie pokazuje falszywej obnizki 1900,01 na 1900,00`() {
        val s = suggestion(AdjustmentType.PERCENT, 0, finalPriceGross = 190_000)

        assertEquals(190_000L, s.originalPriceGross())
    }

    @Test
    fun `gdy bazy nie da sie odtworzyc - brutto z netta, jak dawniej`() {
        val s = suggestion(AdjustmentType.PERCENT, -1_000, finalPriceGross = 171_001)

        assertEquals(190_001L, s.originalPriceGross()) // 154472 · 1,23 = 190000,56
    }
}

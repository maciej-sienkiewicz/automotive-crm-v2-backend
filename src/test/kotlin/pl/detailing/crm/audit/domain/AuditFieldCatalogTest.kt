package pl.detailing.crm.audit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuditFieldCatalogTest {

    /** The catalog separates thousands and the currency with a no-break space. */
    private val nbsp = "\u00A0"

    @Test
    fun `money is grouped and suffixed`() {
        assertEquals("1${nbsp}230,00${nbsp}zł", AuditFieldCatalog.formatValue("1230.00", AuditValueType.MONEY))
        assertEquals("0,00${nbsp}zł", AuditFieldCatalog.formatValue("0", AuditValueType.MONEY))
        assertEquals("123${nbsp}456${nbsp}789,99${nbsp}zł", AuditFieldCatalog.formatValue("123456789.99", AuditValueType.MONEY))
        assertEquals("-50,50${nbsp}zł", AuditFieldCatalog.formatValue("-50.5", AuditValueType.MONEY))
    }

    @Test
    fun `money accepts a comma decimal separator`() {
        assertEquals("1${nbsp}230,50${nbsp}zł", AuditFieldCatalog.formatValue("1230,50", AuditValueType.MONEY))
    }

    @Test
    fun `an unparseable value falls back to the raw string`() {
        assertEquals("brak", AuditFieldCatalog.formatValue("brak", AuditValueType.MONEY))
        assertEquals("kiedyś", AuditFieldCatalog.formatValue("kiedyś", AuditValueType.DATE_TIME))
    }

    @Test
    fun `dates render in local time`() {
        // 2026-02-17T08:30:00Z is 09:30 in Warsaw (CET, UTC+1).
        assertEquals("17.02.2026, 09:30", AuditFieldCatalog.formatValue("2026-02-17T08:30:00Z", AuditValueType.DATE_TIME))
        assertEquals("17.02.2026", AuditFieldCatalog.formatValue("2026-02-17", AuditValueType.DATE))
        assertEquals("17.02.2026, 09:30", AuditFieldCatalog.formatValue("2026-02-17T09:30:00", AuditValueType.DATE_TIME))
    }

    @Test
    fun `booleans and known enums are translated`() {
        assertEquals("Tak", AuditFieldCatalog.formatValue("true", AuditValueType.BOOLEAN))
        assertEquals("Nie", AuditFieldCatalog.formatValue("false", AuditValueType.BOOLEAN))
        assertEquals("Gotowe do odbioru", AuditFieldCatalog.formatValue("READY_FOR_PICKUP", AuditValueType.ENUM))
    }

    @Test
    fun `an unmapped enum code is left readable rather than guessed at`() {
        assertEquals("SOME_FUTURE_STATUS", AuditFieldCatalog.formatValue("SOME_FUTURE_STATUS", AuditValueType.ENUM))
    }

    @Test
    fun `secrets never reach the response`() {
        val change = FieldChange("passwordHash", "\$2a\$10\$realhash", "\$2a\$10\$newhash")
        val rendered = AuditFieldCatalog.render(change)

        assertEquals(AuditValueType.SECRET, rendered.type)
        assertNull(rendered.oldValue)
        assertNull(rendered.newValue)
        assertEquals("••••••", rendered.newValueDisplay)
        assertTrue(rendered.oldValueDisplay!!.none { it.isLetterOrDigit() })
    }

    @Test
    fun `known fields get their Polish label`() {
        assertEquals("Planowane zakończenie", AuditFieldCatalog.labelOf("estimatedCompletionDate"))
        assertEquals("Nr rejestracyjny", AuditFieldCatalog.labelOf("licensePlate"))
    }

    @Test
    fun `an unknown field degrades to a readable label instead of breaking`() {
        assertEquals("Some Brand New Field", AuditFieldCatalog.labelOf("someBrandNewField"))
        assertEquals("Snake case field", AuditFieldCatalog.labelOf("snake_case_field"))
        assertEquals(AuditValueType.TEXT, AuditFieldCatalog.typeOf(FieldChange("someBrandNewField", null, "x")))
    }

    @Test
    fun `an explicit value type overrides what the field name implies`() {
        val change = FieldChange("title", null, "1230.00", valueType = AuditValueType.MONEY)

        assertEquals(AuditValueType.MONEY, AuditFieldCatalog.typeOf(change))
        assertEquals("1${nbsp}230,00${nbsp}zł", AuditFieldCatalog.render(change).newValueDisplay)
    }

    @Test
    fun `a null value stays null rather than becoming the text null`() {
        assertNull(AuditFieldCatalog.formatValue(null, AuditValueType.MONEY))
    }
}

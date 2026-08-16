package pl.detailing.crm.protocol.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HtmlProtocolFillServiceTest {

    private val service = HtmlProtocolFillService()

    @Test
    fun `fills data-field elements with values`() {
        val html = """<html><body><div class="box" data-field="brand"></div></body></html>"""

        val filled = service.fill(html, mapOf("brand" to "Porsche"))

        assertTrue("""<div class="box" data-field="brand">Porsche</div>""" in filled)
    }

    @Test
    fun `escapes HTML in values`() {
        val html = """<html><div data-field="remarks"></div></html>"""

        val filled = service.fill(html, mapOf("remarks" to """<script>alert("x")</script>"""))

        assertFalse("<script>" in filled)
        assertTrue("&lt;script&gt;" in filled)
    }

    @Test
    fun `checkbox fields render mark only when truthy`() {
        val html = """<html><div data-field="keys"></div><div data-field="documents"></div></html>"""

        val filled = service.fill(
            html,
            mapOf("keys" to "Yes", "documents" to "Off"),
            checkboxFields = setOf("keys", "documents")
        )

        assertTrue("""<div data-field="keys">✕</div>""" in filled)
        assertTrue("""<div data-field="documents"></div>""" in filled)
    }

    @Test
    fun `numeric value in non-checkbox field stays literal`() {
        val html = """<html><div data-field="mileage"></div></html>"""

        val filled = service.fill(html, mapOf("mileage" to "1"))

        assertTrue("""<div data-field="mileage">1</div>""" in filled)
    }

    @Test
    fun `multiline values become br tags`() {
        val html = """<html><div data-field="services"></div></html>"""

        val filled = service.fill(html, mapOf("services" to "Korekta\nPowłoka"))

        assertTrue("Korekta<br>Powłoka" in filled)
    }

    @Test
    fun `fields absent from template are ignored`() {
        val html = """<html><div data-field="brand"></div></html>"""

        val filled = service.fill(html, mapOf("brand" to "Audi", "nonexistent" to "x"))

        assertTrue("Audi" in filled)
        assertEquals(false, "nonexistent\">x" in filled)
    }
}

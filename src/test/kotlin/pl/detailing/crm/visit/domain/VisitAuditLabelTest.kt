package pl.detailing.crm.visit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VisitAuditLabelTest {

    @Test
    fun `the title the studio gave the visit wins`() {
        val label = VisitAuditLabel.of("Detailing Audi", "Audi", "A4", "WX 1234", "2026/0184")

        assertEquals("Detailing Audi", label)
    }

    @Test
    fun `a blank title falls through to the vehicle rather than rendering empty`() {
        val label = VisitAuditLabel.of("   ", "Audi", "A4", "WX 1234", "2026/0184")

        assertEquals("Audi A4 (WX 1234)", label)
    }

    @Test
    fun `the visit number is the last resort, not the default`() {
        val label = VisitAuditLabel.of(null, null, null, null, "2026/0184")

        assertEquals("Wizyta #2026/0184", label)
    }

    @Test
    fun `a vehicle with nothing recorded produces no label instead of stray spaces`() {
        assertNull(VisitAuditLabel.vehicleLabel(" ", "", null))
    }
}

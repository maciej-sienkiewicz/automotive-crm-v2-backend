package pl.detailing.crm.smscampaigns.consent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A save that changed nothing must not text the customer — `hasChanges` is the gate. */
class ServiceChangesSummaryTest {

    @Test
    fun `no names anywhere means no changes`() {
        assertFalse(ServiceChangesSummary(emptyList(), emptyList(), emptyList()).hasChanges)
    }

    @Test
    fun `any single list makes it a change`() {
        assertTrue(ServiceChangesSummary(listOf("Powłoka"), emptyList(), emptyList()).hasChanges)
        assertTrue(ServiceChangesSummary(emptyList(), listOf("Mycie"), emptyList()).hasChanges)
        assertTrue(ServiceChangesSummary(emptyList(), emptyList(), listOf("Korekta")).hasChanges)
    }
}

package pl.detailing.crm.audit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the contract the frontend binds to.
 *
 * Presentation used to live in exhaustive `when` blocks in the controller, so it was
 * technically enforced but easy to satisfy with a placeholder. Now that every constant
 * carries its own label, these tests are what stop a new action from shipping with an
 * empty string, a duplicated name, or a column-overflowing value.
 */
class AuditPresentationContractTest {

    /** Matches `audit_logs.module` / `audit_logs.action`, both varchar(50). */
    private val enumColumnLength = 50

    @Test
    fun `every action is presentable`() {
        AuditAction.entries.forEach { action ->
            assertTrue(action.label.isNotBlank()) { "${action.name} has no label" }
            assertTrue(action.sentence.isNotBlank()) { "${action.name} has no sentence" }
            assertTrue(action.name.length <= enumColumnLength) {
                "${action.name} does not fit in audit_logs.action varchar($enumColumnLength)"
            }
        }
    }

    @Test
    fun `every module is presentable`() {
        AuditModule.entries.forEach { module ->
            assertTrue(module.label.isNotBlank()) { "${module.name} has no label" }
            assertTrue(module.name.length <= enumColumnLength) {
                "${module.name} does not fit in audit_logs.module varchar($enumColumnLength)"
            }
        }
    }

    @Test
    fun `action sentences read as impersonal past tense, not as noun phrases`() {
        // Polish verbs inflect for gender and the actor's gender is not stored, so the feed
        // uses the impersonal form. A sentence identical to the label is a sign someone
        // pasted the noun phrase into both fields.
        val suspicious = AuditAction.entries.filter { it.label == it.sentence }

        assertTrue(suspicious.isEmpty()) {
            "These actions reuse their filter label as the feed sentence: ${suspicious.map { it.name }}"
        }
    }

    @Test
    fun `filter labels are unique so two options never look the same in the dropdown`() {
        val duplicated = AuditAction.entries
            .groupBy { it.label }
            .filterValues { it.size > 1 }

        assertTrue(duplicated.isEmpty()) {
            "Duplicate action labels: " + duplicated.map { (label, actions) -> "$label -> ${actions.map { it.name }}" }
        }
    }

    @Test
    fun `module labels are unique`() {
        assertEquals(
            AuditModule.entries.size,
            AuditModule.entries.map { it.label }.toSet().size,
            "Two modules share a label"
        )
    }

    @Test
    fun `money, payroll and security events default to a severity the owner will see`() {
        // The feed's "only what matters" default filters on severity, so these must not sit
        // at NORMAL where a busy studio would never scroll to them.
        val mustBeElevated = listOf(
            AuditAction.CASH_ADJUSTED,
            AuditAction.PAYROLL_PAID,
            AuditAction.COMPENSATION_SET,
            AuditAction.DOCUMENT_ISSUED,
            AuditAction.DOCUMENT_DELETED,
            AuditAction.CROSS_TENANT_ACCESS_ATTEMPT,
            AuditAction.ACCOUNT_LOCKED,
            AuditAction.UPSELL_REQUESTED,
            AuditAction.CAMPAIGN_LAUNCHED,
            AuditAction.SMS_SENT,
            AuditAction.VISIT_CREATED
        )

        mustBeElevated.forEach { action ->
            assertTrue(action.severity.weight >= AuditSeverity.HIGH.weight) {
                "${action.name} is ${action.severity} — it would be hidden behind 'show everything'"
            }
        }
    }

    @Test
    fun `high-volume documentation events stay low severity`() {
        listOf(
            AuditAction.PHOTO_ADDED,
            AuditAction.COMMENT_ADDED,
            AuditAction.NOTE_ADDED,
            AuditAction.VISIT_CARD_OPENED
        ).forEach { action ->
            assertEquals(AuditSeverity.LOW, action.severity) {
                "${action.name} would flood the default feed"
            }
        }
    }

    @Test
    fun `a module that owns no addressable resource is the exception, not the rule`() {
        val addressable = AuditModule.entries.count { it.resource != AuditResource.NONE }

        assertTrue(addressable > AuditModule.entries.size / 2) {
            "Most modules should point the feed at something the reader can open"
        }
    }
}

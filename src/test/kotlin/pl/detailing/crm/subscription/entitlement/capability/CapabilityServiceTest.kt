package pl.detailing.crm.subscription.entitlement.capability

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.shared.CapabilityLockedException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.domain.AddOn
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements
import java.util.UUID

class CapabilityServiceTest {

    private val entitlementService = mockk<EntitlementService>()
    private val service = CapabilityService(entitlementService)
    private val studioId = StudioId.random()

    private fun stubEntitlements(vararg features: FeatureKey) {
        every { entitlementService.getEntitlements(studioId) } returns StudioEntitlements(
            planKey = PlanKey.BASIC,
            planName = "Podstawowy",
            enabledFeatures = features.toSet(),
            activeAddOnKeys = emptySet()
        )
        every { entitlementService.getAllAddOns() } returns listOf(
            addOn(AddOnKey.CLIENT_COMMUNICATION, setOf(FeatureKey.SMS_EMAIL), 4900),
            addOn(AddOnKey.E_SIGNATURES, setOf(FeatureKey.E_SIGNATURES), 2900),
            addOn(AddOnKey.FINANCE_MODULE, setOf(FeatureKey.FINANCE), 4900)
        )
    }

    private fun addOn(key: AddOnKey, features: Set<FeatureKey>, priceCents: Long) = AddOn(
        id = UUID.randomUUID(),
        key = key,
        name = key.displayName,
        description = null,
        monthlyPriceGrossCents = priceCents,
        features = features,
        isActive = true,
        isAvailable = true
    )

    // ── Single-feature capabilities ──────────────────────────────────────────

    @Test
    fun `capability is enabled when its feature is entitled`() {
        stubEntitlements(FeatureKey.SMS_EMAIL)
        assertTrue(service.hasCapability(studioId, CapabilityKey.COMM_SEND_TRANSACTIONAL))
    }

    @Test
    fun `capability is disabled when its feature is missing`() {
        stubEntitlements(FeatureKey.CALENDAR)
        assertFalse(service.hasCapability(studioId, CapabilityKey.COMM_SEND_TRANSACTIONAL))
    }

    // ── Cross-module rule: SIGNATURE_REMOTE_REQUEST = E_SIGNATURES ∧ SMS_EMAIL ─

    @Test
    fun `remote signature request requires BOTH signatures and communication`() {
        stubEntitlements(FeatureKey.E_SIGNATURES)

        assertTrue(service.hasCapability(studioId, CapabilityKey.SIGNATURE_LOCAL))
        assertFalse(service.hasCapability(studioId, CapabilityKey.SIGNATURE_REMOTE_REQUEST))

        val decision = service.resolveOne(studioId, CapabilityKey.SIGNATURE_REMOTE_REQUEST)
        assertEquals(setOf(FeatureKey.SMS_EMAIL), decision.missingFeatures)
        assertEquals(listOf(AddOnKey.CLIENT_COMMUNICATION.name), decision.upsell.map { it.addOnKey })
    }

    @Test
    fun `remote signature request enabled with both modules`() {
        stubEntitlements(FeatureKey.E_SIGNATURES, FeatureKey.SMS_EMAIL)
        assertTrue(service.hasCapability(studioId, CapabilityKey.SIGNATURE_REMOTE_REQUEST))
    }

    // ── requireCapability: fail-closed with checkout-ready payload ───────────

    @Test
    fun `requireCapability throws with missing features and upsell options`() {
        stubEntitlements(FeatureKey.CALENDAR)

        val ex = assertThrows<CapabilityLockedException> {
            service.requireCapability(studioId, CapabilityKey.FINANCE_INVOICE_ISSUE)
        }
        assertEquals(CapabilityKey.FINANCE_INVOICE_ISSUE, ex.capability)
        assertEquals(setOf(FeatureKey.FINANCE), ex.missingFeatures)
        assertEquals(listOf(AddOnKey.FINANCE_MODULE.name), ex.upsell.map { it.addOnKey })
    }

    @Test
    fun `requireCapability passes silently when entitled`() {
        stubEntitlements(FeatureKey.FINANCE)
        service.requireCapability(studioId, CapabilityKey.FINANCE_INVOICE_ISSUE)
    }

    // ── resolve(): full map for the frontend ─────────────────────────────────

    @Test
    fun `resolve returns a decision for every capability`() {
        stubEntitlements(FeatureKey.SMS_EMAIL)
        val capabilities = service.resolve(studioId)

        assertEquals(CapabilityKey.entries.size, capabilities.decisions.size)
        assertTrue(capabilities.isEnabled(CapabilityKey.COMM_SEND_TRANSACTIONAL))
        assertFalse(capabilities.isEnabled(CapabilityKey.FINANCE_ACCESS))
    }

    @Test
    fun `every capability expression is non-empty and references real features`() {
        CapabilityKey.entries.forEach { capability ->
            assertTrue(capability.requiredFeatures.isNotEmpty(), "capability ${capability.name} has empty expression")
        }
    }
}

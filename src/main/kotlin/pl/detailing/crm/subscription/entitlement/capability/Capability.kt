package pl.detailing.crm.subscription.entitlement.capability

import pl.detailing.crm.subscription.entitlement.FeatureKey

/**
 * Capability = an atomic business action the system enforces.
 *
 * This is the third level of the entitlement vocabulary:
 *
 * ```
 * MODULE  (AddOnKey / PlanKey)  — what the customer buys
 *   └─ FEATURE (FeatureKey)     — what a plan/add-on switches on
 *        └─ CAPABILITY          — what the system actually enforces
 * ```
 *
 * A capability is defined as a conjunction (AND) over [FeatureKey]s, which is
 * what makes cross-module rules first-class citizens instead of ad-hoc ifs:
 * [SIGNATURE_REMOTE_REQUEST] requires BOTH e-signatures and the communication
 * module, because sending a signing link to the customer's phone is an SMS send.
 *
 * Enforcement points never inspect modules or plans directly — they ask
 * [CapabilityService] about a capability. Repackaging the price list
 * (moving features between add-ons) therefore never touches enforcing code.
 *
 * Keep the expressions FLAT — features only, never other capabilities.
 * Nesting capabilities creates dependency graphs with cycle potential.
 */
enum class CapabilityKey(
    val displayName: String,
    val requiredFeatures: Set<FeatureKey>
) {
    /** Transactional SMS/e-mail: reservation confirmations, reminders, visit-card links, pickup notifications. */
    COMM_SEND_TRANSACTIONAL(
        "Wysyłka SMS i e-mail do klientów",
        setOf(FeatureKey.SMS_EMAIL)
    ),

    /** Marketing campaigns (bulk SMS/e-mail) — a separate paid module from transactional messaging. */
    COMM_SEND_CAMPAIGN(
        "Kampanie marketingowe SMS i e-mail",
        setOf(FeatureKey.CAMPAIGNS)
    ),

    /** Collecting a signature on a studio-owned device (paired tablet). */
    SIGNATURE_LOCAL(
        "Podpis elektroniczny na tablecie",
        setOf(FeatureKey.E_SIGNATURES)
    ),

    /**
     * Sending a signing request to the customer's own device.
     * Cross-module rule: the signing link travels by SMS, so this needs
     * the communication module on top of e-signatures.
     */
    SIGNATURE_REMOTE_REQUEST(
        "Prośba o podpis na urządzeniu klienta",
        setOf(FeatureKey.E_SIGNATURES, FeatureKey.SMS_EMAIL)
    ),

    /** Access to the finance module: views, configuration, income documents. */
    FINANCE_ACCESS(
        "Kontrola nad finansami",
        setOf(FeatureKey.FINANCE)
    ),

    /** Issuing financial documents (receipt/invoice), including the auto-issue on visit completion. */
    FINANCE_INVOICE_ISSUE(
        "Wystawianie dokumentów finansowych",
        setOf(FeatureKey.FINANCE)
    ),

    /** Configuring KSeF credentials and sending/syncing invoices with KSeF. */
    FINANCE_KSEF(
        "Integracja z KSeF",
        setOf(FeatureKey.FINANCE)
    ),

    /** AI-assisted lead handling (offer composer etc.). */
    AI_LEAD_ASSIST(
        "Asystent AI przy obsłudze leadów",
        setOf(FeatureKey.AI_LEADS)
    ),

    /** Instagram competitor monitoring. */
    INSTAGRAM_MONITOR(
        "Monitoring konkurencji na Instagramie",
        setOf(FeatureKey.INSTAGRAM_MONITORING)
    ),

    /** Statistics & reports module. */
    STATS_VIEW(
        "Statystyki i raporty",
        setOf(FeatureKey.STATISTICS)
    );

    init {
        require(requiredFeatures.isNotEmpty()) { "Capability $name must require at least one feature" }
    }
}

/**
 * The resolved decision for a single capability of a single studio.
 *
 * [missingFeatures] is empty when [enabled]; otherwise it names exactly which
 * features the studio lacks — the UI uses it to point the upsell at the right
 * module instead of showing a generic "no access".
 */
data class CapabilityDecision(
    val capability: CapabilityKey,
    val enabled: Boolean,
    val missingFeatures: Set<FeatureKey>,
    val upsell: List<CapabilityUpsellOption>
) {
    companion object {
        fun allowed(capability: CapabilityKey) =
            CapabilityDecision(capability, enabled = true, missingFeatures = emptySet(), upsell = emptyList())
    }
}

/** An add-on that would provide (part of) the missing features — checkout-ready upsell metadata. */
data class CapabilityUpsellOption(
    val addOnKey: String,
    val addOnName: String,
    val monthlyPriceGrossCents: Long?,
    val providesFeatures: Set<FeatureKey>,
    val isAvailable: Boolean
)

/** Full capability map for a studio — the payload backing GET /api/v1/me/entitlements. */
data class StudioCapabilities(
    val decisions: Map<CapabilityKey, CapabilityDecision>
) {
    fun isEnabled(key: CapabilityKey): Boolean = decisions[key]?.enabled == true
}

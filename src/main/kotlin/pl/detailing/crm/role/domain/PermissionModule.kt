package pl.detailing.crm.role.domain

import pl.detailing.crm.subscription.entitlement.FeatureKey

/**
 * Groups permissions by functional area. Each module maps to an optional [FeatureKey];
 * when a feature key is present the studio must have that feature enabled for permissions
 * in this module to take effect, regardless of what the assigned role specifies.
 */
enum class PermissionModule(
    val displayName: String,
    /** Feature that must be enabled in the studio's entitlements. Null = always accessible. */
    val featureKey: FeatureKey?
) {
    // Covers the calendar too (an event IS a visit/booking). Photos and documents live
    // inside the VISITS permission tree; they keep their own feature gating via
    // Permission.effectiveFeatureKey.
    VISITS("Wizyty i kalendarz", FeatureKey.VISITS),
    // Covers vehicles too: a vehicle is customer data, not a standalone permission area.
    CUSTOMERS("Klienci i pojazdy", FeatureKey.CUSTOMERS),
    FINANCE("Finanse", FeatureKey.FINANCE),
    // Employee management ships with the base product — no paid module gates it.
    EMPLOYEES("Pracownicy", null),
    COMMUNICATION("Komunikacja", FeatureKey.SMS_EMAIL),
    // Social media and competition monitoring (Instagram, Google Reviews) —
    // distinct from COMMUNICATION, which is direct customer messaging.
    MARKETING("Marketing", FeatureKey.CAMPAIGNS),
    STATISTICS("Statystyki", FeatureKey.STATISTICS),
    LEADS("Leady", null),
    TASKS("Zadania", null),
    // Service catalog (price list) — no feature gate; access is also implied by Finance/Statistics.
    SERVICES("Usługi (cennik)", null)
}

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
    // Covers the calendar (an event IS a visit/booking) and — as the "Klienci i pojazdy"
    // section — the customer database with its vehicles: in this product customers exist
    // to serve visits, so there is no standalone customers permission area. Photos,
    // documents and customer permissions live inside the VISITS permission tree; they
    // keep their own feature gating via Permission.effectiveFeatureKey (GALLERY,
    // DOCUMENTS, CUSTOMERS).
    VISITS("Wizyty i kalendarz", FeatureKey.VISITS),
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
    // Company-wide activity history. Deliberately its own area rather than a child of an
    // existing module: the feed cuts across all of them, and it exposes payroll and
    // security events that no single module permission implies.
    AUDIT("Historia aktywności", null)
}

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
    CALENDAR("Kalendarz", FeatureKey.CALENDAR),
    // Photos and documents live inside the VISITS permission tree (sections of the visit
    // details node); they keep their own feature gating via Permission.effectiveFeatureKey.
    VISITS("Wizyty", FeatureKey.VISITS),
    CUSTOMERS("Klienci", FeatureKey.CUSTOMERS),
    VEHICLES("Pojazdy", FeatureKey.VEHICLES),
    FINANCE("Finanse", FeatureKey.FINANCE),
    EMPLOYEES("Pracownicy", FeatureKey.EMPLOYEES),
    COMMUNICATION("Komunikacja", FeatureKey.SMS_EMAIL),
    STATISTICS("Statystyki", null),
    LEADS("Leady", null),
    TASKS("Zadania", null)
}

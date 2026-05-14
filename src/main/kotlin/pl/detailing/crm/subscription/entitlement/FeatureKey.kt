package pl.detailing.crm.subscription.entitlement

/**
 * Canonical set of feature identifiers used throughout the entitlement system.
 *
 * BASIC plan: CALENDAR, VISITS, CUSTOMERS, VEHICLES, DOCUMENTS, GALLERY
 * EVERYTHING plan: all features
 * Individual add-ons unlock: FINANCE, EMPLOYEES, SMS_EMAIL
 */
enum class FeatureKey(val displayName: String) {

    // ── Pakiet Podstawowy ────────────────────────────────────────────────────
    CALENDAR("Kalendarz"),
    VISITS("Wizyty"),
    CUSTOMERS("Klienci"),
    VEHICLES("Pojazdy"),
    DOCUMENTS("Dokumenty"),
    GALLERY("Galeria"),

    // ── Add-ony / Pakiet Wszystko ────────────────────────────────────────────
    FINANCE("Moduł Finansów"),
    EMPLOYEES("Moduł Pracowników"),
    SMS_EMAIL("SMS i E-maile")
}

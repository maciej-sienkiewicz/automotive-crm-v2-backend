package pl.detailing.crm.subscription.entitlement

/**
 * Canonical set of feature identifiers used throughout the entitlement system.
 *
 * BASIC plan: CALENDAR, VISITS, CUSTOMERS, VEHICLES, DOCUMENTS, GALLERY
 * FULL plan: all features
 * Each purchasable add-on module unlocks exactly one of the remaining features.
 */
enum class FeatureKey(val displayName: String) {

    // ── Pakiet BASIC ─────────────────────────────────────────────────────────
    CALENDAR("Kalendarz"),
    VISITS("Wizyty"),
    CUSTOMERS("Klienci"),
    VEHICLES("Pojazdy"),
    DOCUMENTS("Dokumenty"),
    GALLERY("Galeria"),

    // ── Moduły dodatkowe / Pakiet FULL ───────────────────────────────────────
    AI_LEADS("Asystent AI dla leadów"),
    INSTAGRAM_MONITORING("Monitoring konkurencji na Instagramie"),
    SMS_EMAIL("Automatyzacja kontaktu (SMS i E-mail)"),
    CAMPAIGNS("Kampanie marketingowe SMS i E-mail"),
    E_SIGNATURES("Podpisy elektroniczne"),
    FINANCE("Kontrola nad finansami"),
    STATISTICS("Statystyki")
}

package pl.detailing.crm.role.domain

import pl.detailing.crm.subscription.entitlement.FeatureKey

// Section labels grouping siblings under a header in the role editor (presentational only).
private const val SECTION_SERVICES = "Usługi"
private const val SECTION_MEDIA = "Multimedia / Zdjęcia"
private const val SECTION_CUSTOMERS = "Klienci i pojazdy"

/**
 * Hardcoded permission catalog organized as a **tree** (hierarchy). Administrators cannot
 * add or remove entries — they only toggle which permissions a custom role has enabled.
 *
 * ## Hierarchy model
 * Every permission may declare a [parent]. A permission is only meaningful when its parent
 * is also granted — e.g. *deleting a visit* requires *creating visits*. The only dependency
 * relation is the parent chain, so the whole catalog renders as a tree in the role editor.
 * Because a parent must be declared **before** its children (Kotlin forbids forward
 * references between enum constants), the hierarchy is acyclic by construction.
 *
 * Implications that cannot be edges of the tree (a node has exactly one parent, but e.g.
 * creating a visit also requires editing prices) are part of the catalog too:
 * [PermissionHierarchy.impliesOf]. They are expanded together with the ancestor chain by
 * [PermissionHierarchy.close], so a stored role is always a consistent, self-contained set —
 * there is no invisible runtime expansion.
 *
 * Invariants (enforced by tests):
 * - a child always belongs to the same [PermissionModule] as its parent,
 * - every module has at least one root permission.
 *
 * ## Permission chain (v4 restructure)
 * The main dependency chain inside the VISITS module encodes the booking desk-flow:
 *
 *   [VISITS_VIEW] → [CUSTOMERS_VIEW] → [VISITS_SERVICE_PRICES_VIEW]
 *       → [VISITS_CREATE]
 *
 * Rationale: you cannot create a visit without first seeing client personal data and the
 * service price list; you cannot manage client data without viewing the price list context.
 * All destructive actions ([VISITS_DELETE], [CUSTOMERS_DELETE], [VISITS_MEDIA_DELETE]) are
 * children of [VISITS_CREATE] — you cannot delete what you cannot create.
 *
 * Every non-VISITS module root implies [VISITS_CREATE] (see [PermissionHierarchy]): a role
 * cannot be granted Finance, Employees, Communication, etc. without first having the
 * booking desk capability. [VISITS_VIEW] alone (without implying [CUSTOMERS_VIEW]) remains
 * the minimal "detailer" scope — a shop-floor worker sees schedules with personal data
 * masked at the serialization boundary (`@Pii`) and can change visit status.
 *
 * ## Business rules of the catalog
 * A permission exists only when there is a realistic role in a detailing studio that needs
 * it *without* the neighbouring ones:
 * - **[CUSTOMERS_VIEW] is the personal-data permission** — shop-floor views (visits,
 *   calendar) work without it with `@Pii` fields masked; person-centric views hard-require
 *   it and return 403 instead of masking.
 * - **Customers and vehicles are a section of the visits area, not a module** — their
 *   permissions live in the "Wizyty i kalendarz" group (keeping their own CUSTOMERS gate).
 * - **The calendar is not a separate permission area** — a calendar event IS a visit or a
 *   booking (viewing = [VISITS_VIEW], booking = [VISITS_CREATE]).
 * - **Vehicles are not a standalone permission area** — reading rides on visit and customer
 *   views, writing on [VISITS_CREATE], deleting on [CUSTOMERS_DELETE].
 * - **Create and edit are one capability** — [VISITS_CREATE] implies
 *   Work documentation (comments, notes, photo view/upload) is part of [VISITS_VIEW].
 * - Deliberately separate: statuses (detailer policies differ per studio), payroll, cash
 *   register, financial reports.
 *
 * ## Sections
 * [section] is a purely presentational label grouping siblings under a header in the role
 * editor. It carries no authorization semantics.
 *
 * ## Feature gating
 * A permission is feature-gated by [effectiveFeatureKey]: its module's feature key unless
 * the permission overrides it (photos and documents live in the VISITS tree but stay gated
 * by the GALLERY / DOCUMENTS subscription features).
 *
 * ## Personal-data visibility
 * Handled centrally at the serialization boundary
 * ([pl.detailing.crm.shared.pii.PiiMaskingModule]), driven by [CUSTOMERS_VIEW].
 */
enum class Permission(
    val module: PermissionModule,
    val displayName: String,
    val parent: Permission? = null,
    val section: String? = null,
    val description: String? = null,
    private val featureKeyOverride: FeatureKey? = null
) {
    // ── Wizyty i kalendarz ───────────────────────────────────────────────────
    // Root: minimal detailer scope — calendar view with personal data masked.
    VISITS_VIEW(
        PermissionModule.VISITS, "Podgląd wizyt i kalendarza",
        description = "Lista i szczegóły wizyt, kalendarz, komentarze i notatki, " +
            "podgląd i dodawanie zdjęć. Dane osobowe klienta widoczne tylko z uprawnieniem " +
            "„Podgląd danych osobowych”."
    ),
    // Sekcja: Klienci i pojazdy — personal-data gate; child of VISITS_VIEW because every
    // booking role already implies VISITS_VIEW via the chain below, and all other modules
    // that need customer data imply VISITS_CREATE (which contains VISITS_VIEW in its chain).
    CUSTOMERS_VIEW(
        PermissionModule.VISITS, "Podgląd danych osobowych",
        parent = VISITS_VIEW, section = SECTION_CUSTOMERS,
        featureKeyOverride = FeatureKey.CUSTOMERS,
        description = "Pełne dane osobowe, pojazdy i historia komunikacji klienta. " +
            "Bez tego uprawnienia dane osobowe w widokach wizyt i kalendarza są zamaskowane."
    ),

    // Sekcja: Usługi — price list viewing is required before seeing/managing customers
    // in the booking context, forming the natural desk-flow chain.
    VISITS_SERVICE_PRICES_VIEW(
        PermissionModule.VISITS, "Podgląd cen usług w wizycie",
        parent = CUSTOMERS_VIEW, section = SECTION_SERVICES
    ),
    // Booking capability — top of the desk-flow chain (VISITS_VIEW → CUSTOMERS_VIEW →
    // VISITS_SERVICE_PRICES_VIEW → VISITS_CREATE). All destructive actions are children:
    // you cannot delete what you cannot create.
    VISITS_CREATE(
        PermissionModule.VISITS, "Tworzenie i edycja wizyt oraz rezerwacji",
        parent = VISITS_SERVICE_PRICES_VIEW,
        description = "Umawianie i edycja wizyt wraz z wpisywaniem danych pojazdu " +
            "i klienta — pełny przepływ recepcji."
    ),
    VISITS_DELETE(
        PermissionModule.VISITS, "Usuwanie wizyt i dokumentów",
        parent = VISITS_CREATE,
        description = "Usuwanie wizyt oraz dokumentów i protokołów — akcje destrukcyjne " +
            "wydzielone z tworzenia i edycji."
    ),

    // Sekcja: Multimedia — gated by the GALLERY subscription feature. Viewing and adding
    // photos ride on VISITS_VIEW; deleting stays separate (photos are evidence in disputes).
    VISITS_MEDIA_DELETE(
        PermissionModule.VISITS, "Usuwanie zdjęć",
        parent = VISITS_CREATE, section = SECTION_MEDIA,
        featureKeyOverride = FeatureKey.GALLERY
    ),

    CUSTOMERS_DELETE(
        PermissionModule.VISITS, "Usuwanie klientów i pojazdów",
        parent = VISITS_CREATE, section = SECTION_CUSTOMERS,
        featureKeyOverride = FeatureKey.CUSTOMERS
    ),

    // Bulk contractor orders (B2B desk-flow): a child of VISITS_CREATE because closing
    // a contractor month books/settles visits en masse — you cannot run batch orders
    // without the booking capability.
    BATCH_ORDERS(
        PermissionModule.VISITS, "Zlecenia zbiorcze",
        parent = VISITS_CREATE,
        description = "Kontrahenci, wpisy zleceń zbiorczych, raporty i zamykanie miesiąca."
    ),

    // ── Finanse ──────────────────────────────────────────────────────────────
    FINANCE_INVOICES(
        PermissionModule.FINANCE, "Faktury i dokumenty przychodowe",
        description = "Podgląd i wystawianie — kto obsługuje faktury, robi jedno i drugie."
    ),
    FINANCE_MANAGE_CASH_REGISTER(
        PermissionModule.FINANCE, "Zarządzanie kasą fiskalną",
        parent = FINANCE_INVOICES
    ),
    FINANCE_VIEW_REPORTS(PermissionModule.FINANCE, "Podgląd raportów finansowych"),

    // ── Pracownicy ───────────────────────────────────────────────────────────
    EMPLOYEES_MANAGE(
        PermissionModule.EMPLOYEES, "Zarządzanie pracownikami i ich kontami",
        description = "Dane kadrowe i konta logowania. Lista współpracowników (imiona) " +
            "nie wymaga uprawnienia."
    ),
    EMPLOYEES_PAYROLL(
        PermissionModule.EMPLOYEES, "Płace (podgląd i zarządzanie)",
        description = "Pensje to najbardziej wrażliwe dane w firmie — osobno od kadr."
    ),

    // ── Komunikacja ──────────────────────────────────────────────────────────
    // Viewing the history rides on CUSTOMERS_VIEW (it is part of the customer card);
    // sending (SMS + e-mail) is the action. SMS budget is a billing concern, not an
    // access-control concern.
    COMMUNICATION_SEND(
        PermissionModule.COMMUNICATION, "Wysyłanie wiadomości do klientów (SMS i e-mail)",
        description = "Ręczne wysyłanie wiadomości. Automatyzacje skonfigurowane przez firmę " +
            "(np. link do karty wizyty po potwierdzeniu) działają na poziomie firmy — " +
            "wysyłają się niezależnie od uprawnień pracownika, który wywołał zdarzenie."
    ),

    // ── Marketing ────────────────────────────────────────────────────────────
    // One capability: whoever runs the studio's social media does all of it
    // (competition monitoring, post generation, reviews).
    MARKETING_MANAGE(
        PermissionModule.MARKETING, "Marketing i social media",
        description = "Monitoring konkurencji na Instagramie, generowanie postów " +
            "i opinie Google."
    ),

    // ── Statystyki ───────────────────────────────────────────────────────────
    STATISTICS_VIEW(PermissionModule.STATISTICS, "Podgląd statystyk"),

    // ── Leady ────────────────────────────────────────────────────────────────
    // A lead is a work queue — whoever sees it, works it.
    LEADS_MANAGE(PermissionModule.LEADS, "Praca z leadami"),

    // ── Zadania ──────────────────────────────────────────────────────────────
    TASKS_VIEW(PermissionModule.TASKS, "Podgląd i realizacja zadań"),
    TASKS_MANAGE(
        PermissionModule.TASKS, "Tworzenie i przypisywanie zadań",
        parent = TASKS_VIEW
    ),

    // ── Historia aktywności ──────────────────────────────────────────────────
    // The activity feed spans every module and surfaces payroll, permission and security
    // events, so it cannot ride on any module's own view permission. Owners bypass the
    // check entirely (see RequiresPermission), which is the intended default: this is the
    // owner's oversight tool, and it is granted to an employee only deliberately.
    AUDIT_VIEW(
        PermissionModule.AUDIT, "Podgląd historii aktywności firmy",
        description = "Tablica wszystkich zdarzeń w firmie — kto co zrobił, kiedy i na jaką " +
            "kwotę. Obejmuje zdarzenia kadrowo-płacowe i bezpieczeństwa."
    );

    /** Feature that must be enabled in the studio's entitlements for this permission. */
    val effectiveFeatureKey: FeatureKey?
        get() = featureKeyOverride ?: module.featureKey

    companion object {
        private val byName: Map<String, Permission> = entries.associateBy { it.name }

        /**
         * Codes retired by catalog restructures (flat list era + pre-consolidation tree).
         * Rows persisted with these codes (role_permissions.permission) are transparently
         * mapped on read — no SQL migration is required. Codes that vanished entirely
         * (EMPLOYEES_VIEW) map to null and are dropped.
         */
        private val legacyAliases: Map<String, Permission> = mapOf(
            // Visits — pre-consolidation tree nodes
            "VISITS_VIEW_DETAILS" to VISITS_VIEW,
            "VISITS_EDIT" to VISITS_CREATE,
            "VISITS_SERVICES_VIEW" to VISITS_VIEW,
            "VISITS_SERVICES_MANAGE" to VISITS_CREATE,
            "VISITS_COMMENTS_VIEW" to VISITS_VIEW,
            "VISITS_COMMENTS_ADD" to VISITS_VIEW,
            "VISITS_NOTES_ADD" to VISITS_VIEW,
            "VISITS_MEDIA_VIEW" to VISITS_VIEW,
            "VISITS_MEDIA_UPLOAD" to VISITS_VIEW,
            "VISITS_DOCUMENTS_VIEW" to VISITS_CREATE,
            "VISITS_DOCUMENTS_CREATE" to VISITS_CREATE,
            "VISITS_DOCUMENTS_SIGN" to VISITS_CREATE,
            // Visits — flat list era
            "VISITS_VIEW_PRICES" to VISITS_SERVICE_PRICES_VIEW,
            "VISITS_VIEW_COMMENTS" to VISITS_VIEW,
            "VISITS_ADD_COMMENT" to VISITS_VIEW,
            "DOCUMENTS_VIEW" to VISITS_CREATE,
            "DOCUMENTS_CREATE" to VISITS_CREATE,
            "DOCUMENTS_SIGN" to VISITS_CREATE,
            "DOCUMENTS_MANAGE" to VISITS_CREATE,
            "GALLERY_VIEW" to VISITS_VIEW,
            "GALLERY_UPLOAD" to VISITS_VIEW,
            "GALLERY_DELETE" to VISITS_MEDIA_DELETE,
            // Calendar — merged into visits (a calendar event IS a visit/booking)
            "CALENDAR_VIEW" to VISITS_VIEW,
            "CALENDAR_MANAGE" to VISITS_CREATE,
            // Vehicles — merged into visits (read/write) and customers (delete)
            "VEHICLES_VIEW" to VISITS_VIEW,
            "VEHICLES_CREATE" to VISITS_CREATE,
            "VEHICLES_EDIT" to VISITS_CREATE,
            "VEHICLES_DELETE" to CUSTOMERS_DELETE,
            // Customers
            "CUSTOMERS_VIEW_PERSONAL_DATA" to CUSTOMERS_VIEW,
            "CUSTOMERS_MANAGE" to VISITS_CREATE,
            "CUSTOMERS_CREATE" to VISITS_CREATE,
            "CUSTOMERS_EDIT" to VISITS_CREATE,
            // Communication
            "COMMUNICATION_VIEW_LOGS" to CUSTOMERS_VIEW,
            "COMMUNICATION_SEND_SMS" to COMMUNICATION_SEND,
            "COMMUNICATION_SEND_EMAIL" to COMMUNICATION_SEND,
            // Finance
            "FINANCE_VIEW_INVOICES" to FINANCE_INVOICES,
            "FINANCE_CREATE_INVOICE" to FINANCE_INVOICES,
            // Employees ("EMPLOYEES_VIEW" has no successor on purpose)
            "EMPLOYEES_MANAGE_ACCOUNTS" to EMPLOYEES_MANAGE,
            "EMPLOYEES_VIEW_PAYROLL" to EMPLOYEES_PAYROLL,
            "EMPLOYEES_MANAGE_PAYROLL" to EMPLOYEES_PAYROLL,
            // Tasks / Leads
            "TASKS_ASSIGN" to TASKS_MANAGE,
            "LEADS_VIEW" to LEADS_MANAGE,
            // Permissions removed from the catalog (v5 cleanup)
            "VISITS_CHANGE_STATUS" to VISITS_VIEW,
            "VISITS_SERVICE_PRICES_EDIT" to VISITS_SERVICE_PRICES_VIEW,
            "VISITS_DOCUMENTS_MANAGE" to VISITS_CREATE
        )

        /** Strict lookup for codes arriving from the API. Legacy aliases are not accepted. */
        fun fromApiCode(code: String): Permission? = byName[code]

        /** Tolerant lookup for codes read from the database; understands legacy aliases. */
        fun fromStoredCode(code: String): Permission? = byName[code] ?: legacyAliases[code]
    }
}

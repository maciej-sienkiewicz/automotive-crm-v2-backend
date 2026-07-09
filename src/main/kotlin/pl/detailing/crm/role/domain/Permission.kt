package pl.detailing.crm.role.domain

import pl.detailing.crm.subscription.entitlement.FeatureKey

// Section labels grouping siblings under a header in the role editor (presentational only).
private const val SECTION_SERVICES = "Usługi"
private const val SECTION_MEDIA = "Multimedia / Zdjęcia"
private const val SECTION_DOCUMENTS = "Dokumenty"

/**
 * Hardcoded permission catalog organized as a **tree** (hierarchy). Administrators cannot
 * add or remove entries — they only toggle which permissions a custom role has enabled.
 *
 * ## Hierarchy model
 * Every permission may declare a [parent]. A permission is only meaningful when its parent
 * is also granted — e.g. *deleting a visit* requires *viewing visits*. The only dependency
 * relation is the parent chain, so the whole catalog renders as a tree in the role editor.
 * Because a parent must be declared **before** its children (Kotlin forbids forward
 * references between enum constants), the hierarchy is acyclic by construction.
 *
 * Cross-module implications that cannot be edges of a single-module tree (e.g. creating a
 * visit means entering customer data) live in
 * [pl.detailing.crm.role.permission.PermissionCheckService] as runtime expansion.
 *
 * Invariants (enforced by tests):
 * - a child always belongs to the same [PermissionModule] as its parent,
 * - every module has at least one root permission.
 *
 * ## Business rules of the catalog (v2 consolidation)
 * A permission exists only when there is a realistic role in a detailing studio that needs
 * it *without* the neighbouring ones:
 * - **[CUSTOMERS_VIEW] is the personal-data permission** — no separate toggle. Shop-floor
 *   views (visits, calendar) work without it with `@Pii` fields masked at the
 *   serialization boundary; person-centric views (customer database, documents, invoices)
 *   hard-require it and return 403 instead of masking.
 * - **The calendar is not a separate permission area** — a calendar event IS a visit or a
 *   booking (viewing = [VISITS_VIEW], booking = [VISITS_CREATE]).
 * - **Vehicles are not a standalone permission area** — reading rides on the visit and
 *   customer views, writing on [VISITS_CREATE], deleting on [CUSTOMERS_DELETE].
 * - **Create and edit are one capability**; work documentation (comments, notes, photo
 *   view/upload) is part of [VISITS_VIEW]; a document's view/generate/sign is one
 *   desk-side flow ([VISITS_DOCUMENTS_MANAGE]).
 * - Deliberately separate: statuses and prices (detailer policies differ per studio),
 *   destructive actions ([VISITS_DELETE], [VISITS_MEDIA_DELETE], [CUSTOMERS_DELETE]),
 *   payroll, cash register, financial reports.
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
    VISITS_VIEW(
        PermissionModule.VISITS, "Podgląd wizyt i kalendarza",
        description = "Lista i szczegóły wizyt, kalendarz, komentarze i notatki, " +
            "podgląd i dodawanie zdjęć. Dane osobowe klienta widoczne tylko z uprawnieniem " +
            "„Podgląd klientów”."
    ),
    VISITS_CREATE(
        PermissionModule.VISITS, "Tworzenie i edycja wizyt oraz rezerwacji",
        parent = VISITS_VIEW,
        description = "Umawianie i edycja wizyt wraz z wpisywaniem danych pojazdu. " +
            "Obejmuje dodawanie i edycję klientów oraz dostęp do cennika usług."
    ),
    VISITS_CHANGE_STATUS(
        PermissionModule.VISITS, "Zmiana statusu wizyty",
        parent = VISITS_VIEW
    ),
    VISITS_DELETE(
        PermissionModule.VISITS, "Usuwanie wizyty",
        parent = VISITS_VIEW
    ),

    // Sekcja: Usługi
    VISITS_SERVICE_PRICES_VIEW(
        PermissionModule.VISITS, "Podgląd cen usług w wizycie",
        parent = VISITS_VIEW, section = SECTION_SERVICES
    ),
    VISITS_SERVICE_PRICES_EDIT(
        PermissionModule.VISITS, "Edycja cen usług (rabaty)",
        parent = VISITS_SERVICE_PRICES_VIEW, section = SECTION_SERVICES
    ),

    // Sekcja: Multimedia — gated by the GALLERY subscription feature. Viewing and adding
    // photos ride on VISITS_VIEW (a detailer documents damage before touching the car);
    // deleting stays separate because photos are evidence in damage disputes.
    VISITS_MEDIA_DELETE(
        PermissionModule.VISITS, "Usuwanie zdjęć",
        parent = VISITS_VIEW, section = SECTION_MEDIA,
        featureKeyOverride = FeatureKey.GALLERY
    ),

    // Sekcja: Dokumenty — gated by the DOCUMENTS subscription feature. One desk-side flow:
    // viewing, generating and getting protocols/contracts signed happen at the same desk.
    VISITS_DOCUMENTS_MANAGE(
        PermissionModule.VISITS, "Dokumenty i protokoły (podgląd, generowanie, podpis)",
        parent = VISITS_VIEW, section = SECTION_DOCUMENTS,
        featureKeyOverride = FeatureKey.DOCUMENTS
    ),

    // ── Klienci i pojazdy ────────────────────────────────────────────────────
    CUSTOMERS_VIEW(
        PermissionModule.CUSTOMERS, "Podgląd klientów",
        description = "Pełne dane osobowe, pojazdy i historia komunikacji klienta. " +
            "Bez tego uprawnienia dane osobowe w widokach wizyt i kalendarza są zamaskowane."
    ),
    CUSTOMERS_MANAGE(
        PermissionModule.CUSTOMERS, "Dodawanie i edycja klientów",
        parent = CUSTOMERS_VIEW
    ),
    CUSTOMERS_DELETE(
        PermissionModule.CUSTOMERS, "Usuwanie klientów i pojazdów",
        parent = CUSTOMERS_VIEW
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
        PermissionModule.COMMUNICATION, "Wysyłanie wiadomości do klientów (SMS i e-mail)"
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

    // ── Usługi (cennik) ───────────────────────────────────────────────────────
    // Access is also implicitly granted to any user holding a Finance, Statistics or
    // visit-creation permission (see PermissionCheckService.expandCrossModule).
    SERVICES_VIEW(PermissionModule.SERVICES, "Podgląd cennika usług"),
    SERVICES_MANAGE(PermissionModule.SERVICES, "Zarządzanie cennikiem usług", parent = SERVICES_VIEW);

    /** Feature that must be enabled in the studio's entitlements for this permission. */
    val effectiveFeatureKey: FeatureKey?
        get() = featureKeyOverride ?: module.featureKey

    companion object {
        private val byName: Map<String, Permission> = entries.associateBy { it.name }

        /**
         * Codes retired by catalog restructures (flat list era + pre-consolidation tree).
         * Rows persisted with these codes (role_permissions.permission) are transparently
         * mapped on read — no SQL migration is required (an optional cleanup script exists:
         * `migrate-role-permissions-v2.sql`). Codes that vanished entirely (EMPLOYEES_VIEW)
         * map to null and are dropped.
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
            "VISITS_DOCUMENTS_VIEW" to VISITS_DOCUMENTS_MANAGE,
            "VISITS_DOCUMENTS_CREATE" to VISITS_DOCUMENTS_MANAGE,
            "VISITS_DOCUMENTS_SIGN" to VISITS_DOCUMENTS_MANAGE,
            // Visits — flat list era
            "VISITS_VIEW_PRICES" to VISITS_SERVICE_PRICES_VIEW,
            "VISITS_VIEW_COMMENTS" to VISITS_VIEW,
            "VISITS_ADD_COMMENT" to VISITS_VIEW,
            "DOCUMENTS_VIEW" to VISITS_DOCUMENTS_MANAGE,
            "DOCUMENTS_CREATE" to VISITS_DOCUMENTS_MANAGE,
            "DOCUMENTS_SIGN" to VISITS_DOCUMENTS_MANAGE,
            "DOCUMENTS_MANAGE" to VISITS_DOCUMENTS_MANAGE,
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
            "CUSTOMERS_CREATE" to CUSTOMERS_MANAGE,
            "CUSTOMERS_EDIT" to CUSTOMERS_MANAGE,
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
            "LEADS_VIEW" to LEADS_MANAGE
        )

        /** Strict lookup for codes arriving from the API. Legacy aliases are not accepted. */
        fun fromApiCode(code: String): Permission? = byName[code]

        /** Tolerant lookup for codes read from the database; understands legacy aliases. */
        fun fromStoredCode(code: String): Permission? = byName[code] ?: legacyAliases[code]
    }
}

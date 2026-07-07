package pl.detailing.crm.role.domain

import pl.detailing.crm.subscription.entitlement.FeatureKey

// Section labels grouping siblings under a header in the role editor (presentational only).
private const val SECTION_SERVICES = "Usługi"
private const val SECTION_MEDIA = "Multimedia / Zdjęcia"
private const val SECTION_COMMUNICATION = "Komunikacja / Notatki"
private const val SECTION_DOCUMENTS = "Dokumenty"

/**
 * Hardcoded permission catalog organized as a **tree** (hierarchy). Administrators cannot
 * add or remove entries — they only toggle which permissions a custom role has enabled.
 *
 * ## Hierarchy model
 * Every permission may declare a [parent]. A permission is only meaningful when its parent
 * is also granted — e.g. *deleting a visit* requires *opening the visit details*, which in
 * turn requires *seeing the visit list*. This replaces the previous free-form dependency
 * graph ([PermissionDependencies], removed): the only dependency relation is the parent
 * chain, so the whole catalog renders as a tree in the role editor.
 *
 * Because a parent must be declared **before** its children (Kotlin forbids forward
 * references between enum constants), the hierarchy is acyclic by construction.
 *
 * Invariants (enforced by tests):
 * - a child always belongs to the same [PermissionModule] as its parent,
 * - every module has at least one root permission.
 *
 * ## Sections
 * [section] is a purely presentational label grouping siblings under a header in the role
 * editor (e.g. the children of [VISITS_VIEW_DETAILS] split into "Usługi", "Multimedia…").
 * It carries no authorization semantics.
 *
 * ## Feature gating
 * A permission is feature-gated by [effectiveFeatureKey]: its module's feature key unless
 * the permission overrides it (photos and documents live in the VISITS tree but stay gated
 * by the GALLERY / DOCUMENTS subscription features).
 *
 * ## Personal-data visibility
 * Unchanged: handled by masking at the serialization layer, driven by
 * [CUSTOMERS_VIEW_PERSONAL_DATA]. Plain `*_VIEW` permissions never imply it.
 */
enum class Permission(
    val module: PermissionModule,
    val displayName: String,
    val parent: Permission? = null,
    val section: String? = null,
    val description: String? = null,
    private val featureKeyOverride: FeatureKey? = null
) {
    // ── Kalendarz ────────────────────────────────────────────────────────────
    CALENDAR_VIEW(PermissionModule.CALENDAR, "Podgląd kalendarza"),
    CALENDAR_MANAGE(
        PermissionModule.CALENDAR, "Zarządzanie terminami w kalendarzu",
        parent = CALENDAR_VIEW
    ),

    // ── Wizyty ───────────────────────────────────────────────────────────────
    VISITS_VIEW(
        PermissionModule.VISITS, "Podgląd wizyt",
        description = "Widok listy wszystkich wizyt"
    ),
    VISITS_CHANGE_STATUS(
        PermissionModule.VISITS, "Zmiana statusu wizyty",
        parent = VISITS_VIEW
    ),
    VISITS_VIEW_DETAILS(
        PermissionModule.VISITS, "Podgląd wizyty",
        parent = VISITS_VIEW,
        description = "Wejście w szczegóły konkretnej wizyty — warunek dla akcji wewnątrz wizyty"
    ),
    VISITS_DELETE(
        PermissionModule.VISITS, "Usuwanie wizyty",
        parent = VISITS_VIEW_DETAILS
    ),
    VISITS_EDIT(
        PermissionModule.VISITS, "Edytowanie wizyty",
        parent = VISITS_VIEW_DETAILS,
        description = "Edycja ogólnych danych wizyty"
    ),

    // Sekcja: Usługi
    VISITS_SERVICES_VIEW(
        PermissionModule.VISITS, "Zakres usług",
        parent = VISITS_VIEW_DETAILS, section = SECTION_SERVICES,
        description = "Podgląd usług przypisanych do wizyty"
    ),
    VISITS_SERVICES_MANAGE(
        PermissionModule.VISITS, "Dodanie i usuwanie usług",
        parent = VISITS_SERVICES_VIEW, section = SECTION_SERVICES
    ),
    VISITS_SERVICE_PRICES_VIEW(
        PermissionModule.VISITS, "Cena usług",
        parent = VISITS_SERVICES_VIEW, section = SECTION_SERVICES,
        description = "Podgląd cen usług w wizycie"
    ),
    VISITS_SERVICE_PRICES_EDIT(
        PermissionModule.VISITS, "Edytuj cenę usług",
        parent = VISITS_SERVICE_PRICES_VIEW, section = SECTION_SERVICES
    ),

    // Sekcja: Multimedia / Zdjęcia — gated by the GALLERY subscription feature
    VISITS_MEDIA_VIEW(
        PermissionModule.VISITS, "Podgląd zdjęć",
        parent = VISITS_VIEW_DETAILS, section = SECTION_MEDIA,
        featureKeyOverride = FeatureKey.GALLERY
    ),
    VISITS_MEDIA_UPLOAD(
        PermissionModule.VISITS, "Dodanie zdjęcia",
        parent = VISITS_MEDIA_VIEW, section = SECTION_MEDIA,
        featureKeyOverride = FeatureKey.GALLERY
    ),
    VISITS_MEDIA_DELETE(
        PermissionModule.VISITS, "Usuwanie zdjęć",
        parent = VISITS_MEDIA_VIEW, section = SECTION_MEDIA,
        featureKeyOverride = FeatureKey.GALLERY
    ),

    // Sekcja: Komunikacja / Notatki
    VISITS_COMMENTS_VIEW(
        PermissionModule.VISITS, "Podgląd komentarzy",
        parent = VISITS_VIEW_DETAILS, section = SECTION_COMMUNICATION
    ),
    VISITS_COMMENTS_ADD(
        PermissionModule.VISITS, "Dodanie komentarza",
        parent = VISITS_COMMENTS_VIEW, section = SECTION_COMMUNICATION
    ),
    VISITS_NOTES_ADD(
        PermissionModule.VISITS, "Dodanie notatki",
        parent = VISITS_VIEW_DETAILS, section = SECTION_COMMUNICATION
    ),

    // Sekcja: Dokumenty — gated by the DOCUMENTS subscription feature
    VISITS_DOCUMENTS_VIEW(
        PermissionModule.VISITS, "Podgląd dokumentacji",
        parent = VISITS_VIEW_DETAILS, section = SECTION_DOCUMENTS,
        featureKeyOverride = FeatureKey.DOCUMENTS
    ),
    VISITS_DOCUMENTS_CREATE(
        PermissionModule.VISITS, "Tworzenie dokumentów",
        parent = VISITS_DOCUMENTS_VIEW, section = SECTION_DOCUMENTS,
        featureKeyOverride = FeatureKey.DOCUMENTS
    ),
    VISITS_DOCUMENTS_SIGN(
        PermissionModule.VISITS, "Podpisywanie dokumentów",
        parent = VISITS_DOCUMENTS_VIEW, section = SECTION_DOCUMENTS,
        featureKeyOverride = FeatureKey.DOCUMENTS
    ),

    // Dodanie wizyty — bottom of the VISITS tree. Granting it implicitly expands to the
    // entire VISITS module (see PermissionHierarchy.close), so checking it in the role
    // editor selects all visit permissions at once.
    VISITS_CREATE(
        PermissionModule.VISITS, "Dodanie wizyty",
        parent = VISITS_VIEW_DETAILS
    ),

    // ── Klienci ──────────────────────────────────────────────────────────────
    CUSTOMERS_VIEW(PermissionModule.CUSTOMERS, "Podgląd listy klientów"),
    CUSTOMERS_VIEW_PERSONAL_DATA(
        PermissionModule.CUSTOMERS, "Podgląd danych osobowych klienta",
        parent = CUSTOMERS_VIEW
    ),
    CUSTOMERS_CREATE(
        PermissionModule.CUSTOMERS, "Dodawanie klientów",
        parent = CUSTOMERS_VIEW_PERSONAL_DATA
    ),
    CUSTOMERS_EDIT(
        PermissionModule.CUSTOMERS, "Edycja klientów",
        parent = CUSTOMERS_VIEW_PERSONAL_DATA
    ),
    CUSTOMERS_DELETE(
        PermissionModule.CUSTOMERS, "Usuwanie klientów",
        parent = CUSTOMERS_VIEW
    ),

    // ── Pojazdy ──────────────────────────────────────────────────────────────
    VEHICLES_VIEW(PermissionModule.VEHICLES, "Podgląd pojazdów"),
    VEHICLES_CREATE(PermissionModule.VEHICLES, "Dodawanie pojazdów", parent = VEHICLES_VIEW),
    VEHICLES_EDIT(PermissionModule.VEHICLES, "Edycja pojazdów", parent = VEHICLES_VIEW),
    VEHICLES_DELETE(PermissionModule.VEHICLES, "Usuwanie pojazdów", parent = VEHICLES_VIEW),

    // ── Finanse ──────────────────────────────────────────────────────────────
    FINANCE_VIEW_INVOICES(PermissionModule.FINANCE, "Podgląd faktur"),
    FINANCE_CREATE_INVOICE(
        PermissionModule.FINANCE, "Wystawianie faktur",
        parent = FINANCE_VIEW_INVOICES
    ),
    FINANCE_MANAGE_CASH_REGISTER(
        PermissionModule.FINANCE, "Zarządzanie kasą fiskalną",
        parent = FINANCE_VIEW_INVOICES
    ),
    FINANCE_VIEW_REPORTS(PermissionModule.FINANCE, "Podgląd raportów finansowych"),

    // ── Pracownicy ───────────────────────────────────────────────────────────
    EMPLOYEES_VIEW(PermissionModule.EMPLOYEES, "Podgląd pracowników"),
    EMPLOYEES_MANAGE(
        PermissionModule.EMPLOYEES, "Zarządzanie pracownikami",
        parent = EMPLOYEES_VIEW
    ),
    EMPLOYEES_MANAGE_ACCOUNTS(
        PermissionModule.EMPLOYEES, "Zarządzanie kontami pracowników",
        parent = EMPLOYEES_MANAGE
    ),
    EMPLOYEES_VIEW_PAYROLL(
        PermissionModule.EMPLOYEES, "Podgląd listy płac",
        parent = EMPLOYEES_VIEW
    ),
    EMPLOYEES_MANAGE_PAYROLL(
        PermissionModule.EMPLOYEES, "Zarządzanie listą płac",
        parent = EMPLOYEES_VIEW_PAYROLL
    ),

    // ── Komunikacja ──────────────────────────────────────────────────────────
    COMMUNICATION_VIEW_LOGS(PermissionModule.COMMUNICATION, "Podgląd historii komunikacji"),
    COMMUNICATION_SEND_SMS(
        PermissionModule.COMMUNICATION, "Wysyłanie SMS",
        parent = COMMUNICATION_VIEW_LOGS
    ),
    COMMUNICATION_SEND_EMAIL(
        PermissionModule.COMMUNICATION, "Wysyłanie e-maili",
        parent = COMMUNICATION_VIEW_LOGS
    ),

    // ── Statystyki ───────────────────────────────────────────────────────────
    STATISTICS_VIEW(PermissionModule.STATISTICS, "Podgląd statystyk"),

    // ── Leady ────────────────────────────────────────────────────────────────
    LEADS_VIEW(PermissionModule.LEADS, "Podgląd leadów"),
    LEADS_MANAGE(PermissionModule.LEADS, "Zarządzanie leadami", parent = LEADS_VIEW),

    // ── Zadania ──────────────────────────────────────────────────────────────
    TASKS_VIEW(PermissionModule.TASKS, "Podgląd zadań"),
    TASKS_MANAGE(PermissionModule.TASKS, "Zarządzanie zadaniami", parent = TASKS_VIEW),
    TASKS_ASSIGN(PermissionModule.TASKS, "Przypisywanie zadań", parent = TASKS_VIEW),

    // ── Usługi (cennik) ───────────────────────────────────────────────────────
    // Access is also implicitly granted to any user holding a Finance or Statistics
    // permission (see PermissionCheckService.expandCrossModule).
    SERVICES_VIEW(PermissionModule.SERVICES, "Podgląd cennika usług"),
    SERVICES_MANAGE(PermissionModule.SERVICES, "Zarządzanie cennikiem usług", parent = SERVICES_VIEW);

    /** Feature that must be enabled in the studio's entitlements for this permission. */
    val effectiveFeatureKey: FeatureKey?
        get() = featureKeyOverride ?: module.featureKey

    companion object {
        private val byName: Map<String, Permission> = entries.associateBy { it.name }

        /**
         * Codes that existed before the catalog became a tree. Rows persisted with these
         * codes (role_permissions.permission) are transparently mapped on read — no SQL
         * migration is required. Codes that vanished entirely map to null and are dropped.
         */
        private val legacyAliases: Map<String, Permission> = mapOf(
            "VISITS_VIEW_PRICES" to VISITS_SERVICE_PRICES_VIEW,
            "VISITS_VIEW_COMMENTS" to VISITS_COMMENTS_VIEW,
            "VISITS_ADD_COMMENT" to VISITS_COMMENTS_ADD,
            "DOCUMENTS_VIEW" to VISITS_DOCUMENTS_VIEW,
            "DOCUMENTS_CREATE" to VISITS_DOCUMENTS_CREATE,
            "DOCUMENTS_SIGN" to VISITS_DOCUMENTS_SIGN,
            "GALLERY_VIEW" to VISITS_MEDIA_VIEW,
            "GALLERY_UPLOAD" to VISITS_MEDIA_UPLOAD,
            "GALLERY_DELETE" to VISITS_MEDIA_DELETE
        )

        /** Strict lookup for codes arriving from the API. Legacy aliases are not accepted. */
        fun fromApiCode(code: String): Permission? = byName[code]

        /** Tolerant lookup for codes read from the database; understands legacy aliases. */
        fun fromStoredCode(code: String): Permission? = byName[code] ?: legacyAliases[code]
    }
}

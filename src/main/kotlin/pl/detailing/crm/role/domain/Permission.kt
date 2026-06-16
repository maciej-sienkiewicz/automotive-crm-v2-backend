package pl.detailing.crm.role.domain

/**
 * Hardcoded permission catalog. Administrators cannot add or remove entries —
 * they only toggle which permissions a custom role has enabled.
 *
 * Every permission belongs to exactly one [PermissionModule]. The module determines
 * which subscription feature must be active for the permission to be enforced.
 */
enum class Permission(
    val module: PermissionModule,
    val displayName: String
) {
    // ── Calendar ─────────────────────────────────────────────────────────────
    CALENDAR_VIEW(PermissionModule.CALENDAR, "Podgląd kalendarza"),
    CALENDAR_MANAGE(PermissionModule.CALENDAR, "Zarządzanie terminami w kalendarzu"),

    // ── Visits ───────────────────────────────────────────────────────────────
    VISITS_VIEW(PermissionModule.VISITS, "Podgląd wizyt"),
    VISITS_CREATE(PermissionModule.VISITS, "Tworzenie wizyt"),
    VISITS_CHANGE_STATUS(PermissionModule.VISITS, "Zmiana statusu wizyty"),
    VISITS_VIEW_PRICES(PermissionModule.VISITS, "Podgląd cen usług w wizycie"),
    VISITS_ADD_COMMENT(PermissionModule.VISITS, "Dodawanie komentarzy do wizyty"),
    VISITS_VIEW_COMMENTS(PermissionModule.VISITS, "Podgląd komentarzy wizyty"),

    // ── Customers ────────────────────────────────────────────────────────────
    CUSTOMERS_VIEW(PermissionModule.CUSTOMERS, "Podgląd listy klientów"),
    CUSTOMERS_VIEW_PERSONAL_DATA(PermissionModule.CUSTOMERS, "Podgląd danych osobowych klienta"),
    CUSTOMERS_CREATE(PermissionModule.CUSTOMERS, "Dodawanie klientów"),
    CUSTOMERS_EDIT(PermissionModule.CUSTOMERS, "Edycja klientów"),
    CUSTOMERS_DELETE(PermissionModule.CUSTOMERS, "Usuwanie klientów"),

    // ── Vehicles ─────────────────────────────────────────────────────────────
    VEHICLES_VIEW(PermissionModule.VEHICLES, "Podgląd pojazdów"),
    VEHICLES_CREATE(PermissionModule.VEHICLES, "Dodawanie pojazdów"),
    VEHICLES_EDIT(PermissionModule.VEHICLES, "Edycja pojazdów"),
    VEHICLES_DELETE(PermissionModule.VEHICLES, "Usuwanie pojazdów"),

    // ── Documents ────────────────────────────────────────────────────────────
    DOCUMENTS_VIEW(PermissionModule.DOCUMENTS, "Podgląd dokumentów"),
    DOCUMENTS_CREATE(PermissionModule.DOCUMENTS, "Tworzenie dokumentów"),
    DOCUMENTS_SIGN(PermissionModule.DOCUMENTS, "Podpisywanie dokumentów"),

    // ── Gallery ──────────────────────────────────────────────────────────────
    GALLERY_VIEW(PermissionModule.GALLERY, "Podgląd galerii"),
    GALLERY_UPLOAD(PermissionModule.GALLERY, "Dodawanie zdjęć"),
    GALLERY_DELETE(PermissionModule.GALLERY, "Usuwanie zdjęć"),

    // ── Finance ──────────────────────────────────────────────────────────────
    FINANCE_VIEW_INVOICES(PermissionModule.FINANCE, "Podgląd faktur"),
    FINANCE_CREATE_INVOICE(PermissionModule.FINANCE, "Wystawianie faktur"),
    FINANCE_VIEW_REPORTS(PermissionModule.FINANCE, "Podgląd raportów finansowych"),
    FINANCE_MANAGE_CASH_REGISTER(PermissionModule.FINANCE, "Zarządzanie kasą fiskalną"),

    // ── Employees ────────────────────────────────────────────────────────────
    EMPLOYEES_VIEW(PermissionModule.EMPLOYEES, "Podgląd pracowników"),
    EMPLOYEES_MANAGE(PermissionModule.EMPLOYEES, "Zarządzanie pracownikami"),
    EMPLOYEES_VIEW_PAYROLL(PermissionModule.EMPLOYEES, "Podgląd listy płac"),
    EMPLOYEES_MANAGE_PAYROLL(PermissionModule.EMPLOYEES, "Zarządzanie listą płac"),
    EMPLOYEES_MANAGE_ACCOUNTS(PermissionModule.EMPLOYEES, "Zarządzanie kontami pracowników"),

    // ── Communication ────────────────────────────────────────────────────────
    COMMUNICATION_VIEW_LOGS(PermissionModule.COMMUNICATION, "Podgląd historii komunikacji"),
    COMMUNICATION_SEND_SMS(PermissionModule.COMMUNICATION, "Wysyłanie SMS"),
    COMMUNICATION_SEND_EMAIL(PermissionModule.COMMUNICATION, "Wysyłanie e-maili"),

    // ── Statistics ───────────────────────────────────────────────────────────
    STATISTICS_VIEW(PermissionModule.STATISTICS, "Podgląd statystyk"),

    // ── Leads ────────────────────────────────────────────────────────────────
    LEADS_VIEW(PermissionModule.LEADS, "Podgląd leadów"),
    LEADS_MANAGE(PermissionModule.LEADS, "Zarządzanie leadami"),

    // ── Tasks ────────────────────────────────────────────────────────────────
    TASKS_VIEW(PermissionModule.TASKS, "Podgląd zadań"),
    TASKS_MANAGE(PermissionModule.TASKS, "Zarządzanie zadaniami"),
    TASKS_ASSIGN(PermissionModule.TASKS, "Przypisywanie zadań")
}

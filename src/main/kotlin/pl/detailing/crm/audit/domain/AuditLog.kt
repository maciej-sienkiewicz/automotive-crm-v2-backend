package pl.detailing.crm.audit.domain

import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.shared.VisitId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Semantic icon keys. The backend never ships an icon asset or a CSS class — it ships a
 * stable key that the frontend maps to whatever icon set it uses. Adding a key here is a
 * frontend-visible contract change; reusing an existing one is free.
 */
enum class AuditIcon {
    CREATE,
    EDIT,
    DELETE,
    STATUS,
    PHOTO,
    DOCUMENT,
    COMMENT,
    NOTE,
    SERVICE,
    MONEY,
    CALENDAR,
    VEHICLE,
    CUSTOMER,
    USER,
    SECURITY,
    MESSAGE,
    CAMPAIGN,
    TIME,
    SIGNATURE,
    PHONE,
    TASK,
    VIEW,
    APPROVE,
    REJECT,
    ARCHIVE,
    RESTORE,
    SPLIT,
    MERGE,
    LEAVE,
    PAYROLL,
    LINK
}

/**
 * How much attention an event deserves in the owner's activity feed.
 *
 * The feed is dominated by high-volume, low-signal events (a photo upload, a comment).
 * Severity is what lets the UI default to "everything that matters" without the owner
 * having to hand-pick modules.
 */
enum class AuditSeverity(val label: String, val weight: Int) {
    /** Routine documentation work. Hidden behind "show everything". */
    LOW("Rutynowe", 0),

    /** The normal working day: bookings, visits, edits. */
    NORMAL("Standardowe", 1),

    /** Money, customer-facing messages, deletions. Worth a glance. */
    HIGH("Istotne", 2),

    /** Payroll, permissions, security. The owner should see these unprompted. */
    CRITICAL("Krytyczne", 3)
}

/**
 * Who performed the action. Modelling this explicitly is what makes customer-driven and
 * automated events representable — before this existed, every event had to borrow an
 * employee's [UserId], and scheduled jobs wrote a synthetic all-zeroes UUID.
 */
enum class AuditActorType(val label: String) {
    EMPLOYEE("Pracownik"),
    CUSTOMER("Klient"),
    SYSTEM("System"),
    INTEGRATION("Integracja")
}

/**
 * Where the action came from. Resolved automatically from the current HTTP request by
 * [pl.detailing.crm.audit.infrastructure.AuditRequestContext] — call sites never pass it by hand.
 */
enum class AuditChannel(val label: String) {
    WEB_PANEL("Panel"),
    MOBILE("Aplikacja mobilna"),
    TABLET("Tablet"),
    PUBLIC_LINK("Link dla klienta"),
    API("API"),
    SYSTEM("Automat")
}

/**
 * A business object an audit entry can point at. The frontend maps a resource to a route;
 * the backend deliberately does not emit URLs, so routing stays a frontend concern.
 */
enum class AuditResource {
    CUSTOMER,
    VEHICLE,
    VISIT,
    APPOINTMENT,
    LEAD,
    EMPLOYEE,
    USER,
    TASK,
    SERVICE,
    FINANCIAL_DOCUMENT,
    CAMPAIGN,
    MESSAGE,
    WORK_TIME,
    PROTOCOL,
    STUDIO,
    NONE
}

/**
 * Modules tracked by the audit system. Each module corresponds to a top-level business
 * domain and carries its own presentation so the API can render a filter list without a
 * translation table living in the controller.
 */
enum class AuditModule(
    val label: String,
    val icon: AuditIcon,
    /** Resource type that [AuditLog.entityId] refers to for events in this module. */
    val resource: AuditResource,
    /**
     * Accusative-case noun for the object this module's events act on ("wizytę",
     * "pojazd"). Appended after generic verbs so the feed reads "Utworzono usługę - X"
     * instead of the ambiguous "Utworzono - X". Null when there is no natural object
     * to name (the specific actions of such modules carry their own noun already).
     */
    val objectNoun: String? = null
) {
    CUSTOMER("Klienci", AuditIcon.CUSTOMER, AuditResource.CUSTOMER, "klienta"),
    VEHICLE("Pojazdy", AuditIcon.VEHICLE, AuditResource.VEHICLE, "pojazd"),
    VISIT("Wizyty", AuditIcon.CALENDAR, AuditResource.VISIT, "wizytę"),
    APPOINTMENT("Rezerwacje", AuditIcon.CALENDAR, AuditResource.APPOINTMENT, "rezerwację"),
    SERVICE("Usługi", AuditIcon.SERVICE, AuditResource.SERVICE, "usługę"),
    LEAD("Leady", AuditIcon.CUSTOMER, AuditResource.LEAD, "leada"),
    PROTOCOL("Protokoły", AuditIcon.DOCUMENT, AuditResource.PROTOCOL, "protokół"),
    CONSENT("Zgody", AuditIcon.SIGNATURE, AuditResource.CUSTOMER, "zgodę"),
    INBOUND_CALL("Połączenia przychodzące", AuditIcon.PHONE, AuditResource.LEAD, "połączenie"),
    APPOINTMENT_COLOR("Kolory rezerwacji", AuditIcon.CALENDAR, AuditResource.NONE, "kolor rezerwacji"),
    STUDIO("Studio", AuditIcon.STATUS, AuditResource.STUDIO, "dane studia"),
    USER("Użytkownicy", AuditIcon.USER, AuditResource.USER, "użytkownika"),
    FINANCE("Finanse", AuditIcon.MONEY, AuditResource.FINANCIAL_DOCUMENT, "dokument"),
    CASH_REGISTER("Kasa", AuditIcon.MONEY, AuditResource.NONE),
    EMPLOYEE("Pracownicy", AuditIcon.USER, AuditResource.EMPLOYEE, "pracownika"),
    TASK("Zadania", AuditIcon.TASK, AuditResource.TASK, "zadanie"),
    SECURITY("Bezpieczeństwo", AuditIcon.SECURITY, AuditResource.USER),
    DOOR_TO_DOOR("Door to door", AuditIcon.VEHICLE, AuditResource.VISIT, "obsługę Door to Door"),
    CAMPAIGN("Kampanie", AuditIcon.CAMPAIGN, AuditResource.CAMPAIGN, "kampanię"),
    COMMUNICATION("Komunikacja", AuditIcon.MESSAGE, AuditResource.MESSAGE, "wiadomość"),
    WORK_TIME("Czas pracy", AuditIcon.TIME, AuditResource.WORK_TIME, "wpis czasu pracy"),
    VISIT_CARD("Karta Wizyty", AuditIcon.LINK, AuditResource.VISIT, "Kartę Wizyty")
}

/**
 * Actions that can be performed on an entity.
 *
 * Every constant carries its own presentation, which is the point: a new action cannot be
 * added without also deciding how it reads in the feed, how important it is and which icon
 * it gets. Previously these lived in exhaustive `when` blocks in the controller, so the
 * frontend contract was one forgotten branch away from being wrong.
 *
 * - [label] — noun phrase for filter dropdowns ("Dodanie zdjęcia").
 * - [sentence] — impersonal past tense for the feed ("Dodano zdjęcie"). Impersonal is
 *   deliberate: Polish verbs inflect for gender and we do not store the actor's gender,
 *   so "dodał(a)" would be the only alternative.
 * - [entityMirror] — the entry is a copy of another entry, written only so a second
 *   object's own history shows the event. It always has a primary entry beside it in the
 *   same request, so the company-wide feed must not show both.
 */
enum class AuditAction(
    val label: String,
    val sentence: String,
    val icon: AuditIcon,
    val severity: AuditSeverity = AuditSeverity.NORMAL,
    /**
     * Kopia zdarzenia, dopisywana po stronie drugiego obiektu, żeby jego własna historia
     * je pokazała ("Dodano rezerwację" na pojeździe obok "Utworzono rezerwację").
     *
     * Odkąd [AuditContextResolver] dokleja kontekst do wpisu głównego, karta obiektu
     * widzi zdarzenie bez kopii — a firmowa Aktywność pokazywała przez nią dwa wiersze
     * na jedno kliknięcie. Nowe kopie nie powstają; te już zapisane zostają w dzienniku
     * (audyt jest rejestrem, nie brudnopisem) i są odsiewane przy odczycie feedu
     * ogólnego. W historii pojedynczego obiektu zostają — dla wpisów sprzed
     * [AuditContextResolver] są jedynym śladem zdarzenia na tym obiekcie.
     */
    val entityMirror: Boolean = false
) {
    // ── CRUD ────────────────────────────────────────────────────────────────
    CREATE("Utworzenie", "Utworzono", AuditIcon.CREATE),
    UPDATE("Aktualizacja", "Zaktualizowano", AuditIcon.EDIT),
    DELETE("Usunięcie", "Usunięto", AuditIcon.DELETE, AuditSeverity.HIGH),

    // ── Status transitions ──────────────────────────────────────────────────
    STATUS_CHANGE("Zmiana statusu", "Zmieniono status", AuditIcon.STATUS),

    // ── Sub-entity operations ───────────────────────────────────────────────
    PHOTO_ADDED("Dodanie zdjęcia", "Dodano zdjęcie", AuditIcon.PHOTO, AuditSeverity.LOW),
    PHOTO_DELETED("Usunięcie zdjęcia", "Usunięto zdjęcie", AuditIcon.PHOTO, AuditSeverity.HIGH),
    DOCUMENT_ADDED("Dodanie dokumentu", "Dodano dokument", AuditIcon.DOCUMENT),
    COMMENT_ADDED("Dodanie komentarza", "Dodano komentarz", AuditIcon.COMMENT, AuditSeverity.LOW),
    COMMENT_UPDATED("Edycja komentarza", "Zmieniono komentarz", AuditIcon.COMMENT, AuditSeverity.LOW),
    COMMENT_DELETED("Usunięcie komentarza", "Usunięto komentarz", AuditIcon.COMMENT),
    NOTE_ADDED("Dodanie notatki", "Dodano notatkę", AuditIcon.NOTE, AuditSeverity.LOW),
    NOTE_UPDATED("Edycja notatki", "Zmieniono notatkę", AuditIcon.NOTE, AuditSeverity.LOW),
    NOTE_DELETED("Usunięcie notatki", "Usunięto notatkę", AuditIcon.NOTE),

    // ── Customer-specific ───────────────────────────────────────────────────
    /**
     * Jeden wpis na cały import książki adresowej, nie jeden na klienta — patrz
     * [pl.detailing.crm.customer.importing.CustomerImportService].
     */
    CUSTOMERS_IMPORTED("Import klientów", "Zaimportowano klientów", AuditIcon.CUSTOMER, AuditSeverity.HIGH),

    // ── Visit-specific ──────────────────────────────────────────────────────
    SERVICE_ADDED("Dodanie usługi", "Dodano usługę", AuditIcon.SERVICE),
    SERVICE_UPDATED("Aktualizacja usługi", "Zaktualizowano usługę", AuditIcon.SERVICE),
    SERVICE_REMOVED("Usunięcie usługi", "Usunięto usługę", AuditIcon.SERVICE, AuditSeverity.HIGH),
    SERVICES_UPDATED("Aktualizacja listy usług", "Zmieniono zakres usług", AuditIcon.SERVICE),
    VISIT_CREATED("Rozpoczęcie wizyty", "Rozpoczęto wizytę", AuditIcon.CALENDAR, AuditSeverity.HIGH),
    VISIT_CONFIRMED("Potwierdzenie wizyty", "Potwierdzono wizytę", AuditIcon.APPROVE),
    VISIT_CANCELLED("Anulowanie wizyty", "Anulowano wizytę", AuditIcon.REJECT, AuditSeverity.HIGH),
    VISIT_COMPLETED("Zakończenie wizyty", "Zakończono wizytę", AuditIcon.APPROVE, AuditSeverity.HIGH),
    VISIT_REJECTED("Odrzucenie wizyty", "Odrzucono wizytę", AuditIcon.REJECT, AuditSeverity.HIGH),
    VISIT_MARKED_READY("Oznaczenie jako gotowe", "Oznaczono jako gotowe do odbioru", AuditIcon.APPROVE),
    VISIT_ARCHIVED("Archiwizacja wizyty", "Zarchiwizowano wizytę", AuditIcon.ARCHIVE),
    VISIT_DELETED("Usunięcie wizyty", "Usunięto wizytę", AuditIcon.DELETE, AuditSeverity.CRITICAL),

    // ── Appointment-specific ────────────────────────────────────────────────
    APPOINTMENT_CANCELLED("Anulowanie rezerwacji", "Anulowano rezerwację", AuditIcon.REJECT, AuditSeverity.HIGH),
    APPOINTMENT_RESTORED("Przywrócenie rezerwacji", "Przywrócono rezerwację", AuditIcon.RESTORE),
    APPOINTMENT_DELETED("Usunięcie rezerwacji", "Usunięto rezerwację", AuditIcon.DELETE, AuditSeverity.HIGH),
    APPOINTMENT_CONVERTED("Konwersja rezerwacji", "Zamieniono rezerwację na wizytę", AuditIcon.APPROVE),
    APPOINTMENT_ABANDONED("Porzucenie rezerwacji", "Porzucono rezerwację", AuditIcon.REJECT),

    // ── Protocol-specific ───────────────────────────────────────────────────
    PROTOCOL_GENERATED("Wygenerowanie protokołu", "Wygenerowano protokół", AuditIcon.DOCUMENT),
    PROTOCOL_SIGNED("Podpisanie protokołu", "Podpisano protokół", AuditIcon.SIGNATURE, AuditSeverity.HIGH),

    // ── Consent-specific ────────────────────────────────────────────────────
    CONSENT_GRANTED("Udzielenie zgody", "Udzielono zgody", AuditIcon.SIGNATURE),
    CONSENT_REVOKED("Cofnięcie zgody", "Cofnięto zgodę", AuditIcon.SIGNATURE, AuditSeverity.HIGH),

    // ── Lead-specific ───────────────────────────────────────────────────────
    LEAD_CONVERTED("Konwersja leada", "Przekształcono leada w rezerwację", AuditIcon.APPROVE, AuditSeverity.HIGH),
    LEAD_ABANDONED("Porzucenie leada", "Porzucono leada", AuditIcon.REJECT),
    LEAD_CONFIRMED("Potwierdzenie leada", "Potwierdzono leada", AuditIcon.APPROVE),
    LEAD_COMPLETED("Zakończenie leada", "Zakończono leada", AuditIcon.APPROVE),
    LEAD_LOST("Utrata leada", "Utracono leada", AuditIcon.REJECT, AuditSeverity.HIGH),
    LEAD_NO_SHOW("Brak kontaktu", "Odnotowano brak kontaktu", AuditIcon.PHONE),
    LEAD_APPOINTMENT_CREATED("Utworzenie rezerwacji z leada", "Utworzono rezerwację z leada", AuditIcon.CALENDAR),
    LEAD_USER_ASSIGNED("Przypisanie pracownika", "Przypisano pracownika do leada", AuditIcon.USER),
    LEAD_CUSTOMER_ASSIGNED("Przypisanie klienta", "Przypisano klienta do leada", AuditIcon.CUSTOMER),
    LEAD_LOST_REASON_UPDATED("Aktualizacja powodu utraty", "Zaktualizowano powód utraty", AuditIcon.EDIT),
    LEAD_QUOTE_UPDATED("Aktualizacja wyceny", "Zaktualizowano wycenę", AuditIcon.MONEY, AuditSeverity.HIGH),
    LEAD_COMMENT_ADDED("Dodanie komentarza do leada", "Dodano komentarz do leada", AuditIcon.COMMENT, AuditSeverity.LOW),
    LEAD_COMMENT_UPDATED("Edycja komentarza do leada", "Zmieniono komentarz do leada", AuditIcon.COMMENT, AuditSeverity.LOW),
    LEAD_COMMENT_DELETED("Usunięcie komentarza do leada", "Usunięto komentarz do leada", AuditIcon.COMMENT),
    LEAD_ESTIMATION_COMPLETED("Zakończenie wyceny AI", "Zakończono wycenę AI", AuditIcon.MONEY),
    LEAD_SPLIT("Podział leada", "Podzielono leada", AuditIcon.SPLIT),
    LEAD_MERGED("Scalenie leada", "Scalono leady", AuditIcon.MERGE),
    LEAD_CUSTOMER_ANSWERED("Odpowiedź klienta (e-mail)", "Klient odpowiedział na e-mail", AuditIcon.MESSAGE),
    LEAD_ACKNOWLEDGED("Potwierdzenie aktywności", "Potwierdzono aktywność na leadzie", AuditIcon.APPROVE, AuditSeverity.LOW),

    // ── Inbound-specific ────────────────────────────────────────────────────
    CALL_ACCEPTED("Przyjęcie połączenia", "Przyjęto połączenie", AuditIcon.PHONE),
    CALL_REJECTED("Odrzucenie połączenia", "Odrzucono połączenie", AuditIcon.PHONE),

    // ── Vehicle-specific ────────────────────────────────────────────────────
    OWNER_ADDED("Dodanie właściciela", "Dodano właściciela pojazdu", AuditIcon.CUSTOMER),
    OWNER_REMOVED("Usunięcie właściciela", "Usunięto właściciela pojazdu", AuditIcon.CUSTOMER, AuditSeverity.HIGH),
    APPOINTMENT_ADDED("Dodanie rezerwacji", "Dodano rezerwację", AuditIcon.CALENDAR, entityMirror = true),
    VISIT_ADDED("Przyjęcie pojazdu", "Przyjęto pojazd", AuditIcon.VEHICLE, AuditSeverity.HIGH, entityMirror = true),

    // ── Company data ────────────────────────────────────────────────────────
    COMPANY_UPDATED("Aktualizacja danych firmy", "Zaktualizowano dane firmy", AuditIcon.EDIT),
    COMPANY_DELETED("Usunięcie danych firmy", "Usunięto dane firmy", AuditIcon.DELETE, AuditSeverity.HIGH),

    // ── Finance ─────────────────────────────────────────────────────────────
    DOCUMENT_ISSUED("Wystawienie dokumentu", "Wystawiono dokument", AuditIcon.MONEY, AuditSeverity.HIGH),
    DOCUMENT_STATUS_CHANGED("Zmiana statusu dokumentu", "Zmieniono status dokumentu", AuditIcon.MONEY, AuditSeverity.HIGH),
    DOCUMENT_NUMBER_UPDATED("Aktualizacja numeru dokumentu", "Zmieniono numer dokumentu", AuditIcon.EDIT, AuditSeverity.HIGH),
    DOCUMENT_DELETED("Usunięcie dokumentu", "Usunięto dokument", AuditIcon.DELETE, AuditSeverity.CRITICAL),
    DOCUMENT_RESTORED("Przywrócenie dokumentu", "Przywrócono dokument", AuditIcon.RESTORE, AuditSeverity.HIGH),
    CASH_ADJUSTED("Korekta kasy", "Skorygowano stan kasy", AuditIcon.MONEY, AuditSeverity.CRITICAL),

    // ── Employee-specific ───────────────────────────────────────────────────
    EMPLOYEE_TERMINATED("Zakończenie zatrudnienia", "Zakończono zatrudnienie", AuditIcon.USER, AuditSeverity.CRITICAL),
    CONTRACT_CREATED("Utworzenie umowy", "Utworzono umowę", AuditIcon.DOCUMENT, AuditSeverity.HIGH),
    CONTRACT_ENDED("Zakończenie umowy", "Zakończono umowę", AuditIcon.DOCUMENT, AuditSeverity.HIGH),
    COMPENSATION_SET("Ustawienie wynagrodzenia", "Ustawiono wynagrodzenie", AuditIcon.PAYROLL, AuditSeverity.CRITICAL),
    WORK_TIME_LOGGED("Zapis czasu pracy", "Zapisano czas pracy", AuditIcon.TIME),
    WORK_TIME_APPROVED("Zatwierdzenie czasu pracy", "Zatwierdzono czas pracy", AuditIcon.APPROVE),
    WORK_TIME_REJECTED("Odrzucenie czasu pracy", "Odrzucono czas pracy", AuditIcon.REJECT, AuditSeverity.HIGH),
    WORK_TIME_PERIOD_SAVED("Zapis okresu rozliczeniowego", "Zapisano okres rozliczeniowy", AuditIcon.TIME),
    WORK_TIME_ENTRY_DELETED("Usunięcie wpisu czasu pracy", "Usunięto wpis czasu pracy", AuditIcon.DELETE, AuditSeverity.HIGH),
    LEAVE_REQUESTED("Wniosek urlopowy", "Złożono wniosek urlopowy", AuditIcon.LEAVE),
    LEAVE_APPROVED("Zatwierdzenie urlopu", "Zatwierdzono urlop", AuditIcon.LEAVE),
    LEAVE_REJECTED("Odrzucenie urlopu", "Odrzucono urlop", AuditIcon.LEAVE, AuditSeverity.HIGH),
    LEAVE_CANCELLED("Anulowanie urlopu", "Anulowano urlop", AuditIcon.LEAVE),
    PAYROLL_GENERATED("Generowanie listy płac", "Wygenerowano listę płac", AuditIcon.PAYROLL, AuditSeverity.CRITICAL),
    PAYROLL_CONFIRMED("Potwierdzenie listy płac", "Potwierdzono listę płac", AuditIcon.PAYROLL, AuditSeverity.CRITICAL),
    PAYROLL_PAID("Wypłata wynagrodzenia", "Wypłacono wynagrodzenie", AuditIcon.PAYROLL, AuditSeverity.CRITICAL),
    BONUS_ADDED("Dodanie bonusu", "Dodano bonus", AuditIcon.PAYROLL, AuditSeverity.CRITICAL),
    BONUS_DELETED("Usunięcie bonusu", "Usunięto bonus", AuditIcon.PAYROLL, AuditSeverity.CRITICAL),

    // ── Task-specific ───────────────────────────────────────────────────────
    TASK_CREATED("Utworzenie zadania", "Utworzono nowe zadanie", AuditIcon.TASK),

    // ── Door to Door ─────────────────────────────────────────────────────────
    DOOR_TO_DOOR_ADDED("Dodanie obsługi Door to Door", "Dodano obsługę Door to Door do wizyty", AuditIcon.VEHICLE),
    DOOR_TO_DOOR_UPDATED("Aktualizacja obsługi Door to Door", "Zaktualizowano obsługę Door to Door", AuditIcon.VEHICLE),

    // ── Campaigns ───────────────────────────────────────────────────────────
    CAMPAIGN_CREATED("Utworzenie kampanii", "Utworzono kampanię", AuditIcon.CAMPAIGN, AuditSeverity.HIGH),
    CAMPAIGN_UPDATED("Edycja kampanii", "Zmieniono kampanię", AuditIcon.CAMPAIGN),
    CAMPAIGN_DELETED("Usunięcie kampanii", "Usunięto kampanię", AuditIcon.CAMPAIGN, AuditSeverity.HIGH),
    CAMPAIGN_LAUNCHED("Uruchomienie kampanii", "Uruchomiono kampanię", AuditIcon.CAMPAIGN, AuditSeverity.CRITICAL),
    CAMPAIGN_CANCELLED("Anulowanie kampanii", "Anulowano kampanię", AuditIcon.CAMPAIGN, AuditSeverity.HIGH),

    // ── Communication ───────────────────────────────────────────────────────
    SMS_SENT("Wysłanie SMS", "Wysłano SMS", AuditIcon.MESSAGE, AuditSeverity.HIGH),
    SMS_FAILED("Błąd wysyłki SMS", "Nie udało się wysłać SMS", AuditIcon.MESSAGE, AuditSeverity.HIGH),
    EMAIL_SENT("Wysłanie e-maila", "Wysłano e-mail", AuditIcon.MESSAGE, AuditSeverity.HIGH),
    EMAIL_FAILED("Błąd wysyłki e-maila", "Nie udało się wysłać e-maila", AuditIcon.MESSAGE, AuditSeverity.HIGH),

    // ── Visit Card (customer-facing) ────────────────────────────────────────
    VISIT_CARD_LINK_SENT("Wysłanie linku do Karty Wizyty", "Wysłano link do Karty Wizyty", AuditIcon.LINK),
    VISIT_CARD_OPENED("Otwarcie Karty Wizyty", "Otwarto Kartę Wizyty", AuditIcon.VIEW, AuditSeverity.LOW),
    UPSELL_REQUESTED("Akceptacja sugerowanych usług", "Zaakceptowano sugerowane usługi", AuditIcon.APPROVE, AuditSeverity.CRITICAL),

    // ── Security events ─────────────────────────────────────────────────────
    CROSS_TENANT_ACCESS_ATTEMPT("Próba nieautoryzowanego dostępu", "Wykryto próbę nieautoryzowanego dostępu", AuditIcon.SECURITY, AuditSeverity.CRITICAL),
    LOGIN_FAILURE("Nieudane logowanie", "Odnotowano nieudane logowanie", AuditIcon.SECURITY, AuditSeverity.HIGH),
    LOGIN_SUCCESS("Zalogowanie", "Zalogowano się", AuditIcon.SECURITY, AuditSeverity.LOW),
    ACCOUNT_LOCKED("Zablokowanie konta", "Zablokowano konto", AuditIcon.SECURITY, AuditSeverity.CRITICAL)
}

/**
 * How a [FieldChange] value should be rendered. Set by [AuditFieldCatalog] from the field
 * name, or overridden explicitly by the emitting handler when the same name means
 * different things in different modules.
 */
enum class AuditValueType {
    TEXT,
    MONEY,
    NUMBER,
    DATE,
    DATE_TIME,
    BOOLEAN,
    ENUM,
    PHONE,
    EMAIL,
    LIST,
    REFERENCE,
    SECRET
}

/**
 * Represents a single field change within an audit event.
 *
 * [valueType] is optional and only needed to override what [AuditFieldCatalog] infers from
 * [field]; it is persisted so that a later change to the catalog cannot retroactively
 * reinterpret values that were already written.
 */
data class FieldChange(
    val field: String,
    val oldValue: String?,
    val newValue: String?,
    val valueType: AuditValueType? = null
)

/**
 * Type-safe ID wrapper for AuditLog entities.
 */
@JvmInline
value class AuditLogId(val value: UUID) {
    companion object {
        fun random() = AuditLogId(UUID.randomUUID())
        fun fromString(value: String) = AuditLogId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Who performed an audited action.
 *
 * [id] is the actor's identifier in its own namespace — a [UserId] for [AuditActorType.EMPLOYEE],
 * a [CustomerId] for [AuditActorType.CUSTOMER], null for automated actors. Filtering by
 * employee therefore means `actorType = EMPLOYEE AND actorId = ...`; the type is what keeps
 * the two namespaces from colliding.
 */
data class AuditActor(
    val type: AuditActorType,
    val id: UUID?,
    val displayName: String
) {
    companion object {
        /** Fallback name so the feed never renders a blank row when a handler has no name at hand. */
        const val UNKNOWN_EMPLOYEE = "Nieznany pracownik"
        const val UNKNOWN_CUSTOMER = "Klient"

        fun employee(userId: UserId, displayName: String?): AuditActor = AuditActor(
            type = AuditActorType.EMPLOYEE,
            id = userId.value,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: UNKNOWN_EMPLOYEE
        )

        fun customer(customerId: CustomerId?, displayName: String?): AuditActor = AuditActor(
            type = AuditActorType.CUSTOMER,
            id = customerId?.value,
            displayName = displayName?.takeIf { it.isNotBlank() } ?: UNKNOWN_CUSTOMER
        )

        fun system(displayName: String = "System"): AuditActor = AuditActor(
            type = AuditActorType.SYSTEM,
            id = null,
            displayName = displayName
        )

        fun integration(displayName: String): AuditActor = AuditActor(
            type = AuditActorType.INTEGRATION,
            id = null,
            displayName = displayName
        )
    }
}

/**
 * Business objects the event relates to, beyond the entity it acted on.
 *
 * These are stored as indexed columns rather than metadata keys, which is what makes
 * "show me everything that happened around this customer" a single indexed query instead
 * of a JSON scan.
 */
data class AuditContext(
    val customerId: CustomerId? = null,
    val customerName: String? = null,
    val vehicleId: VehicleId? = null,
    val vehicleName: String? = null,
    val visitId: VisitId? = null,
    val visitName: String? = null,
    val appointmentId: AppointmentId? = null,
    val appointmentName: String? = null
) {
    val isEmpty: Boolean
        get() = customerId == null && vehicleId == null && visitId == null && appointmentId == null

    companion object {
        val EMPTY = AuditContext()
    }
}

/**
 * Monetary value attached to an event — the answer to "na jaką kwotę?" without the reader
 * having to open the entity. Always gross, in the studio's currency.
 */
data class AuditAmount(
    val value: BigDecimal,
    val currency: String = DEFAULT_CURRENCY
) {
    companion object {
        const val DEFAULT_CURRENCY = "PLN"

        /** See [auditMoney]: the log stores decimal amounts, handlers hold grosz. */
        fun ofGrosz(grosz: Long, currency: String = DEFAULT_CURRENCY): AuditAmount =
            AuditAmount(auditMoneyDecimal(grosz), currency)
    }
}

/**
 * Converts an internal grosz amount to the decimal the audit log expects.
 *
 * Every monetary value in the domain is a `Long` of grosz, while [AuditValueType.MONEY]
 * formats exactly what it is handed. Passing grosz through unconverted is therefore not a
 * rounding nuisance but a factor-of-100 lie: a 123,45 zl service renders as "12 345,00 zl".
 */
fun auditMoneyDecimal(grosz: Long): BigDecimal = BigDecimal(grosz).movePointLeft(2)

/** [auditMoneyDecimal] as the plain string a [FieldChange] carries. */
fun auditMoney(grosz: Long): String = auditMoneyDecimal(grosz).toPlainString()

/**
 * Domain model for an audit log entry.
 * Represents a single auditable action performed by an actor on an entity.
 */
data class AuditLog(
    val id: AuditLogId,
    val studioId: StudioId,
    val actor: AuditActor,
    val module: AuditModule,
    val entityId: String,
    val entityDisplayName: String?,
    val action: AuditAction,
    val changes: List<FieldChange>,
    val metadata: Map<String, String>,
    val context: AuditContext,
    val amount: AuditAmount?,
    val severity: AuditSeverity,
    val channel: AuditChannel,
    val ipAddress: String?,
    /** Groups events produced by one user gesture, so the feed can collapse them. */
    val correlationId: UUID?,
    val createdAt: Instant
)

package pl.detailing.crm.metrics.domain

/**
 * The closed vocabulary of business events written to `metric_events`.
 *
 * Why an enum and not a free-form string: a metrics stream whose event names are typed
 * by hand at every call site rots within a quarter — `reservation_created`,
 * `reservationCreated` and `RESERVATION_CREATED` end up as three different series and
 * every dashboard silently under-counts. The enum makes the vocabulary reviewable in
 * one place and makes a typo a compile error.
 *
 * [countsToward] declares which roll-up column an event feeds, so adding an event type
 * does not mean touching the roll-up SQL by hand.
 *
 * Volume note: only *business-meaningful* events belong here. Raw HTTP traffic is
 * pre-aggregated in memory (see `apiaudit`) and never produces one row per request.
 */
enum class MetricEventType(
    val category: MetricEventCategory,
    val description: String
) {
    // ── Core business activity ────────────────────────────────────────────────
    RESERVATION_CREATED(MetricEventCategory.BUSINESS, "Utworzono rezerwację (wizytę w kalendarzu)"),
    RESERVATION_CANCELLED(MetricEventCategory.BUSINESS, "Anulowano rezerwację"),
    VISIT_CREATED(MetricEventCategory.BUSINESS, "Rozpoczęto wizytę (przyjęcie pojazdu)"),
    VISIT_COMPLETED(MetricEventCategory.BUSINESS, "Zakończono wizytę (wydanie pojazdu)"),
    CUSTOMER_CREATED(MetricEventCategory.BUSINESS, "Dodano klienta"),
    VEHICLE_CREATED(MetricEventCategory.BUSINESS, "Dodano pojazd"),
    PROTOCOL_SIGNED(MetricEventCategory.BUSINESS, "Podpisano protokół"),
    INVOICE_ISSUED(MetricEventCategory.BUSINESS, "Wystawiono dokument sprzedaży"),

    // ── Resource consumption (billable / cost-bearing) ────────────────────────
    SMS_SENT(MetricEventCategory.RESOURCE, "Wysłano wiadomość SMS"),
    SMS_FAILED(MetricEventCategory.RESOURCE, "Nieudana wysyłka SMS"),
    EMAIL_SENT(MetricEventCategory.RESOURCE, "Wysłano wiadomość e-mail"),
    EMAIL_FAILED(MetricEventCategory.RESOURCE, "Nieudana wysyłka e-mail"),
    STORAGE_FILE_UPLOADED(MetricEventCategory.RESOURCE, "Wgrano plik do magazynu obiektowego"),
    AI_COMPLETION(MetricEventCategory.RESOURCE, "Wywołanie modelu językowego (koszt zewnętrzny)"),

    // ── Account lifecycle ─────────────────────────────────────────────────────
    LOGIN_SUCCESS(MetricEventCategory.LIFECYCLE, "Udane logowanie"),
    LOGIN_FAILURE(MetricEventCategory.LIFECYCLE, "Nieudane logowanie"),
    STUDIO_REGISTERED(MetricEventCategory.LIFECYCLE, "Rejestracja nowego studia"),
    TRIAL_STARTED(MetricEventCategory.LIFECYCLE, "Rozpoczęcie okresu próbnego"),
    PLAN_ACTIVATED(MetricEventCategory.LIFECYCLE, "Aktywacja pakietu płatnego"),
    PLAN_CHANGED(MetricEventCategory.LIFECYCLE, "Zmiana pakietu"),
    ADD_ON_ACTIVATED(MetricEventCategory.LIFECYCLE, "Aktywacja modułu dodatkowego"),
    SUBSCRIPTION_EXPIRED(MetricEventCategory.LIFECYCLE, "Wygaśnięcie subskrypcji"),
    EMPLOYEE_INVITED(MetricEventCategory.LIFECYCLE, "Zaproszono pracownika"),

    // ── Integrations with third parties (their failures are our support load) ─
    INTEGRATION_CALL_OK(MetricEventCategory.INTEGRATION, "Udane wywołanie integracji zewnętrznej"),
    INTEGRATION_CALL_FAILED(MetricEventCategory.INTEGRATION, "Nieudane wywołanie integracji zewnętrznej");

    /** Column of `metric_daily_studio_snapshots` this event feeds, or null if it only lives raw. */
    val countsToward: String?
        get() = when (this) {
            RESERVATION_CREATED -> "reservations_created"
            VISIT_CREATED -> "visits_created"
            VISIT_COMPLETED -> "visits_completed"
            SMS_SENT -> "sms_sent"
            EMAIL_SENT -> "emails_sent"
            LOGIN_SUCCESS -> "logins"
            else -> null
        }
}

enum class MetricEventCategory {
    BUSINESS,
    RESOURCE,
    LIFECYCLE,
    INTEGRATION
}

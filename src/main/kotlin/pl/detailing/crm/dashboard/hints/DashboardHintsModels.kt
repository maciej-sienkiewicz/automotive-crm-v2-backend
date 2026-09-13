package pl.detailing.crm.dashboard.hints

/**
 * Podpowiedź z paska na Tablicy (między powitaniem a kafelkami stanu).
 *
 * Jedna podpowiedź to jedno zdanie i najwyżej jeden przycisk. Backend zwraca
 * listę posortowaną malejąco po ważności; frontend pokazuje pierwszą, a po jej
 * zamknięciu następną. Regułą jest konkret: podpowiedź istnieje tylko wtedy,
 * gdy da się z nią coś zrobić — inaczej to dekoracja, nie pomoc.
 */
data class DashboardHint(
    /**
     * Stabilny klucz zamknięcia. Podpowiedzi okresowe niosą okres w kluczu
     * (np. WORKTIME_MISSING_2026-08), więc następny miesiąc czy tydzień
     * to nowy klucz i zamknięcie starego niczego nie ukrywa.
     */
    val key: String,
    val kind: DashboardHintKind,
    val text: String,
    val action: DashboardHintAction?,
    /** true = zamknięcie chowa na zawsze (upselle); false = drzemka 7 dni. */
    val permanentDismiss: Boolean,
    /**
     * Waga wizualna podpowiedzi. O tym, czy coś jest alarmem, decyduje backend —
     * bo to ocena danych („klient czeka na naszą odpowiedź"), a nie sposób ich
     * pokazania. Frontend maluje [CRITICAL] na czerwono, [INFO] zostawia spokojne;
     * domyślnie [INFO], więc wszystkie dotychczasowe reguły zostają bez zmian.
     */
    val severity: DashboardHintSeverity = DashboardHintSeverity.INFO
)

enum class DashboardHintKind {
    LEADS_AWAITING,
    WORKTIME_MISSING,
    WORKTIME_UNUSED,
    COMPETITOR_STANDOUT,
    UNREAD_MAIL,
    SELF_IG_SILENT,
    KSEF_UPSELL
}

/**
 * Czerwień jest droga: gdyby dostała ją połowa podpowiedzi, przestałaby cokolwiek
 * znaczyć. [CRITICAL] to wyłącznie zaległość, która kosztuje pieniądze teraz —
 * zapytanie, na które klient czeka, a piłka jest po naszej stronie.
 */
enum class DashboardHintSeverity {
    INFO,
    CRITICAL
}

data class DashboardHintAction(
    val label: String,
    val type: DashboardHintActionType,
    /** Ścieżka w aplikacji (NAVIGATE) albo pełny adres (EXTERNAL). Null dla akcji specjalnych. */
    val url: String?
)

enum class DashboardHintActionType {
    /** Nawigacja wewnątrz aplikacji. */
    NAVIGATE,
    /** Link zewnętrzny (np. post na Instagramie), nowa karta. */
    EXTERNAL,
    /** Wyłączenie śledzenia czasu pracy we wszystkich rolach studia. */
    DISABLE_WORKTIME
}

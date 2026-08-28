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
    val permanentDismiss: Boolean
)

enum class DashboardHintKind {
    WORKTIME_MISSING,
    WORKTIME_UNUSED,
    COMPETITOR_STANDOUT,
    UNREAD_MAIL,
    SELF_IG_SILENT,
    KSEF_UPSELL
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

package pl.detailing.crm.leads.domain

/**
 * Zestaw startowy tagów leada — oś „o co pytają" w analityce.
 *
 * UWAGA: to nie jest już zamknięta lista. Od V74 tagi żyją w `lead_tag_definitions`
 * per studio i użytkownik dodaje oraz usuwa je sam; ten enum jest wyłącznie zasiewem
 * dla nowego studia i punktem odniesienia dla migracji historii. Nic w kodzie nie
 * powinno zakładać, że tag leada da się sprowadzić do jednej z tych stałych.
 *
 * Zamknięta lista jest ceną tego, że analityka w ogóle działa: swobodnego tekstu nie
 * da się zsumować w odpowiedź na pytanie „o co klienci pytają najczęściej". Tag na
 * leadzie może być wiele — jedno zapytanie potrafi dotyczyć folii z przodu, korekty
 * reszty lakieru i powłoki na koniec, a wciśnięte w jedną kategorię liczyłoby się
 * tylko raz i nie tam, gdzie trzeba.
 *
 * Kody są nadzbiorem dawnych [LeadCategory], żeby przepisanie historii w migracji
 * V71 było tożsamościowe i nie zgubiło ani jednego wcześniejszego zapytania.
 */
enum class LeadTag(val label: String) {
    CERAMIC_COATING("Powłoka ceramiczna"),
    PPF_WRAP("Folia PPF / oklejanie"),
    CORRECTION_POLISH("Korekta lakieru"),
    INTERIOR("Detailing wnętrza"),
    WASH_MAINTENANCE("Mycie i pielęgnacja"),
    FULL_DETAILING("Pełny detailing"),
    OTHER("Inne");

    companion object {
        fun fromCode(code: String): LeadTag? = entries.firstOrNull { it.name == code }
    }
}

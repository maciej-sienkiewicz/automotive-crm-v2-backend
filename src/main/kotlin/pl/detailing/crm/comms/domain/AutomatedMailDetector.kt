package pl.detailing.crm.comms.domain

/**
 * Czy ta wiadomość jest odpowiedzią człowieka, czy wypluł ją automat.
 *
 * Rozstrzygnięcie potrzebne po stronie leadów: „czas pierwszej reakcji" ma mierzyć
 * moment, w którym ktoś ze studia faktycznie odpisał. Autoresponder („jestem na
 * urlopie"), potwierdzenie systemowe czy newsletter wysłany z tej samej skrzynki
 * pokazałyby reakcję w kilka sekund i zafałszowały statystyki mocniej niż brak
 * pomiaru w ogóle.
 *
 * Sygnały to standardowe nagłówki, którymi automaty same się przedstawiają
 * (RFC 3834 i praktyka): Auto-Submitted, Precedence, X-Autoreply oraz nagłówki
 * list wysyłkowych. Człowiek piszący z klienta pocztowego nie ustawia żadnego
 * z nich, więc fałszywe trafienie oznacza pominięty stempel, nie zmyśloną reakcję.
 */
object AutomatedMailDetector {

    private val BULK_PRECEDENCE = setOf("bulk", "junk", "list", "auto_reply")

    fun isAutomated(headers: Map<String, String>): Boolean {
        val normalized = headers.entries.associate { (key, value) -> key.lowercase() to value.trim().lowercase() }

        // RFC 3834: „no" znaczy wprost „to napisał człowiek".
        normalized["auto-submitted"]?.let { if (it != "no") return true }
        normalized["precedence"]?.let { if (it in BULK_PRECEDENCE) return true }
        if (normalized.containsKey("x-autoreply")) return true
        if (normalized.containsKey("x-auto-response-suppress")) return true

        // Wysyłka masowa: newsletter albo kampania, nie rozmowa z klientem.
        if (normalized.containsKey("list-id") || normalized.containsKey("list-unsubscribe")) return true

        return false
    }
}

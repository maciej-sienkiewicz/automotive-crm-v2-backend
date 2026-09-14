package pl.detailing.crm.communication.window

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Konfiguracja [SendWindow], wiązana z `communication.send-window.*`
 * (env: COMMUNICATION_SEND_WINDOW_ENABLED, COMMUNICATION_SEND_WINDOW_OPENS_AT,
 * COMMUNICATION_SEND_WINDOW_CLOSES_AT, COMMUNICATION_SEND_WINDOW_ZONE).
 *
 * Godziny jako tekst `HH:mm`, parsowane w [SendWindowConfig] — Spring nie ma domyślnego
 * konwertera String → LocalTime dla properties, a błąd w formacie ma wywrócić start
 * aplikacji czytelnym komunikatem, nie cichym fallbackiem.
 */
@ConfigurationProperties(prefix = "communication.send-window")
data class SendWindowProperties(
    val enabled: Boolean = true,
    val zone: String = "Europe/Warsaw",
    val opensAt: String = "12:00",
    val closesAt: String = "18:00"
)

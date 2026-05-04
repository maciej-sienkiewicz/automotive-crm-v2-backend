package pl.detailing.crm.email.domain

import pl.detailing.crm.shared.StudioId

/**
 * A single event-triggered email notification rule.
 * Both [subjectTemplate] and [bodyTemplate] support the same {{placeholder}} syntax.
 */
data class EmailNotificationRule(
    val enabled: Boolean,
    val subjectTemplate: String,
    val bodyTemplate: String
)

/**
 * Per-studio configuration for automated email notifications.
 * One instance exists per studio; missing row means all rules are disabled (defaults returned in-memory).
 */
data class EmailAutomationConfig(
    val studioId: StudioId,
    val visitWelcome: EmailNotificationRule,
    val visitReadyForPickup: EmailNotificationRule
) {
    companion object {
        private const val DEFAULT_VISIT_WELCOME_SUBJECT =
            "Potwierdzenie przyjęcia pojazdu – {{pojazd}} (wizyta {{numer_wizyty}})"
        private const val DEFAULT_VISIT_WELCOME_BODY = """Szanowny/a {{imie_nazwisko}},

Dziękujemy za powierzenie nam Państwa pojazdu. Niniejszym potwierdzamy przyjęcie pojazdu {{pojazd}}{{rejestracja}} do naszego serwisu.

Numer wizyty: {{numer_wizyty}}

W razie pytań zapraszamy do kontaktu z naszym serwisem.

Pozdrawiamy,
{{studio}}"""

        private const val DEFAULT_VISIT_READY_SUBJECT =
            "Twój pojazd jest gotowy do odbioru! – {{pojazd}}"
        private const val DEFAULT_VISIT_READY_BODY = """Szanowny/a {{imie_nazwisko}},

Mamy dobre wiadomości! Prace nad Twoim pojazdem {{pojazd}}{{rejestracja}} zostały zakończone.
Auto jest już gotowe i czeka na odbiór w naszym studiu.

Numer wizyty: {{numer_wizyty}}

Zapraszamy po odbiór w godzinach otwarcia naszego serwisu. Do zobaczenia!

Pozdrawiamy,
{{studio}}"""

        fun defaultFor(studioId: StudioId) = EmailAutomationConfig(
            studioId = studioId,
            visitWelcome = EmailNotificationRule(
                enabled = false,
                subjectTemplate = DEFAULT_VISIT_WELCOME_SUBJECT,
                bodyTemplate = DEFAULT_VISIT_WELCOME_BODY
            ),
            visitReadyForPickup = EmailNotificationRule(
                enabled = false,
                subjectTemplate = DEFAULT_VISIT_READY_SUBJECT,
                bodyTemplate = DEFAULT_VISIT_READY_BODY
            )
        )
    }
}

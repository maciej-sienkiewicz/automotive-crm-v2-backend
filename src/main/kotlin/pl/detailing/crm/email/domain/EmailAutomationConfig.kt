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
) {
    /** No subject or no body, no e-mail. Nothing is ever substituted for a blank one. */
    val sendable: Boolean get() = enabled && subjectTemplate.isNotBlank() && bodyTemplate.isNotBlank()
}

/**
 * Per-studio configuration for automated email notifications.
 *
 * One instance exists per studio. A studio with no row sends nothing — [defaultFor]
 * exists only to seed the editor with a starting point, never to stand in for a
 * template at send time.
 */
data class EmailAutomationConfig(
    val studioId: StudioId,
    val visitWelcome: EmailNotificationRule,
    val visitReadyForPickup: EmailNotificationRule,
    val batchOrderClose: EmailNotificationRule,
    val visitCardLink: EmailNotificationRule,
    val reservationCardLink: EmailNotificationRule
) {
    companion object {
        // Starter templates offered in the editor. Every rule ships disabled, so none of
        // this text reaches a customer until the studio has read it and switched it on.

        private const val DEFAULT_VISIT_WELCOME_SUBJECT =
            "Potwierdzenie przyjęcia pojazdu – {{pojazd}} (wizyta {{numer_wizyty}})"
        private const val DEFAULT_VISIT_WELCOME_BODY = """Szanowny/a {{imie_nazwisko}},

Dziękujemy za powierzenie nam Państwa pojazdu. Niniejszym potwierdzamy przyjęcie pojazdu {{pojazd}} {{rejestracja}} do naszego serwisu.

Numer wizyty: {{numer_wizyty}}

W razie pytań zapraszamy do kontaktu z naszym serwisem.

Pozdrawiamy"""

        private const val DEFAULT_VISIT_READY_SUBJECT =
            "Twój pojazd jest gotowy do odbioru! – {{pojazd}}"
        private const val DEFAULT_VISIT_READY_BODY = """Szanowny/a {{imie_nazwisko}},

Mamy dobre wiadomości! Prace nad Twoim pojazdem {{pojazd}} {{rejestracja}} zostały zakończone.
Auto jest już gotowe i czeka na odbiór.

Numer wizyty: {{numer_wizyty}}

Zapraszamy po odbiór w godzinach otwarcia naszego serwisu. Do zobaczenia!

Pozdrawiamy"""

        private const val DEFAULT_BATCH_CLOSE_SUBJECT =
            "Zestawienie zbiorcze – {{kontrahent}} – {{okres}}"
        private const val DEFAULT_BATCH_CLOSE_BODY = """Szanowni Państwo,

Przesyłamy zestawienie zbiorcze za okres {{okres}} dla kontrahenta {{kontrahent}}.

Liczba wpisów: {{liczba_wpisow}}
Łączna kwota brutto: {{kwota_brutto}}

Zestawienie w formacie PDF znajdą Państwo w załączniku.

Pozdrawiamy"""

        private const val DEFAULT_VISIT_CARD_LINK_SUBJECT =
            "Karta wizyty {{numer_wizyty}}"
        private const val DEFAULT_VISIT_CARD_LINK_BODY = """Dzień dobry {{imie}},

przygotowaliśmy Kartę Wizyty dla Twojego pojazdu {{pojazd}} {{rejestracja}} (wizyta {{numer_wizyty}}, termin: {{data}} {{godzina}}).

Znajdziesz na niej szczegóły rezerwacji, zakres usług z wyceną oraz — w trakcie wizyty — dokumentację zdjęciową i dokumenty:
{{link}}

Pozdrawiamy"""

        private const val DEFAULT_RESERVATION_CARD_LINK_SUBJECT =
            "Twoja rezerwacja {{data}} {{godzina}}"
        private const val DEFAULT_RESERVATION_CARD_LINK_BODY = """Dzień dobry {{imie}},

przygotowaliśmy stronę Twojej rezerwacji (termin: {{data}} {{godzina}}).

Znajdziesz na niej szczegóły rezerwacji i zakres usług z wyceną, a po przyjęciu pojazdu także dokumentację zdjęciową i dokumenty:
{{link}}

Pozdrawiamy"""

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
            ),
            batchOrderClose = EmailNotificationRule(
                enabled = false,
                subjectTemplate = DEFAULT_BATCH_CLOSE_SUBJECT,
                bodyTemplate = DEFAULT_BATCH_CLOSE_BODY
            ),
            visitCardLink = EmailNotificationRule(
                enabled = false,
                subjectTemplate = DEFAULT_VISIT_CARD_LINK_SUBJECT,
                bodyTemplate = DEFAULT_VISIT_CARD_LINK_BODY
            ),
            reservationCardLink = EmailNotificationRule(
                enabled = false,
                subjectTemplate = DEFAULT_RESERVATION_CARD_LINK_SUBJECT,
                bodyTemplate = DEFAULT_RESERVATION_CARD_LINK_BODY
            )
        )
    }
}

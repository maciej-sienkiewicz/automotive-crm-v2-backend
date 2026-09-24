package pl.detailing.crm.comms.domain

/** Adresat odpowiedzi w wątku: dokąd pójdzie mail po kliknięciu „Odpowiedz". */
data class ReplyTarget(val email: String, val name: String?)

/** To, co resolver musi wiedzieć o jednej wiadomości — bez treści i załączników. */
data class ReplyCandidate(
    val direction: CommDirection,
    val fromEmail: String,
    val fromName: String?,
    val replyToEmail: String?,
    val replyToName: String?,
    val toEmails: List<String>
)

/**
 * Ustala adresata odpowiedzi PO STRONIE SERWERA.
 *
 * Wcześniej robiła to przeglądarka: „odpowiedz na drugą stronę wątku". Dla zgłoszeń
 * z formularza drugą stroną był robot — często adres samego studia — więc wycena
 * wysłana z CRM-a lądowała w skrzynce studia, a klient nie dostawał nic. Tutaj
 * obowiązuje ta sama zasada co w każdym programie pocztowym (RFC 5322 §3.6.2):
 * odpowiadamy na `Reply-To`, a gdy go nie ma — na nadawcę. Do tego jeden twardy
 * warunek: adres po naszej stronie (skrzynka studia, robot formularza, serwer poczty)
 * nigdy nie jest adresatem odpowiedzi.
 *
 * Kolejność źródeł:
 *  1. najnowsza wiadomość przychodząca od klienta — `Reply-To`, potem nadawca
 *     (klient mógł odpisać z innego adresu niż wpisany w formularz);
 *  2. druga strona wątku, o ile jest klientem;
 *  3. odbiorca naszej ostatniej wiadomości (wątek zaczęty przez nas).
 *
 * Null znaczy „nie wiemy" — interfejs każe wtedy wpisać adres ręcznie, zamiast
 * podstawić cokolwiek.
 */
object ReplyAddressResolver {

    fun resolve(
        kind: CommThreadKind,
        participantEmail: String,
        participantName: String?,
        messagesChronological: List<ReplyCandidate>,
        book: MailAddressBook
    ): ReplyTarget? {
        if (kind == CommThreadKind.SYSTEM) return null

        messagesChronological.asReversed()
            .asSequence()
            .filter { it.direction == CommDirection.INBOUND }
            .forEach { message ->
                val replyTo = message.replyToEmail?.let(MailAddressBook::normalize)
                if (replyTo != null && !book.isNotAClient(replyTo)) {
                    return ReplyTarget(replyTo, message.replyToName ?: nameFor(replyTo, participantEmail, participantName))
                }
                val from = MailAddressBook.normalize(message.fromEmail)
                if (!book.isNotAClient(from)) {
                    return ReplyTarget(from, message.fromName ?: nameFor(from, participantEmail, participantName))
                }
            }

        val participant = MailAddressBook.normalize(participantEmail)
        if (!book.isNotAClient(participant)) return ReplyTarget(participant, participantName)

        messagesChronological.asReversed()
            .asSequence()
            .filter { it.direction == CommDirection.OUTBOUND }
            .flatMap { it.toEmails.asSequence() }
            .map(MailAddressBook::normalize)
            .firstOrNull { !book.isNotAClient(it) }
            ?.let { return ReplyTarget(it, null) }

        return null
    }

    private fun nameFor(email: String, participantEmail: String, participantName: String?): String? =
        participantName.takeIf { MailAddressBook.normalize(participantEmail) == email }
}

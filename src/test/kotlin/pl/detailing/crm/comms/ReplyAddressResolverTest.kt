package pl.detailing.crm.comms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommThreadKind
import pl.detailing.crm.comms.domain.MailAddressBook
import pl.detailing.crm.comms.domain.ReplyAddressResolver
import pl.detailing.crm.comms.domain.ReplyCandidate
import pl.detailing.crm.comms.domain.ReplyTarget

/**
 * Dokąd pójdzie „Odpowiedz". Zgłoszenie z produkcji: wycena 6500 zł dla Alfy Tonale
 * poszła na biuro@carslab.pl — do samego studia — bo odpowiedź szła na drugą stronę
 * wątku, a drugą stroną był robot formularza z adresem studia.
 */
class ReplyAddressResolverTest {

    private val book = MailAddressBook(ownAddresses = setOf("biuro@carslab.pl"), formSenders = emptySet())

    private fun inbound(from: String, replyTo: String? = null, fromName: String? = null) = ReplyCandidate(
        direction = CommDirection.INBOUND,
        fromEmail = from,
        fromName = fromName,
        replyToEmail = replyTo,
        replyToName = null,
        toEmails = listOf("biuro@carslab.pl")
    )

    private fun outbound(to: String) = ReplyCandidate(
        direction = CommDirection.OUTBOUND,
        fromEmail = "biuro@carslab.pl",
        fromName = null,
        replyToEmail = null,
        replyToName = null,
        toEmails = listOf(to)
    )

    @Test
    fun `odpowiedz na zgloszenie z formularza idzie na Reply-To, nie na robota`() {
        val target = ReplyAddressResolver.resolve(
            kind = CommThreadKind.FORM,
            participantEmail = "maciej-winkel@wp.pl",
            participantName = "Maciej",
            messagesChronological = listOf(inbound("biuro@carslab.pl", replyTo = "maciej-winkel@wp.pl")),
            book = book
        )

        assertEquals(ReplyTarget("maciej-winkel@wp.pl", "Maciej"), target)
    }

    @Test
    fun `klient, ktory odpisal z innego adresu, dostaje odpowiedz na ten adres`() {
        val target = ReplyAddressResolver.resolve(
            kind = CommThreadKind.FORM,
            participantEmail = "maciej@firma.pl",
            participantName = null,
            messagesChronological = listOf(
                inbound("biuro@carslab.pl", replyTo = "maciej@firma.pl"),
                outbound("maciej@firma.pl"),
                inbound("maciej.prywatny@gmail.com", fromName = "Maciej B")
            ),
            book = book
        )

        assertEquals(ReplyTarget("maciej.prywatny@gmail.com", "Maciej B"), target)
    }

    @Test
    fun `wielki watek robota bez Reply-To nie ma adresata - lepiej zapytac niz wyslac do studia`() {
        val target = ReplyAddressResolver.resolve(
            kind = CommThreadKind.DIRECT,
            participantEmail = "biuro@carslab.pl",
            participantName = "Carslab",
            messagesChronological = listOf(inbound("biuro@carslab.pl"), outbound("biuro@carslab.pl")),
            book = book
        )

        assertNull(target)
    }

    @Test
    fun `watek zaczety przez nas odpowiada odbiorcy naszej wiadomosci`() {
        val target = ReplyAddressResolver.resolve(
            kind = CommThreadKind.DIRECT,
            participantEmail = "klient@gmail.com",
            participantName = null,
            messagesChronological = listOf(outbound("klient@gmail.com")),
            book = book
        )

        assertEquals("klient@gmail.com", target?.email)
    }

    @Test
    fun `zwroty serwera nie maja adresata`() {
        val target = ReplyAddressResolver.resolve(
            kind = CommThreadKind.SYSTEM,
            participantEmail = "mailer-daemon@s190.cyber-folks.pl",
            participantName = null,
            messagesChronological = listOf(inbound("mailer-daemon@s190.cyber-folks.pl")),
            book = book
        )

        assertNull(target)
    }
}

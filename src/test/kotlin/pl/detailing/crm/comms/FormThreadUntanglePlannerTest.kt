package pl.detailing.crm.comms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.FormThreadUntanglePlanner
import pl.detailing.crm.comms.domain.MailAddressBook
import pl.detailing.crm.comms.domain.UntangleMessage
import java.time.Instant
import java.util.UUID

/**
 * Rozplątanie wielkiego wątku formularza — na wycinku wątku z produkcji (carslab.pl):
 * zgłoszenia klientów przychodzą od biuro@carslab.pl do biuro@carslab.pl, klient siedzi
 * w Reply-To (albo — dla starej poczty — w polu „Email:" treści), a między nimi leżą
 * nasze odpowiedzi, odpisy klientów i zwroty serwera pocztowego.
 */
class FormThreadUntanglePlannerTest {

    private val book = MailAddressBook(ownAddresses = setOf("biuro@carslab.pl"), formSenders = emptySet())
    private val base = Instant.parse("2026-08-15T16:00:00Z")

    private fun at(hours: Long): Instant = base.plusSeconds(hours * 3600)

    private fun submission(hdr: String, client: String, hours: Long, fromHeaderOnly: Boolean = true) = UntangleMessage(
        id = UUID.randomUUID(),
        messageIdHdr = hdr,
        direction = CommDirection.INBOUND,
        fromEmail = "biuro@carslab.pl",
        fromName = "Carslab",
        replyToEmail = if (fromHeaderOnly) client else null,
        replyToName = null,
        toEmails = listOf("biuro@carslab.pl"),
        inReplyTo = null,
        references = emptyList(),
        sentAt = at(hours),
        recoveredClientEmail = if (fromHeaderOnly) null else client
    )

    private fun ourReply(hdr: String, to: String, inReplyTo: String?, hours: Long) = UntangleMessage(
        id = UUID.randomUUID(),
        messageIdHdr = hdr,
        direction = CommDirection.OUTBOUND,
        fromEmail = "biuro@carslab.pl",
        fromName = null,
        replyToEmail = null,
        replyToName = null,
        toEmails = listOf(to),
        inReplyTo = inReplyTo,
        references = listOfNotNull(inReplyTo),
        sentAt = at(hours)
    )

    private fun clientMail(hdr: String, from: String, inReplyTo: String?, hours: Long, name: String? = null) = UntangleMessage(
        id = UUID.randomUUID(),
        messageIdHdr = hdr,
        direction = CommDirection.INBOUND,
        fromEmail = from,
        fromName = name,
        replyToEmail = null,
        replyToName = null,
        toEmails = listOf("biuro@carslab.pl"),
        inReplyTo = inReplyTo,
        references = listOfNotNull(inReplyTo),
        sentAt = at(hours)
    )

    private fun bounce(hdr: String, refersTo: String, hours: Long) = UntangleMessage(
        id = UUID.randomUUID(),
        messageIdHdr = hdr,
        direction = CommDirection.INBOUND,
        fromEmail = "mailer-daemon@s190.cyber-folks.pl",
        fromName = "Mail Delivery System",
        replyToEmail = null,
        replyToName = null,
        toEmails = listOf("biuro@carslab.pl"),
        inReplyTo = null,
        references = listOf(refersTo),
        sentAt = at(hours)
    )

    @Test
    fun `kazde zgloszenie dostaje wlasna rozmowe razem z odpowiedziami`() {
        val grzegorz = submission("f1", "grzechu.pawelec@gmail.com", 0)
        val replyToGrzegorz = ourReply("o1", "grzechu.pawelec@gmail.com", "f1", 40)
        val grzegorzAgain = clientMail("c1", "grzechu.pawelec@gmail.com", "o1", 140, name = "Grzegorz Pawelec")
        val maciej = submission("f2", "maciej.blachowski@gmail.com", 195, fromHeaderOnly = false)
        val replyToMaciej = ourReply("o2", "maciej.blachowski@gmail.com", "f2", 220)
        val bounceForJacek = bounce("b1", "f3", 300)
        val jacek = submission("f3", "jacek257986@wp.pl", 190)

        val plan = FormThreadUntanglePlanner.plan(
            listOf(grzegorz, replyToGrzegorz, grzegorzAgain, maciej, replyToMaciej, bounceForJacek, jacek),
            book
        )

        assertEquals(3, plan.groups.size)
        val byClient = plan.groups.associateBy { it.clientEmail }
        assertEquals(
            listOf(grzegorz.id, replyToGrzegorz.id, grzegorzAgain.id),
            byClient.getValue("grzechu.pawelec@gmail.com").messageIds
        )
        assertEquals("Grzegorz Pawelec", byClient.getValue("grzechu.pawelec@gmail.com").clientName)
        assertEquals(listOf(maciej.id, replyToMaciej.id), byClient.getValue("maciej.blachowski@gmail.com").messageIds)
        assertEquals(listOf(jacek.id), byClient.getValue("jacek257986@wp.pl").messageIds)
        // Zwrot niesie w References identyfikator zgłoszenia Jacka — i mimo to nie trafia do jego rozmowy.
        assertEquals(listOf(bounceForJacek.id), plan.systemMessageIds)
        assertTrue(plan.leftoverIds.isEmpty())
    }

    @Test
    fun `odpis klienta bez naglowkow watku trafia do jego zgloszenia po adresie`() {
        val agnieszka = submission("f1", "agnieszka.rybarczyk@poczta.fm", 0)
        val reply = ourReply("o1", "agnieszka.rybarczyk@poczta.fm", "f1", 2)
        // Poczta klientki nie ustawiła In-Reply-To — dowodem jest tylko adres.
        val cancellation = clientMail("c1", "agnieszka.rybarczyk@poczta.fm", null, 160)

        val plan = FormThreadUntanglePlanner.plan(listOf(agnieszka, reply, cancellation), book)

        assertEquals(listOf(agnieszka.id, reply.id, cancellation.id), plan.groups.single().messageIds)
    }

    @Test
    fun `ponowne wyslanie formularza przez te sama osobe to jedna sprawa`() {
        val first = submission("f1", "kuna199696@gmail.com", 0)
        val second = submission("f2", "kuna199696@gmail.com", 0).copy(sentAt = at(0).plusSeconds(150))

        val plan = FormThreadUntanglePlanner.plan(listOf(first, second), book)

        val group = plan.groups.single()
        assertEquals(listOf(first.id, second.id), group.submissionIds)
    }

    @Test
    fun `odpowiedz wyslana do samego studia zostaje w starym watku, a nie w cudzej rozmowie`() {
        // 20.07: wycena poszła na biuro@carslab.pl, a zgłoszenia, na które odpowiadała, w wątku nie ma.
        val misdirected = ourReply("o0", "biuro@carslab.pl", "f-starsze", 0)
        val other = submission("f1", "grzechu.pawelec@gmail.com", 10)

        val plan = FormThreadUntanglePlanner.plan(listOf(misdirected, other), book)

        assertEquals(listOf(misdirected.id), plan.leftoverIds)
        assertEquals(listOf(other.id), plan.groups.single().messageIds)
    }

    @Test
    fun `zgloszenie bez odzyskanego klienta nie jest zgadywane`() {
        val unknown = submission("f1", "x", 0, fromHeaderOnly = false).copy(recoveredClientEmail = null)

        val plan = FormThreadUntanglePlanner.plan(listOf(unknown), book)

        assertFalse(plan.changesAnything)
        assertEquals(listOf(unknown.id), plan.leftoverIds)
    }
}

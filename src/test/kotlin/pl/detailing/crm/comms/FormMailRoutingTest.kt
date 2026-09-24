package pl.detailing.crm.comms

import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.domain.DeliveryReportDetector
import pl.detailing.crm.comms.domain.InboundRoute
import pl.detailing.crm.comms.domain.InboundRouter
import pl.detailing.crm.comms.domain.MailAddressBook
import pl.detailing.crm.comms.domain.ParsedEmail
import pl.detailing.crm.comms.infrastructure.MimeEmailParser
import java.time.Instant
import java.util.Properties

/**
 * Rozpoznanie zgłoszeń z formularza i zwrotów serwera — na przypadku z produkcji:
 * WP Mail SMTP na stronie studia wysyła każde zgłoszenie „od studia do studia"
 * (From: biuro@carslab.pl, To: biuro@carslab.pl), a klienta wpisuje w Reply-To.
 */
class FormMailRoutingTest {

    private val book = MailAddressBook(ownAddresses = setOf("biuro@carslab.pl"), formSenders = emptySet())

    private fun parsed(
        from: String,
        replyTo: String? = null,
        replyToName: String? = null,
        headers: Map<String, String> = emptyMap()
    ) = ParsedEmail(
        messageId = "m@x",
        inReplyTo = null,
        references = emptyList(),
        fromEmail = from,
        fromName = "Carslab",
        toEmails = listOf("biuro@carslab.pl"),
        ccEmails = emptyList(),
        subject = "Formularz kontaktowy - carslab - kontakt",
        sentAt = Instant.now(),
        bodyHtml = null,
        bodyText = "Imię: Jacek",
        attachments = emptyList(),
        imapUid = null,
        seen = false,
        headers = headers,
        replyToEmail = replyTo,
        replyToName = replyToName
    )

    @Test
    fun `zgloszenie od studia do studia z klientem w Reply-To to formularz z klientem jako druga strona`() {
        val route = InboundRouter.route(parsed("biuro@carslab.pl", replyTo = "jacek257986@wp.pl"), book)

        val relay = route as InboundRoute.FormRelay
        assertEquals("jacek257986@wp.pl", relay.clientEmail)
        assertEquals("biuro@carslab.pl", relay.relayEmail)
    }

    @Test
    fun `nieoznaczony robot wordpress z Reply-To tez jest zgloszeniem`() {
        val route = InboundRouter.route(parsed("wordpress@carslab.pl", replyTo = "klient@gmail.com"), book)

        assertTrue(route is InboundRoute.FormRelay)
    }

    @Test
    fun `sygnatura wtyczki w X-Mailer rozpoznaje robota o dowolnym adresie`() {
        val route = InboundRouter.route(
            parsed("strona@carslab.pl", replyTo = "klient@gmail.com", headers = mapOf("x-mailer" to "WPMailSMTP/Mailer/smtp 4.9.0")),
            book
        )

        assertTrue(route is InboundRoute.FormRelay)
    }

    @Test
    fun `zwykly klient z Reply-To na inny wlasny adres nie jest formularzem`() {
        val route = InboundRouter.route(parsed("jan@gmail.com", replyTo = "jan.kowalski@firma.pl"), book)

        assertEquals(InboundRoute.Direct, route)
    }

    @Test
    fun `Reply-To wskazujace na studio nie jest klientem`() {
        val route = InboundRouter.route(parsed("biuro@carslab.pl", replyTo = "biuro@carslab.pl"), book)

        assertEquals(InboundRoute.OwnMailbox, route)
    }

    @Test
    fun `oznaczony robot bez Reply-To dostaje wlasny watek, klient z tresci`() {
        val withRobot = book.copy(formSenders = setOf("no-reply@strona.pl"))

        val route = InboundRouter.route(parsed("no-reply@strona.pl"), withRobot)

        assertEquals(InboundRoute.FormRobot("no-reply@strona.pl"), route)
    }

    @Test
    fun `zwrot serwera pocztowego ma pierwszenstwo przed wszystkim`() {
        val route = InboundRouter.route(
            parsed("mailer-daemon@s190.cyber-folks.pl", replyTo = "klient@gmail.com"),
            book
        )

        assertEquals(InboundRoute.DeliveryReport, route)
    }

    @Test
    fun `raport doreczenia rozpoznany po strukturze a nie tylko po nadawcy`() {
        assertTrue(
            DeliveryReportDetector.isDeliveryReport(
                "postmaster@serwer.pl",
                emptyMap()
            )
        )
        assertTrue(
            DeliveryReportDetector.isDeliveryReport(
                "bounces@serwer.pl",
                mapOf("content-type" to "multipart/report; report-type=delivery-status; boundary=x")
            )
        )
        assertTrue(DeliveryReportDetector.isDeliveryReport("x@serwer.pl", mapOf("x-failed-recipients" to "a@b.pl")))
        assertFalse(DeliveryReportDetector.isDeliveryReport("klient@gmail.com", mapOf("content-type" to "text/plain")))
    }

    @Test
    fun `parser czyta Reply-To z naglowka, a przy jego braku nie podstawia From`() {
        val session = Session.getInstance(Properties())
        val withReplyTo = MimeMessage(session).apply {
            setFrom(InternetAddress("biuro@carslab.pl", "Carslab"))
            replyTo = arrayOf(InternetAddress("Jacek257986@wp.pl", "Jacek Nowak"))
            subject = "Formularz kontaktowy"
            setText("Imię: Jacek")
            saveChanges()
        }
        val withoutReplyTo = MimeMessage(session).apply {
            setFrom(InternetAddress("klient@gmail.com"))
            subject = "Pytanie"
            setText("Dzień dobry")
            saveChanges()
        }

        val parsedWith = MimeEmailParser().parse(withReplyTo, imapUid = 1L)
        val parsedWithout = MimeEmailParser().parse(withoutReplyTo, imapUid = 2L)

        assertEquals("jacek257986@wp.pl", parsedWith.replyToEmail)
        assertEquals("Jacek Nowak", parsedWith.replyToName)
        assertNull(parsedWithout.replyToEmail)
    }
}

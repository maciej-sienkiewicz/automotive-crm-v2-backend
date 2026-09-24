package pl.detailing.crm.comms.signature

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.comms.infrastructure.EmailHtmlSanitizer
import pl.detailing.crm.comms.send.SendMailHandler
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException

class UserMailSignatureServiceTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private var stored: CommUserSignatureEntity? = null
    private val repository = mockk<CommUserSignatureRepository>().also { repo ->
        every { repo.findByUserIdAndStudioId(userId.value, studioId.value) } answers { stored }
        val saved = slot<CommUserSignatureEntity>()
        every { repo.save(capture(saved)) } answers { saved.captured.also { stored = it } }
    }
    private val service = UserMailSignatureService(repository, EmailHtmlSanitizer())

    private val design = MailSignatureDesign(
        template = "baner-okrag",
        fullName = "Anna Kowalska",
        phone = "+48 600 123 456",
        photoUrl = "https://api.detailboost.pl/api/public/mail-signature/s/0123456789abcdef.jpg",
        color = "#0088B0",
        iconStyle = "color"
    )

    /** Fragment w kształcie tego, co renderuje kreator: tabele, bgcolor, style komórek, https img. */
    private val designedHtml =
        """<table role="presentation" cellpadding="0" cellspacing="0" border="0" style="border-collapse:collapse;max-width:560px;">""" +
            """<tr><td valign="middle" align="center" bgcolor="#0088b0" style="background:#0088b0;padding:22px 18px;width:140px;">""" +
            """<img src="https://api.detailboost.pl/api/public/mail-signature/s/0123456789abcdef.jpg" width="110" height="110" alt="Anna Kowalska" style="display:block;border:0;border-radius:50%;width:110px;height:110px;"></td>""" +
            """<td style="padding:20px 24px;"><div style="font-family:Arial, Helvetica, sans-serif;font-size:22px;font-weight:bold;color:#0088b0;">Anna Kowalska</div>""" +
            """<a href="tel:+48600123456" style="color:#333333;text-decoration:none;">+48 600 123 456</a></td></tr></table>"""

    @Test
    fun `stopka z kreatora zapisuje projekt i oddaje go przy odczycie`() {
        val saved = service.save(studioId, userId, designedHtml, enabledByDefault = true, design = design)

        assertEquals("#0088b0", saved.design!!.color)
        assertNotNull(stored!!.designJson)
        val read = service.get(studioId, userId)
        assertEquals("baner-okrag", read.design!!.template)
        assertEquals("+48 600 123 456", read.design!!.phone)
        assertEquals("color", read.design!!.iconStyle)
    }

    @Test
    fun `uklad motywu przechodzi przez sanitizer bez utraty atrybutow`() {
        val html = service.save(studioId, userId, designedHtml, enabledByDefault = true, design = design).bodyHtml!!

        assertTrue(html.contains("""role="presentation""""), html)
        assertTrue(html.contains("""cellpadding="0""""), html)
        assertTrue(html.contains("""bgcolor="#0088b0""""), html)
        assertTrue(html.contains("""valign="middle""""), html)
        assertTrue(html.contains("border-radius:50%"), html)
        assertTrue(html.contains("""src="https://api.detailboost.pl/api/public/mail-signature/s/0123456789abcdef.jpg""""), html)
        assertTrue(html.contains("""href="tel:+48600123456""""), html)
    }

    @Test
    fun `zapis stopki tekstowej kasuje poprzedni projekt`() {
        service.save(studioId, userId, designedHtml, enabledByDefault = true, design = design)
        service.save(studioId, userId, "<div>Anna</div>", enabledByDefault = true)

        assertNull(stored!!.designJson)
        assertNull(service.get(studioId, userId).design)
    }

    @Test
    fun `stopka z motywu ma wiekszy limit niz tekstowa`() {
        val longHtml = "<div>" + "a".repeat(UserMailSignatureService.MAX_LENGTH + 500) + "</div>"

        assertThrows<ValidationException> { service.save(studioId, userId, longHtml, enabledByDefault = true) }
        service.save(studioId, userId, longHtml, enabledByDefault = true, design = design)

        val tooLong = "<div>" + "a".repeat(UserMailSignatureService.MAX_DESIGNED_LENGTH) + "</div>"
        assertThrows<ValidationException> {
            service.save(studioId, userId, tooLong, enabledByDefault = true, design = design)
        }
    }

    @Test
    fun `nieprawidlowy projekt nie trafia do bazy`() {
        assertThrows<ValidationException> {
            service.save(studioId, userId, designedHtml, enabledByDefault = true, design = design.copy(template = "ekspert"))
        }
        assertNull(stored)
    }

    @Test
    fun `uszkodzony JSON projektu nie blokuje stopki`() {
        stored = CommUserSignatureEntity(
            userId = userId.value,
            studioId = studioId.value,
            bodyHtml = "<div>Anna</div>",
            designJson = "{nie-json"
        )

        val read = service.get(studioId, userId)
        assertEquals("<div>Anna</div>", read.bodyHtml)
        assertNull(read.design)
    }

    @Test
    fun `stopka z kreatora idzie bez kreski, tekstowa z kreska`() {
        val designed = SendMailHandler.signatureMarkup(UserMailSignature("<table></table>", true, design))
        val text = SendMailHandler.signatureMarkup(UserMailSignature("<div>Anna</div>", true, null))

        assertFalse(designed.contains("<div>--</div>"), designed)
        assertTrue(designed.contains("<table></table>"), designed)
        assertTrue(text.contains("<div>--</div><div>Anna</div>"), text)
        assertEquals("", SendMailHandler.signatureMarkup(UserMailSignature(null, false, null)))
    }
}

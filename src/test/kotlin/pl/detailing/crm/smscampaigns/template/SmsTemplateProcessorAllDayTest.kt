package pl.detailing.crm.smscampaigns.template

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import pl.detailing.crm.email.template.EmailTemplateContext
import pl.detailing.crm.email.template.EmailTemplateProcessor
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Regresja: klient z rezerwacją całodniową dostawał
 * „Przypominamy o wizycie dnia 09.09.2026 o godz. 00:00. Do zobaczenia!".
 * Północ nie jest godziną wizyty — zdanie ma stracić godzinę razem ze zwrotem, który ją
 * zapowiadał, i to w obu kanałach, z tego samego szablonu.
 */
class SmsTemplateProcessorAllDayTest {

    private val renderer = MessageTemplateRenderer()
    private val sms = SmsTemplateProcessor(renderer)
    private val email = EmailTemplateProcessor(renderer)

    private val midnight = LocalDateTime.of(2026, 9, 9, 0, 0).atZone(ZoneId.of("Europe/Warsaw")).toInstant()
    private val afternoon = LocalDateTime.of(2026, 9, 9, 14, 30).atZone(ZoneId.of("Europe/Warsaw")).toInstant()
    private val template = "Przypominamy o wizycie dnia {{data}} o godz. {{godzina}}. Do zobaczenia!"

    @Test
    fun `sms dla rezerwacji calodniowej nie zawiera 00 00`() {
        val text = sms.process(template, SmsTemplateContext("Anna", "Kowalska", midnight, allDay = true))
        assertEquals("Przypominamy o wizycie dnia 09.09.2026. Do zobaczenia!", text)
    }

    @Test
    fun `sms dla rezerwacji z godzina wyglada jak dotad`() {
        val text = sms.process(template, SmsTemplateContext("Anna", "Kowalska", afternoon))
        assertEquals("Przypominamy o wizycie dnia 09.09.2026 o godz. 14:30. Do zobaczenia!", text)
    }

    @Test
    fun `e-mail z tego samego szablonu zachowuje sie tak samo`() {
        val context = EmailTemplateContext("Anna", "Kowalska", "Anna Kowalska", "Audi RS6", "WE 4RS6X", "WIZ/1", midnight, allDay = true)
        assertEquals("Przypominamy o wizycie dnia 09.09.2026. Do zobaczenia!", email.process(template, context))
    }
}

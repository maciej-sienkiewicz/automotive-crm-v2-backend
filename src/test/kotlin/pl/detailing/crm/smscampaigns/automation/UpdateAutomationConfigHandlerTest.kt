package pl.detailing.crm.smscampaigns.automation

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import java.util.UUID

/**
 * Wyprzedzenie reguły musi być dodatnie: „0 minut przed wizytą" to przypomnienie w trakcie
 * wizyty, a wartość ujemna — po niej. Automat ma własną blokadę, ale zła konfiguracja ma
 * nie przejść już przy zapisie.
 */
class UpdateAutomationConfigHandlerTest {

    private val repository: SmsAutomationConfigRepository = mockk { every { save(any()) } answers { firstArg() } }
    private val handler = UpdateAutomationConfigHandler(repository)
    private val studioId = StudioId(UUID.randomUUID())

    @Test
    fun `wlaczona regula przed wizyta z zerowym wyprzedzeniem jest odrzucana`() {
        val ex = assertThrows(ValidationException::class.java) {
            handler.handle(command(preVisit = automation(enabled = true, offsetMinutes = 0)))
        }
        assertEquals("Wyprzedzenie reguły przed wizytą musi wynosić co najmniej 1 minutę", ex.message)
    }

    @Test
    fun `ujemne wyprzedzenie jest odrzucane`() {
        assertThrows(ValidationException::class.java) {
            handler.handle(command(postVisit = automation(enabled = true, offsetMinutes = -15)))
        }
    }

    @Test
    fun `wylaczona regula moze miec dowolny offset - nic z niej nie wychodzi`() {
        val saved = handler.handle(command(preVisit = automation(enabled = false, offsetMinutes = 0)))
        assertEquals(0, saved.preVisit.offsetMinutes)
    }

    @Test
    fun `minuta wyprzedzenia to najmniejsza poprawna wartosc`() {
        val saved = handler.handle(command(preVisit = automation(enabled = true, offsetMinutes = 1)))
        assertEquals(1, saved.preVisit.offsetMinutes)
    }

    private fun automation(enabled: Boolean, offsetMinutes: Int) =
        UpdateAutomationRuleCommand(enabled = enabled, offsetMinutes = offsetMinutes, messageTemplate = "Treść")

    private val notification = UpdateNotificationRuleCommand(enabled = false, messageTemplate = "")

    private fun command(
        preVisit: UpdateAutomationRuleCommand = automation(enabled = true, offsetMinutes = 60),
        postVisit: UpdateAutomationRuleCommand = automation(enabled = true, offsetMinutes = 30),
        delayedReminder: UpdateAutomationRuleCommand = automation(enabled = false, offsetMinutes = 0)
    ) = UpdateAutomationConfigCommand(
        studioId = studioId,
        preVisit = preVisit,
        postVisit = postVisit,
        delayedReminder = delayedReminder,
        bookingConfirmation = notification,
        rescheduleConfirmation = notification,
        visitReadyForPickup = notification,
        visitCardLink = notification,
        reservationCardLink = notification,
        upsellConsent = notification,
        upsellSuggestion = notification,
        signatureRequest = notification
    )
}

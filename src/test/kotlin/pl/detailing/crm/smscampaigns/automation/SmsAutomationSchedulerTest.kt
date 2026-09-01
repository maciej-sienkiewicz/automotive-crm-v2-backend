package pl.detailing.crm.smscampaigns.automation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.domain.SmsAutomationRule
import pl.detailing.crm.smscampaigns.infrastructure.SmsAppointmentQueryService
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import pl.detailing.crm.smscampaigns.infrastructure.SmsVisitQueryService
import pl.detailing.crm.smscampaigns.template.SmsTemplateProcessor
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.util.UUID

/**
 * The rules that speak about a visit in the past tense must be driven by visits.
 *
 * A studio reported customers thanked for a visit they never made: the booking simply
 * reached its end time while the car was never dropped off. These tests pin the fix at the
 * level where it matters — which table the scheduler asks.
 */
class SmsAutomationSchedulerTest {

    private val configRepository: SmsAutomationConfigRepository = mockk()
    private val appointmentQueryService: SmsAppointmentQueryService = mockk(relaxed = true)
    private val visitQueryService: SmsVisitQueryService = mockk(relaxed = true)
    private val smsLogRepository: SmsLogJpaRepository = mockk(relaxed = true)
    private val communicationGateway: OutboundCommunicationGateway = mockk(relaxed = true)
    private val templateProcessor: SmsTemplateProcessor = mockk(relaxed = true)
    private val visitRepository: VisitRepository = mockk(relaxed = true)
    private val communicationLogService: CommunicationLogService = mockk(relaxed = true)

    private val scheduler = SmsAutomationScheduler(
        configRepository,
        appointmentQueryService,
        visitQueryService,
        smsLogRepository,
        communicationGateway,
        templateProcessor,
        visitRepository,
        communicationLogService
    )

    private val studioId = StudioId(UUID.randomUUID())

    @Test
    fun `post-visit rule reads completed visits, never appointments`() {
        givenConfig(postVisit = rule(enabled = true, template = "Dziękujemy, {{imie}}!"))

        scheduler.processPendingAutomations()

        verify {
            visitQueryService.findCompletedByStudioIdAndPickupDateBetween(studioId, any(), any())
        }
        verify(exactly = 0) {
            appointmentQueryService.findByStudioIdAndStartTimeBetween(any(), any(), any())
        }
    }

    @Test
    fun `a rule the studio never switched on queries nothing`() {
        givenConfig(postVisit = rule(enabled = false, template = "Dziękujemy, {{imie}}!"))

        scheduler.processPendingAutomations()

        verify(exactly = 0) {
            visitQueryService.findCompletedByStudioIdAndPickupDateBetween(any(), any(), any())
        }
    }

    @Test
    fun `an enabled rule with no message queries nothing`() {
        givenConfig(postVisit = rule(enabled = true, template = "   "))

        scheduler.processPendingAutomations()

        verify(exactly = 0) {
            visitQueryService.findCompletedByStudioIdAndPickupDateBetween(any(), any(), any())
        }
    }

    @Test
    fun `pre-visit still reads appointments - the visit does not exist yet`() {
        givenConfig(preVisit = rule(enabled = true, template = "Do zobaczenia {{data}}"))

        scheduler.processPendingAutomations()

        verify {
            appointmentQueryService.findByStudioIdAndStartTimeBetween(studioId, any(), any())
        }
        verify(exactly = 0) {
            visitQueryService.findCompletedByStudioIdAndPickupDateBetween(any(), any(), any())
        }
    }

    private fun rule(enabled: Boolean, template: String, offsetMinutes: Int = 60) =
        SmsAutomationRule(enabled = enabled, offsetMinutes = offsetMinutes, messageTemplate = template)

    private val off = SmsAutomationRule(enabled = false, offsetMinutes = 60, messageTemplate = "")

    private fun givenConfig(
        preVisit: SmsAutomationRule = off,
        postVisit: SmsAutomationRule = off,
        delayedReminder: SmsAutomationRule = off
    ) {
        val config = SmsAutomationConfig.defaultFor(studioId).copy(
            preVisit = preVisit,
            postVisit = postVisit,
            delayedReminder = delayedReminder
        )
        every { configRepository.findAllWithAnyRuleEnabled() } returns listOf(config)
        every { configRepository.findByStudioId(any()) } returns config
        every {
            appointmentQueryService.findWithSendReminderAndStartTimeBetween(any(), any())
        } returns emptyList()
        every {
            appointmentQueryService.findByStudioIdAndStartTimeBetween(any(), any(), any())
        } returns emptyList()
        every {
            visitQueryService.findCompletedByStudioIdAndPickupDateBetween(any(), any(), any())
        } returns emptyList()
    }
}

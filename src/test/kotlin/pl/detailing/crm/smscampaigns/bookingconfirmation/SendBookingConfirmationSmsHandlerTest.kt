package pl.detailing.crm.smscampaigns.bookingconfirmation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.domain.SmsNotificationRule
import pl.detailing.crm.smscampaigns.domain.SmsTriggerType
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import pl.detailing.crm.smscampaigns.template.SmsTemplateProcessor
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import java.util.UUID

/**
 * One confirmation per booking. A duplicated create request (client retry, double
 * submit) reaches this handler twice; the second call must find the log row written by
 * the first and stop before touching the customer.
 */
class SendBookingConfirmationSmsHandlerTest {

    private val appointmentRepository: AppointmentRepository = mockk()
    private val customerRepository: CustomerRepository = mockk()
    private val gateway: OutboundCommunicationGateway = mockk()
    private val logService: CommunicationLogService = mockk(relaxed = true)
    private val configRepository: SmsAutomationConfigRepository = mockk()
    private val templateProcessor: SmsTemplateProcessor = mockk()
    private val smsLogRepository: SmsLogJpaRepository = mockk()

    private val handler = SendBookingConfirmationSmsHandler(
        appointmentRepository, customerRepository, gateway, logService, configRepository, templateProcessor, smsLogRepository
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val appointmentId = AppointmentId(UUID.randomUUID())

    private fun ruleEnabled() {
        every { configRepository.findByStudioId(studioId) } returns SmsAutomationConfig.defaultFor(studioId).copy(
            bookingConfirmation = SmsNotificationRule(true, "Cześć {{imie}}, {{data}} {{godzina}}")
        )
    }

    @Test
    fun `a confirmation already logged for this appointment is not sent again`() = runBlocking {
        ruleEnabled()
        every { smsLogRepository.existsByAppointmentIdAndTriggerType(appointmentId.value, SmsTriggerType.BOOKING_CONFIRMATION) } returns true

        handler.handle(SendBookingConfirmationSmsCommand(appointmentId, studioId, force = true))

        verify(exactly = 0) { appointmentRepository.findByIdAndStudioId(any(), any()) }
        verify(exactly = 0) { gateway.sendTransactionalSms(any(), any(), any(), any()) }
        verify(exactly = 0) { smsLogRepository.save(any()) }
    }

    @Test
    fun `the dedup check happens before any customer lookup, so it cannot be bypassed by force`() = runBlocking {
        ruleEnabled()
        every { smsLogRepository.existsByAppointmentIdAndTriggerType(any(), any()) } returns true

        handler.handle(SendBookingConfirmationSmsCommand(appointmentId, studioId, force = false))
        handler.handle(SendBookingConfirmationSmsCommand(appointmentId, studioId, force = true))

        verify(exactly = 0) { gateway.sendTransactionalSms(any(), any(), any(), any()) }
    }

    @Test
    fun `without a template nothing is sent and the log is not even consulted`() = runBlocking {
        every { configRepository.findByStudioId(studioId) } returns SmsAutomationConfig.defaultFor(studioId).copy(
            bookingConfirmation = SmsNotificationRule(true, "")
        )

        handler.handle(SendBookingConfirmationSmsCommand(appointmentId, studioId))

        verify(exactly = 0) { smsLogRepository.existsByAppointmentIdAndTriggerType(any(), any()) }
        verify(exactly = 0) { gateway.sendTransactionalSms(any(), any(), any(), any()) }
    }
}

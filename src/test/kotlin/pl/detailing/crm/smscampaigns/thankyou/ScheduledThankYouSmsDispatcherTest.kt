package pl.detailing.crm.smscampaigns.thankyou

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.smscampaigns.domain.SmsTriggerType
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogStatus
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSms
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsRepository
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsStatus
import java.time.Instant
import java.util.UUID

class ScheduledThankYouSmsDispatcherTest {

    private val repository: ScheduledThankYouSmsRepository = mockk(relaxed = true)
    private val communicationGateway: OutboundCommunicationGateway = mockk()
    private val communicationLogService: CommunicationLogService = mockk(relaxed = true)
    private val smsLogRepository: SmsLogJpaRepository = mockk(relaxed = true)

    private val dispatcher = ScheduledThankYouSmsDispatcher(
        repository,
        communicationGateway,
        communicationLogService,
        smsLogRepository
    )

    @BeforeEach
    fun setUp() {
        every { repository.findDueForDispatch(any()) } returns listOf(pending())
        every {
            communicationGateway.sendSms(any(), any(), any(), any(), any(), any())
        } returns SmsDeliveryResult.success("provider-42")
    }

    @Test
    fun `wysyla tresc zamrozona w chwili planowania`() {
        val message = slot<String>()
        every {
            communicationGateway.sendSms(any(), any(), any(), capture(message), any(), any())
        } returns SmsDeliveryResult.success("provider-42")

        dispatcher.dispatch()

        assertEquals("Dziękujemy za wizytę, Anno!", message.captured)
    }

    @Test
    fun `udana wysylka zamyka wpis statusem SENT`() {
        val saved = slot<ScheduledThankYouSms>()
        every { repository.save(capture(saved)) } answers { firstArg() }

        dispatcher.dispatch()

        assertEquals(ScheduledThankYouSmsStatus.SENT, saved.captured.status)
        assertEquals("provider-42", saved.captured.externalMessageId)
    }

    @Test
    fun `odmowa operatora konczy sie statusem FAILED, a nie kolejna proba`() {
        every {
            communicationGateway.sendSms(any(), any(), any(), any(), any(), any())
        } returns SmsDeliveryResult.failure("Brak zgody na komunikację SMS")
        val saved = slot<ScheduledThankYouSms>()
        every { repository.save(capture(saved)) } answers { firstArg() }

        dispatcher.dispatch()

        assertEquals(ScheduledThankYouSmsStatus.FAILED, saved.captured.status)
        assertEquals("Brak zgody na komunikację SMS", saved.captured.errorMessage)
    }

    @Test
    fun `brak kredytow nie wywraca calego przebiegu`() {
        every {
            communicationGateway.sendSms(any(), any(), any(), any(), any(), any())
        } throws InsufficientSmsCreditsException()
        val saved = slot<ScheduledThankYouSms>()
        every { repository.save(capture(saved)) } answers { firstArg() }

        dispatcher.dispatch()

        assertEquals(ScheduledThankYouSmsStatus.FAILED, saved.captured.status)
    }

    @Test
    fun `wyslane podziekowanie trafia do dziennika SMS pod triggerem POST_VISIT`() {
        // Dziennik rezerwacji ma pokazywać „SMS po wizycie" niezależnie od tego, czy
        // wysłał go automat, czy termin wybrany przy wydaniu pojazdu.
        val logged = slot<SmsLogEntity>()
        every { smsLogRepository.save(capture(logged)) } answers { firstArg() }

        dispatcher.dispatch()

        assertEquals(SmsTriggerType.POST_VISIT, logged.captured.triggerType)
        assertEquals(SmsLogStatus.SENT, logged.captured.status)
    }

    @Test
    fun `wysylka jest odnotowana w historii kontaktu z klientem`() {
        dispatcher.dispatch()

        verify {
            communicationLogService.record(
                match { it.messageType == CommunicationMessageType.SMS_AUTOMATION_POST_VISIT }
            )
        }
    }

    @Test
    fun `pusty wpis nie idzie do operatora`() {
        every { repository.findDueForDispatch(any()) } returns listOf(pending().copy(messageContent = null))
        val saved = slot<ScheduledThankYouSms>()
        every { repository.save(capture(saved)) } answers { firstArg() }

        dispatcher.dispatch()

        verify(exactly = 0) { communicationGateway.sendSms(any(), any(), any(), any(), any(), any()) }
        assertEquals(ScheduledThankYouSmsStatus.FAILED, saved.captured.status)
    }

    @Test
    fun `pusta kolejka nie rusza operatora`() {
        every { repository.findDueForDispatch(any()) } returns emptyList()

        dispatcher.dispatch()

        verify(exactly = 0) { communicationGateway.sendSms(any(), any(), any(), any(), any(), any()) }
    }

    private fun pending() = ScheduledThankYouSms(
        id = UUID.randomUUID(),
        studioId = UUID.randomUUID(),
        visitId = UUID.randomUUID(),
        appointmentId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        phoneNumber = "+48534920205",
        messageContent = "Dziękujemy za wizytę, Anno!",
        scheduledFor = Instant.now().minusSeconds(60),
        status = ScheduledThankYouSmsStatus.PENDING,
        sentAt = null,
        externalMessageId = null,
        errorMessage = null,
        createdBy = UUID.randomUUID(),
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )
}

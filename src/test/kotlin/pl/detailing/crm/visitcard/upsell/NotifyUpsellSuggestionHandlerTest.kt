package pl.detailing.crm.visitcard.upsell

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.DeliveryPolicy
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.domain.SmsNotificationRule
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visitcard.VisitCardProperties
import pl.detailing.crm.visitcard.VisitCardTokenService
import pl.detailing.crm.visitcard.upsell.infrastructure.UpsellSuggestionStatus
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionEntity
import java.time.Instant
import java.util.UUID

/**
 * SMS „Upselling" po dodaniu propozycji na Karcie Wizyty: informacja z linkiem, wysyłana
 * tylko na życzenie pracownika, a każda blokada wraca jako komunikat, nie wyjątek —
 * sugestia jest już zapisana i ma zostać.
 */
class NotifyUpsellSuggestionHandlerTest {

    private val visitRepository: VisitRepository = mockk()
    private val appointmentRepository: AppointmentRepository = mockk()
    private val customerRepository: CustomerRepository = mockk()
    private val configRepository: SmsAutomationConfigRepository = mockk()
    private val tokenService: VisitCardTokenService = mockk()
    private val gateway: OutboundCommunicationGateway = mockk()
    private val log: CommunicationLogService = mockk(relaxed = true)

    private val handler = NotifyUpsellSuggestionHandler(
        visitRepository, appointmentRepository, customerRepository, configRepository, MessageTemplateRenderer(),
        tokenService, VisitCardProperties(), gateway, log
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val visitId = VisitId(UUID.randomUUID())
    private val appointmentId = AppointmentId(UUID.randomUUID())
    private val customerId = UUID.randomUUID()

    private val suggestion = VisitUpsellSuggestionEntity(
        id = UUID.randomUUID(), studioId = studioId.value, visitId = visitId.value, appointmentId = null,
        serviceId = UUID.randomUUID(), serviceName = "Powłoka ceramiczna", basePriceNet = 100_000, vatRate = 23,
        adjustmentType = AdjustmentType.PERCENT, adjustmentValue = 0, finalPriceNet = 100_000, finalPriceGross = 123_000,
        note = null, createdBy = UUID.randomUUID()
    )

    private fun givenRule(enabled: Boolean = true, template: String = "{{imie}}, propozycja: {{uslugi}}. Zobacz: {{link}}") {
        every { configRepository.findByStudioId(studioId) } returns SmsAutomationConfig.defaultFor(studioId).copy(
            upsellSuggestion = SmsNotificationRule(enabled, template)
        )
    }

    private fun givenVisitAndCustomer(phone: String? = "534920205") {
        every { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns mockk<VisitEntity>(relaxed = true).also {
            every { it.customerId } returns customerId
            every { it.appointmentId } returns appointmentId.value
        }
        every { customerRepository.findByIdAndStudioId(customerId, studioId.value) } returns mockk<CustomerEntity>(relaxed = true).also {
            every { it.firstName } returns "Anna"
            every { it.lastName } returns "Kowalska"
            every { it.phone } returns phone
        }
        every { tokenService.getOrCreateToken(studioId, visitId, appointmentId) } returns "tok123"
    }

    @Test
    fun `wysyla SMS z nazwa uslugi i linkiem do karty, domyslna polityka okna wysylki`() {
        givenRule(); givenVisitAndCustomer()
        val message = slot<String>()
        every { gateway.sendSms(customerId, studioId.value, "+48534920205", capture(message), any(), any(), DeliveryPolicy.SEND_WINDOW) } returns
            SmsDeliveryResult.success("ext-1")

        val result = handler.notifyForVisit(visitId, studioId, suggestion)

        assertTrue(result.sent)
        assertFalse(result.queued)
        assertEquals("Anna, propozycja: Powłoka ceramiczna. Zobacz: https://detailboost.pl/vc/tok123", message.captured)
        val record = slot<RecordCommunicationCommand>()
        verify { log.record(capture(record)) }
        assertEquals(CommunicationMessageType.VISIT_CARD_UPSELL_SUGGESTION_SMS, record.captured.messageType)
        assertEquals(visitId, record.captured.visitId)
        assertEquals(true, record.captured.success)
    }

    @Test
    fun `poza oknem wysylki wraca jako zakolejkowany z terminem`() {
        givenRule(); givenVisitAndCustomer()
        val at = Instant.parse("2026-09-16T10:00:00Z")
        val queuedId = UUID.randomUUID()
        every { gateway.sendSms(any(), any(), any(), any(), any(), any(), any()) } returns SmsDeliveryResult.queued(queuedId, at)

        val result = handler.notifyForVisit(visitId, studioId, suggestion)

        assertTrue(result.sent)
        assertTrue(result.queued)
        assertEquals(at, result.scheduledFor)
        val record = slot<RecordCommunicationCommand>()
        verify { log.record(capture(record)) }
        assertEquals(queuedId, record.captured.queuedMessageId)
    }

    @Test
    fun `wylaczony szablon konczy sie komunikatem, bez wolania bramki`() {
        givenRule(enabled = false); givenVisitAndCustomer()

        val result = handler.notifyForVisit(visitId, studioId, suggestion)

        assertFalse(result.sent)
        assertTrue(result.message.contains("wyłączony"), result.message)
        verify(exactly = 0) { gateway.sendSms(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { log.record(any()) }
    }

    @Test
    fun `klient bez numeru dostaje czytelny powod`() {
        givenRule(); givenVisitAndCustomer(phone = null)

        val result = handler.notifyForVisit(visitId, studioId, suggestion)

        assertFalse(result.sent)
        assertTrue(result.message.contains("numeru telefonu"), result.message)
        verify(exactly = 0) { gateway.sendSms(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `brak modulu SMS wraca jako blad z bramki, zapisany w dzienniku jako FAILED`() {
        givenRule(); givenVisitAndCustomer()
        every { gateway.sendSms(any(), any(), any(), any(), any(), any(), any()) } returns
            SmsDeliveryResult.failure("Moduł 'Komunikacja' nie jest aktywny w tym studiu — wiadomość zablokowana")

        val result = handler.notifyForVisit(visitId, studioId, suggestion)

        assertFalse(result.sent)
        assertTrue(result.message.contains("nie jest aktywny"), result.message)
        val record = slot<RecordCommunicationCommand>()
        verify { log.record(capture(record)) }
        assertEquals(false, record.captured.success)
    }

    @Test
    fun `brak kredytow nie rzuca wyjatkiem - sugestia ma zostac`() {
        givenRule(); givenVisitAndCustomer()
        every { gateway.sendSms(any(), any(), any(), any(), any(), any(), any()) } throws InsufficientSmsCreditsException("Brak kredytów SMS")

        val result = handler.notifyForVisit(visitId, studioId, suggestion)

        assertFalse(result.sent)
        assertTrue(result.message.contains("kredytów"), result.message)
        val record = slot<RecordCommunicationCommand>()
        verify { log.record(capture(record)) }
        assertEquals(false, record.captured.success)
    }

    @Test
    fun `dla rezerwacji link prowadzi do karty rezerwacji`() {
        givenRule()
        every { appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value) } returns mockk<AppointmentEntity>(relaxed = true).also {
            every { it.customerId } returns customerId
        }
        every { customerRepository.findByIdAndStudioId(customerId, studioId.value) } returns mockk<CustomerEntity>(relaxed = true).also {
            every { it.firstName } returns "Anna"; every { it.lastName } returns "Kowalska"; every { it.phone } returns "534920205"
        }
        every { tokenService.getOrCreateTokenForAppointment(studioId, appointmentId) } returns "res456"
        val message = slot<String>()
        every { gateway.sendSms(any(), any(), any(), capture(message), any(), any(), any()) } returns SmsDeliveryResult.success("x")

        val result = handler.notifyForAppointment(appointmentId, studioId, suggestion)

        assertTrue(result.sent)
        assertTrue(message.captured.endsWith("/vc/res456"), message.captured)
        val record = slot<RecordCommunicationCommand>()
        verify { log.record(capture(record)) }
        assertEquals(appointmentId, record.captured.appointmentId)
        assertEquals(null, record.captured.visitId)
    }
}

package pl.detailing.crm.communication

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditActorResolver
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.communication.infrastructure.CommunicationLogEntity
import pl.detailing.crm.communication.infrastructure.CommunicationLogJpaRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * Dziennik komunikacji ma mówić prawdę: wiadomość odłożona na okno to QUEUED, nie SENT,
 * a po faktycznej wysyłce TEN SAM wpis zmienia się w SENT albo FAILED. Aktywność dostaje
 * osobne zdarzenie przy odłożeniu i osobne przy wysyłce.
 */
class CommunicationLogServiceQueuedTest {

    private val repository: CommunicationLogJpaRepository = mockk(relaxed = true)
    private val auditService: AuditService = mockk(relaxed = true)
    private val auditActorResolver: AuditActorResolver = mockk { every { current(any()) } returns AuditActor.system() }
    private val customerRepository: CustomerRepository = mockk { every { findByIdAndStudioId(any(), any()) } returns null }
    private val service = CommunicationLogService(repository, auditService, auditActorResolver, customerRepository)

    private val studioId = StudioId(UUID.randomUUID())
    private val customerId = CustomerId(UUID.randomUUID())

    private fun command(success: Boolean, queuedMessageId: UUID? = null, status: CommunicationStatus? = null, channel: CommunicationChannel = CommunicationChannel.SMS) =
        RecordCommunicationCommand(
            studioId = studioId, customerId = customerId, visitId = null, channel = channel,
            messageType = CommunicationMessageType.VISIT_READY_FOR_PICKUP_SMS, recipientAddress = "+48600700800",
            subject = null, bodyContent = "Auto gotowe", success = success, errorMessage = null,
            status = status, queuedMessageId = queuedMessageId
        )

    private fun savedEntity(): CommunicationLogEntity {
        val entity = slot<CommunicationLogEntity>()
        verify { repository.saveAndFlush(capture(entity)) }
        return entity.captured
    }

    private fun auditedAction(): AuditAction {
        val event = slot<AuditEvent>()
        verify { auditService.recordSync(capture(event)) }
        return event.captured.action
    }

    // ── record ───────────────────────────────────────────────────────────────

    @Test
    fun `odlozona wiadomosc jest QUEUED i pamieta wiersz kolejki`() {
        val queuedId = UUID.randomUUID()

        service.record(command(success = true, queuedMessageId = queuedId))

        val entity = savedEntity()
        assertEquals(CommunicationStatus.QUEUED, entity.status)
        assertEquals(queuedId, entity.queuedMessageId)
        assertEquals(AuditAction.SMS_QUEUED, auditedAction())
    }

    @Test
    fun `odlozony e-mail dostaje wlasne zdarzenie w aktywnosci`() {
        service.record(command(success = true, queuedMessageId = UUID.randomUUID(), channel = CommunicationChannel.EMAIL))
        assertEquals(AuditAction.EMAIL_QUEUED, auditedAction())
    }

    @Test
    fun `wyslana od reki wiadomosc jest SENT bez odniesienia do kolejki`() {
        service.record(command(success = true))

        val entity = savedEntity()
        assertEquals(CommunicationStatus.SENT, entity.status)
        assertEquals(null, entity.queuedMessageId)
        assertEquals(AuditAction.SMS_SENT, auditedAction())
    }

    @Test
    fun `jawny status wygrywa z wnioskowaniem`() {
        service.record(command(success = true, queuedMessageId = UUID.randomUUID(), status = CommunicationStatus.FAILED))
        assertEquals(CommunicationStatus.FAILED, savedEntity().status)
    }

    // ── recordQueuedOutcome ──────────────────────────────────────────────────

    private fun queuedEntry(queuedId: UUID) = CommunicationLogEntity(
        id = UUID.randomUUID(), studioId = studioId.value, customerId = customerId.value, visitId = null, appointmentId = null,
        channel = CommunicationChannel.SMS, messageType = CommunicationMessageType.VISIT_READY_FOR_PICKUP_SMS,
        recipientAddress = "+48600700800", subject = null, bodyContent = "Auto gotowe",
        status = CommunicationStatus.QUEUED, errorMessage = null, sentAt = Instant.now(), queuedMessageId = queuedId
    )

    @Test
    fun `faktyczna wysylka domyka wpis QUEUED jako SENT i loguje wyslanie w aktywnosci`() {
        val queuedId = UUID.randomUUID()
        every { repository.findAllByQueuedMessageId(queuedId) } returns listOf(queuedEntry(queuedId))

        service.recordQueuedOutcome(queuedId, success = true, errorMessage = null)

        verify { repository.resolveQueued(queuedId, CommunicationStatus.QUEUED, CommunicationStatus.SENT, null, any()) }
        assertEquals(AuditAction.SMS_SENT, auditedAction())
    }

    @Test
    fun `ostateczna porazka domyka wpis jako FAILED z powodem`() {
        val queuedId = UUID.randomUUID()
        every { repository.findAllByQueuedMessageId(queuedId) } returns listOf(queuedEntry(queuedId))

        service.recordQueuedOutcome(queuedId, success = false, errorMessage = "SMSAPI 500")

        verify { repository.resolveQueued(queuedId, CommunicationStatus.QUEUED, CommunicationStatus.FAILED, "SMSAPI 500", any()) }
        assertEquals(AuditAction.SMS_FAILED, auditedAction())
    }

    @Test
    fun `wynik bez wpisu w dzienniku niczego nie zapisuje i nie wywraca dispatchera`() {
        val queuedId = UUID.randomUUID()
        every { repository.findAllByQueuedMessageId(queuedId) } returns emptyList()

        service.recordQueuedOutcome(queuedId, success = true, errorMessage = null)

        verify(exactly = 0) { repository.resolveQueued(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { auditService.recordSync(any()) }
    }

    @Test
    fun `blad bazy przy domykaniu jest logowany, nie propagowany`() {
        val queuedId = UUID.randomUUID()
        every { repository.findAllByQueuedMessageId(queuedId) } throws IllegalStateException("db down")

        service.recordQueuedOutcome(queuedId, success = true, errorMessage = null)
    }
}

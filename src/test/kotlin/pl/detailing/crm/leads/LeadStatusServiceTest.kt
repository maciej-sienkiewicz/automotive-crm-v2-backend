package pl.detailing.crm.leads

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import pl.detailing.crm.leads.domain.LeadLostReason
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryEntity
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.leads.update.LeadStatusService
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

class LeadStatusServiceTest {

    private val leadRepository = mockk<LeadRepository> {
        every { save(any()) } answers { firstArg() }
    }
    private val historyRepository = mockk<LeadStatusHistoryRepository> {
        every { save(any()) } answers { firstArg() }
    }
    private val eventPublisher = mockk<ApplicationEventPublisher> {
        every { publishEvent(any()) } just Runs
    }
    private val service = LeadStatusService(leadRepository, historyRepository, eventPublisher)

    private fun lead(status: LeadStatus = LeadStatus.NEW) = LeadEntity(
        id = UUID.randomUUID(),
        studioId = UUID.randomUUID(),
        source = LeadSource.EMAIL,
        status = status,
        contactIdentifier = "klient@example.com",
        customerName = null,
        initialMessage = null,
        estimatedValue = 0,
        requiresVerification = false,
        vehicleBrand = null,
        vehicleModel = null,
        customerId = null,
        appointmentId = null,
        visitId = null,
        assignedUserId = null,
        assignedUserName = null,
        lostReason = null,
        stagnantAlertSentAt = null
    )

    @Test
    fun `losing a lead requires a dictionary reason`() {
        assertThrows(ValidationException::class.java) {
            service.transition(lead(), LeadStatus.LOST)
        }
    }

    @Test
    fun `terminal transition stamps closedAt and appends history`() {
        val entity = lead(LeadStatus.IN_PROGRESS)
        val history = slot<LeadStatusHistoryEntity>()
        every { historyRepository.save(capture(history)) } answers { firstArg() }

        service.transition(entity, LeadStatus.LOST, lostReasonCode = LeadLostReason.TOO_EXPENSIVE)

        assertEquals(LeadStatus.LOST, entity.status)
        assertEquals(LeadLostReason.TOO_EXPENSIVE, entity.lostReasonCode)
        assertNotNull(entity.closedAt)
        assertEquals(LeadStatus.IN_PROGRESS, history.captured.fromStatus)
        assertEquals(LeadStatus.LOST, history.captured.toStatus)
        assertEquals(LeadLostReason.TOO_EXPENSIVE, history.captured.lostReasonCode)
    }

    @Test
    fun `reopening clears closedAt`() {
        val entity = lead(LeadStatus.LOST).apply { closedAt = java.time.Instant.now() }
        service.transition(entity, LeadStatus.IN_PROGRESS)
        assertNull(entity.closedAt)
    }

    @Test
    fun `same status transition is a no-op`() {
        val entity = lead(LeadStatus.NEW)
        service.transition(entity, LeadStatus.NEW)
        verify(exactly = 0) { historyRepository.save(any()) }
    }
}

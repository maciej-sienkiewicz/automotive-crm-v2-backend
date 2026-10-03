package pl.detailing.crm.visit.transitions.cancel

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.signing.SignatureRequestLifecycleService
import pl.detailing.crm.visit.infrastructure.S3DamageMapStorageService
import pl.detailing.crm.visit.infrastructure.VisitDocumentRepository
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitJournalEntryRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.util.UUID

/**
 * Zgłoszenie biznesu z 03.10: „Wizyta" w kalendarzu → formularz → „Utwórz wizytę" →
 * „Przerwij przyjęcie" → „Anuluj wizytę", a rezerwacja i tak zostawała w kalendarzu.
 * Walk-in zakłada w tle rezerwację-cień; anulowanie szkicu musi ją zdjąć. Rezerwacja
 * z kalendarza (przyjęcie z prawdziwej rezerwacji) zostaje - auto można przyjąć od nowa.
 */
class CancelDraftVisitHandlerWalkInTest {

    private val studioId = StudioId.random()
    private val visitId = VisitId.random()
    private val appointmentId = UUID.randomUUID()
    private val userId = UserId.random()

    private val visitRepository = mockk<VisitRepository>(relaxed = true)
    private val protocols = mockk<VisitProtocolRepository>(relaxed = true)
    private val documents = mockk<VisitDocumentRepository>(relaxed = true)
    private val journal = mockk<VisitJournalEntryRepository>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val appointments = mockk<AppointmentRepository>(relaxed = true)
    private val transactions = mockk<TransactionTemplate> {
        every { execute(any<TransactionCallback<Any>>()) } answers {
            firstArg<TransactionCallback<Any>>().doInTransaction(mockk(relaxed = true))
        }
    }

    private val handler = CancelDraftVisitHandler(
        visitRepository, protocols, documents, journal,
        mockk<S3ProtocolStorageService>(relaxed = true), mockk<S3DamageMapStorageService>(relaxed = true),
        transactions, audit, mockk<SignatureRequestLifecycleService>(relaxed = true), appointments,
    )

    init {
        val draft = mockk<VisitEntity>(relaxed = true) {
            every { status } returns VisitStatus.DRAFT
            every { visitNumber } returns "1060/26"
            every { damageMapFileId } returns null
            every { appointmentId } returns this@CancelDraftVisitHandlerWalkInTest.appointmentId
        }
        every { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns draft
        every { protocols.findAllByVisitIdAndStudioId(any(), any()) } returns emptyList()
        every { documents.findByVisit_IdOrderByUploadedAtDesc(any()) } returns emptyList()
        every { journal.findByVisitId(any()) } returns emptyList()
        coEvery { audit.record(any()) } returns Unit
        every { appointments.save(any<AppointmentEntity>()) } answers { firstArg() }
    }

    private fun appointment(walkIn: Boolean) = AppointmentEntity(
        id = appointmentId,
        studioId = studioId.value,
        customerId = UUID.randomUUID(),
        vehicleId = UUID.randomUUID(),
        appointmentTitle = "Mycie",
        appointmentColorId = UUID.randomUUID(),
        isAllDay = false,
        startDateTime = java.time.Instant.parse("2026-10-03T08:00:00Z"),
        endDateTime = java.time.Instant.parse("2026-10-03T10:00:00Z"),
        status = AppointmentStatus.CREATED,
        note = null,
        createdBy = UUID.randomUUID(),
        updatedBy = UUID.randomUUID(),
        walkIn = walkIn,
    )

    private fun cancel() = runBlocking {
        handler.handle(CancelDraftVisitCommand(visitId = visitId, studioId = studioId, userId = userId, userName = "Anna"))
    }

    @Test
    fun `anulowanie walk-inu usuwa jego rezerwacje-cien z kalendarza`() {
        val shadow = appointment(walkIn = true)
        every { appointments.findByIdAndStudioId(appointmentId, studioId.value) } returns shadow

        val result = cancel()

        assertNotNull(shadow.deletedAt, "rezerwacja-cień ma zniknąć z kalendarza")
        assertEquals(userId.value, shadow.updatedBy)
        verify(exactly = 1) { appointments.save(shadow) }
        assertFalse(result.reservationKept)
    }

    @Test
    fun `anulowanie przyjecia z rezerwacji zostawia rezerwacje w kalendarzu`() {
        val booked = appointment(walkIn = false)
        every { appointments.findByIdAndStudioId(appointmentId, studioId.value) } returns booked

        val result = cancel()

        assertNull(booked.deletedAt)
        assertEquals(AppointmentStatus.CREATED, booked.status)
        verify(exactly = 0) { appointments.save(any()) }
        assertTrue(result.reservationKept)
    }

    @Test
    fun `szkic sprzatany przez system tez zabiera rezerwacje-cien`() {
        val shadow = appointment(walkIn = true)
        every { appointments.findByIdAndStudioId(appointmentId, studioId.value) } returns shadow

        runBlocking {
            handler.handle(CancelDraftVisitCommand(visitId = visitId, studioId = studioId, userId = null, reason = "stale"))
        }

        assertNotNull(shadow.deletedAt)
    }
}

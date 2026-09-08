package pl.detailing.crm.visit.transitions.complete

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.finance.document.CreateFinancialDocumentHandler
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
import pl.detailing.crm.visit.domain.VisitFixtures
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.util.UUID

/**
 * Powtórzone „Wydaj pojazd" na wizycie już zakończonej.
 *
 * Tu stawka jest wyższa niż czerwony toast: drugie wystawienie dokumentu finansowego
 * do tej samej wizyty to podwójny paragon w księgowości. Idempotencja ma zwrócić
 * dokument z PIERWSZEGO wydania, nie wystawić kolejnego.
 */
class CompleteVisitIdempotencyTest {

    private val visitRepository: VisitRepository = mockk()
    private val customerRepository: CustomerRepository = mockk { coEvery { findByIdAndStudioId(any(), any()) } returns null }
    private val auditService: AuditService = mockk(relaxed = true)
    private val createFinancialDocumentHandler: CreateFinancialDocumentHandler = mockk()
    private val capabilityService: CapabilityService = mockk(relaxed = true) {
        every { hasCapability(any(), any()) } returns true
    }
    private val financialDocumentRepository: FinancialDocumentRepository = mockk()

    private val handler = CompleteVisitHandler(
        visitRepository, customerRepository, auditService, createFinancialDocumentHandler,
        capabilityService, mockk(relaxed = true), financialDocumentRepository
    )

    private val studioId = StudioId.random()
    private val userId = UserId.random()

    private fun givenVisitInStatus(status: VisitStatus): VisitId {
        val visit = VisitFixtures.visit(studioId = studioId, status = status)
        every { visitRepository.findByIdAndStudioIdWithPhotos(visit.id.value, studioId.value) } returns
            VisitEntity.fromDomain(visit)
        every { visitRepository.save(any()) } answers { firstArg() }
        every { financialDocumentRepository.findAllByVisitIdAndStudioIdAndDeletedAtIsNull(any(), any()) } returns emptyList()
        return visit.id
    }

    private fun command(visitId: VisitId) = CompleteVisitCommand(
        studioId = studioId, userId = userId, visitId = visitId, userName = "Anna Kowalska",
        paymentMethod = PaymentMethod.CASH, documentType = DocumentType.RECEIPT
    )

    @Test
    fun `wizyta juz zakonczona konczy sie sukcesem i nie wystawia drugiego dokumentu`() = runBlocking {
        val visitId = givenVisitInStatus(VisitStatus.COMPLETED)

        val result = handler.handle(command(visitId))

        assertTrue(result.alreadyInTargetState)
        assertEquals(VisitStatus.COMPLETED, result.newStatus)
        verify(exactly = 0) { createFinancialDocumentHandler.handle(any()) }
        verify(exactly = 0) { visitRepository.save(any()) }
        coVerify(exactly = 0) { auditService.log(any()) }
    }

    @Test
    fun `powtorka zwraca numer dokumentu z pierwszego wydania`() = runBlocking {
        val visitId = givenVisitInStatus(VisitStatus.COMPLETED)
        val documentId = UUID.randomUUID()
        every { financialDocumentRepository.findAllByVisitIdAndStudioIdAndDeletedAtIsNull(visitId.value, studioId.value) } returns
            listOf(mockk<FinancialDocumentEntity>(relaxed = true).also {
                every { it.id } returns documentId
                every { it.documentNumber } returns "PAR/2026/0042"
            })

        val result = handler.handle(command(visitId))

        assertEquals("PAR/2026/0042", result.financialDocumentNumber)
        assertEquals(documentId, result.financialDocumentId?.value)
    }

    @Test
    fun `pierwsze wydanie dziala jak dotad`() = runBlocking {
        val visitId = givenVisitInStatus(VisitStatus.READY_FOR_PICKUP)
        every { createFinancialDocumentHandler.handle(any()) } returns mockk(relaxed = true)

        val result = handler.handle(command(visitId))

        assertFalse(result.alreadyInTargetState)
        assertEquals(VisitStatus.COMPLETED, result.newStatus)
        verify(exactly = 1) { visitRepository.save(any()) }
    }
}

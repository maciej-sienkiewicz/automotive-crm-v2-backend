package pl.detailing.crm.visit.transitions.cancel

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
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

/**
 * Zgłoszenie z 29.09: pracownik anulował szkic wizyty w oknie dokumentów przyjęcia
 * („Przerwać przyjęcie pojazdu?" → „Anuluj wizytę"), a jej protokoły zostały na
 * tablecie. Klient dostał do podpisu dokument wizyty, której już nie było, i każda
 * próba kończyła się „Wizyta nie została znaleziona". Anulowanie szkicu musi zdjąć
 * z tabletu żądania podpisu tej wizyty.
 */
class CancelDraftVisitHandlerSignatureTest {

    private val studioId = StudioId.random()
    private val visitId = VisitId.random()

    private val visitRepository = mockk<VisitRepository>(relaxed = true)
    private val protocols = mockk<VisitProtocolRepository>(relaxed = true)
    private val documents = mockk<VisitDocumentRepository>(relaxed = true)
    private val journal = mockk<VisitJournalEntryRepository>(relaxed = true)
    private val protocolStorage = mockk<S3ProtocolStorageService>(relaxed = true)
    private val damageMapStorage = mockk<S3DamageMapStorageService>(relaxed = true)
    private val audit = mockk<AuditService>(relaxed = true)
    private val signatures = mockk<SignatureRequestLifecycleService>(relaxed = true)
    private val transactions = mockk<TransactionTemplate> {
        every { execute(any<TransactionCallback<Any>>()) } answers {
            firstArg<TransactionCallback<Any>>().doInTransaction(mockk(relaxed = true))
        }
    }

    private val handler = CancelDraftVisitHandler(
        visitRepository, protocols, documents, journal, protocolStorage, damageMapStorage,
        transactions, audit, signatures, mockk(relaxed = true),
    )

    init {
        val draft = mockk<VisitEntity>(relaxed = true) {
            every { status } returns VisitStatus.DRAFT
            every { visitNumber } returns "1054/26"
            every { damageMapFileId } returns null
        }
        every { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns draft
        every { protocols.findAllByVisitIdAndStudioId(visitId.value, studioId.value) } returns emptyList()
        every { documents.findByVisit_IdOrderByUploadedAtDesc(visitId.value) } returns emptyList()
        every { journal.findByVisitId(visitId.value) } returns emptyList()
        coEvery { audit.record(any()) } returns Unit
    }

    private fun command() = CancelDraftVisitCommand(
        visitId = visitId, studioId = studioId, userId = UserId.random(), userName = "Martyna Niemier",
    )

    @Test
    fun `anulowanie szkicu anuluje zadania podpisu tej wizyty`() {
        runBlocking { handler.handle(command()) }

        verify(exactly = 1) {
            signatures.cancelActiveForVisit(studioId, visitId.value, "Martyna Niemier")
        }
    }

    @Test
    fun `blad anulowania zadan nie cofa anulowania wizyty`() {
        // Kolejka tabletu i tak odrzuci żądania wizyty, której nie ma (withoutOrphaned).
        every { signatures.cancelActiveForVisit(any(), any(), any()) } throws IllegalStateException("redis down")

        val result = runBlocking { handler.handle(command()) }

        assertEquals(visitId, result.visitId)
        verify { visitRepository.delete(any()) }
    }
}

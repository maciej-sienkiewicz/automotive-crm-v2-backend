package pl.detailing.crm.signing

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitProtocolId
import pl.detailing.crm.signing.domain.SignatureRequestStatus
import pl.detailing.crm.signing.domain.SignatureSubject
import pl.detailing.crm.signing.infrastructure.DocumentIntegrityService
import pl.detailing.crm.signing.infrastructure.SignatureAuditTrailService
import pl.detailing.crm.signing.infrastructure.SignatureEventPublisher
import pl.detailing.crm.signing.infrastructure.SignatureRequestEntity
import pl.detailing.crm.signing.infrastructure.SignatureRequestRepository
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository

/**
 * Zgłoszenie z 29.09: szkic wizyty usunięty (przyjęcie porzucone i zaczęte od nowa
 * z rezerwacji), a jego żądania podpisu zostały aktywne. Tablet podaje kolejkę od
 * najstarszego, więc klient dostawał protokół skasowanej wizyty i każda próba
 * podpisu kończyła się „Wizyta nie została znaleziona" - przez 15 minut, aż żądania
 * wygasły. Dokumenty nowej wizyty czekały za nimi.
 */
class OrphanedSignatureRequestsTest {

    private val repository = mockk<SignatureRequestRepository>(relaxed = true)
    private val integrity = mockk<DocumentIntegrityService>(relaxed = true)
    private val auditTrail = mockk<SignatureAuditTrailService>(relaxed = true)
    private val events = mockk<SignatureEventPublisher>(relaxed = true)
    private val visits = mockk<VisitRepository>()

    private val service = SignatureRequestLifecycleService(repository, integrity, auditTrail, events, visits)

    private val studioId = StudioId.random()

    init {
        every { repository.save(any<SignatureRequestEntity>()) } answers { firstArg() }
    }

    private fun requestFor(visitId: VisitId): SignatureRequestEntity {
        val entity = SignatureRequestEntity.fromDomain(
            signatureRequest(
                subject = SignatureSubject.VisitProtocol(visitId, VisitProtocolId.random()),
                studioId = studioId,
            )
        )
        every { repository.findByIdAndStudioId(entity.id, studioId.value) } returns entity
        return entity
    }

    private fun existingVisit(id: VisitId): VisitEntity = mockk { every { this@mockk.id } returns id.value }

    @Test
    fun `usuniecie wizyty anuluje jej aktywne zadania podpisu i zdejmuje je z tabletu`() {
        val deleted = VisitId.random()
        val first = requestFor(deleted)
        val second = requestFor(deleted)
        every { repository.findActiveForVisit(studioId.value, deleted.value) } returns listOf(first, second)

        val cancelled = service.cancelActiveForVisit(studioId, deleted.value, "Martyna Niemier")

        assertEquals(2, cancelled)
        verify {
            repository.save(match { it.id == first.id && it.status == SignatureRequestStatus.CANCELLED })
            repository.save(match { it.id == second.id && it.status == SignatureRequestStatus.CANCELLED })
        }
        // Tablet dostaje SIGNATURE_CANCELLED i dokument znika z ekranu od razu.
        verify(exactly = 2) {
            events.publish(any(), any(), "SIGNATURE_CANCELLED", any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `kolejka tabletu pomija zadania usunietej wizyty, a dokumenty nowej zostaja`() {
        val deleted = VisitId.random()
        val current = VisitId.random()
        val orphanFirst = requestFor(deleted)
        val orphanSecond = requestFor(deleted)
        val fresh = requestFor(current)
        every { visits.findAllById(setOf(deleted.value, current.value)) } returns listOf(existingVisit(current))

        val queue = service.withoutOrphaned(listOf(orphanFirst, orphanSecond, fresh))

        // Na czele kolejki stoi teraz protokół nowej wizyty, nie skasowanej.
        assertEquals(listOf(fresh.id), queue.map { it.id })
        verify {
            repository.save(match { it.id == orphanFirst.id && it.status == SignatureRequestStatus.CANCELLED })
            repository.save(match { it.id == orphanSecond.id && it.status == SignatureRequestStatus.CANCELLED })
        }
    }

    @Test
    fun `kolejka bez osieroconych zadan zostaje nietknieta`() {
        val current = VisitId.random()
        val request = requestFor(current)
        every { visits.findAllById(setOf(current.value)) } returns listOf(existingVisit(current))

        assertEquals(listOf(request.id), service.withoutOrphaned(listOf(request)).map { it.id })
        verify(exactly = 0) { repository.save(any<SignatureRequestEntity>()) }
    }
}

package pl.detailing.crm.signing

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitProtocolId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.signing.domain.SignatureRequestStatus
import pl.detailing.crm.signing.domain.SignatureSubject
import pl.detailing.crm.signing.infrastructure.SignatureRequestEntity
import pl.detailing.crm.signing.infrastructure.SignatureRequestRepository
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

/**
 * Kolejka podpisów tabletu na prawdziwym Postgresie - odtworzenie zgłoszenia z 29.09.
 *
 * Szkic wizyty A (przyjęcie bez rezerwacji) wysłał dwa protokoły na tablet i został
 * anulowany. Z rezerwacji powstała wizyta B z dwoma nowymi protokołami. Tablet bierze
 * z kolejki najstarsze żądanie, więc dostawał protokół A - wizyty, której już nie
 * było - i każda próba podpisu kończyła się „Wizyta nie została znaleziona".
 *
 * Testy z atrapami (OrphanedSignatureRequestsTest) nie widzą samych zapytań: kolejności
 * kolejki, filtrów statusu i studia. Tu idą przez JPQL na prawdziwej bazie.
 *
 * `@Tag("testcontainers")`: wymaga Dockera, wyłączony z domyślnego `./gradlew test`;
 * uruchom `./gradlew test -PrunTestcontainers`. W środowisku, w którym powstał, Dockera
 * nie było - treść testu sprawdzono na lokalnym Postgresie 16 (ta sama klasa, źródło
 * danych podmienione na czas uruchomienia).
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class SignatureQueuePersistenceTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired lateinit var entityManager: TestEntityManager
    @Autowired lateinit var requests: SignatureRequestRepository
    @Autowired lateinit var visits: VisitRepository

    private val studioId = StudioId.random()
    private val tabletId = "tablet-recepcja"
    private val t0 = Instant.parse("2026-09-29T11:36:51Z")

    private fun service() = SignatureRequestLifecycleService(
        requests, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), visits,
    )

    private fun persistVisit(id: VisitId, studio: StudioId = studioId): VisitEntity =
        entityManager.persistAndFlush(
            VisitEntity(
                id = id.value, studioId = studio.value, visitNumber = "1054/26",
                customerId = UUID.randomUUID(), vehicleId = UUID.randomUUID(), appointmentId = UUID.randomUUID(),
                appointmentColorId = null, title = null, brandSnapshot = "Audi", modelSnapshot = "A6 Avant",
                licensePlateSnapshot = null, vinSnapshot = null, yearOfProductionSnapshot = null, colorSnapshot = null,
                status = VisitStatus.DRAFT, scheduledDate = t0, estimatedCompletionDate = null,
                actualCompletionDate = null, pickupDate = null, mileageAtArrival = null, keysHandedOver = true,
                documentsHandedOver = true, inspectionNotes = null, technicalNotes = null,
                isHandedOffByOtherPerson = null, contactPersonFirstName = null, contactPersonLastName = null,
                contactPersonPhone = null, contactPersonEmail = null, damageMapFileId = null,
                createdBy = UUID.randomUUID(), updatedBy = UUID.randomUUID(),
            )
        )

    private fun request(visitId: VisitId, createdAt: Instant, studio: StudioId = studioId): SignatureRequestEntity =
        requests.saveAndFlush(
            SignatureRequestEntity.fromDomain(
                signatureRequest(
                    subject = SignatureSubject.VisitProtocol(visitId, VisitProtocolId.random()),
                    studioId = studio,
                    now = createdAt,
                )
            )
        )

    private fun queue(now: Instant = t0.plusSeconds(180)) =
        requests.findActiveForTablet(studioId.value, tabletId, now)

    @Test
    fun `protokoly anulowanej wizyty staly na czele kolejki, po poprawce tablet dostaje nowa wizyte`() {
        val deleted = VisitId.random()                  // szkic A - usunięty, w bazie go nie ma
        val current = VisitId.random().also { persistVisit(it) }
        val a1 = request(deleted, t0)
        val a2 = request(deleted, t0.plusSeconds(1))
        val b1 = request(current, t0.plusSeconds(126))
        val b2 = request(current, t0.plusSeconds(127))

        // Przyczyna zgłoszenia: kolejka od najstarszego - protokół A pierwszy.
        assertEquals(listOf(a1.id, a2.id, b1.id, b2.id), queue().map { it.id })

        val served = service().withoutOrphaned(queue())
        entityManager.flush(); entityManager.clear()

        assertEquals(listOf(b1.id, b2.id), served.map { it.id })
        assertEquals(SignatureRequestStatus.CANCELLED, requests.findById(a1.id).get().status)
        assertEquals(SignatureRequestStatus.CANCELLED, requests.findById(a2.id).get().status)
        // Anulowane żądania wypadają z kolejki na stałe, nie tylko z jednej odpowiedzi.
        assertEquals(listOf(b1.id, b2.id), queue().map { it.id })
    }

    @Test
    fun `anulowanie wizyty zdejmuje tylko jej aktywne zadania, w tym studiu`() {
        val deleted = VisitId.random()
        val current = VisitId.random().also { persistVisit(it) }
        val a1 = request(deleted, t0)
        val a2 = request(deleted, t0.plusSeconds(1))
        val b1 = request(current, t0.plusSeconds(126))
        // To samo id wizyty w innym studiu nie może zostać ruszone.
        val otherStudio = StudioId.random()
        val foreign = request(deleted, t0, studio = otherStudio)

        val cancelled = service().cancelActiveForVisit(studioId, deleted.value, "Martyna Niemier")
        entityManager.flush(); entityManager.clear()

        assertEquals(2, cancelled)
        assertEquals(SignatureRequestStatus.CANCELLED, requests.findById(a1.id).get().status)
        assertEquals(SignatureRequestStatus.CANCELLED, requests.findById(a2.id).get().status)
        assertEquals(SignatureRequestStatus.PENDING_DISPLAY, requests.findById(b1.id).get().status)
        assertEquals(SignatureRequestStatus.PENDING_DISPLAY, requests.findById(foreign.id).get().status)
        assertEquals(listOf(b1.id), queue().map { it.id })
    }
}

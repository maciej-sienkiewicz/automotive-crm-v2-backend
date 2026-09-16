package pl.detailing.crm.visit.damagemap

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.checkin.qr.CheckinDamagePointsService
import pl.detailing.crm.checkin.qr.CheckinPhotoService
import pl.detailing.crm.checkin.qr.DamagePointData
import pl.detailing.crm.checkin.qr.DamagePointsResult
import pl.detailing.crm.checkin.qr.FinalizedCheckinPhoto
import pl.detailing.crm.checkin.qr.GeneratedUploadToken
import pl.detailing.crm.checkin.qr.UploadContextTokenService
import pl.detailing.crm.checkin.qr.UploadSessionPurpose
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.visit.domain.DamagePhoto
import pl.detailing.crm.visit.domain.DamagePoint
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitPhotoEntity
import pl.detailing.crm.visit.infrastructure.VisitPhotoRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

/**
 * Telefon jako narzędzie do mapy uszkodzeń otwartej wizyty.
 *
 * Dwie rzeczy są tu nieoczywiste i obie potrafią po cichu zgubić pracę operatora:
 *  - zasiew sesji musi PRZENIEŚĆ zdjęcia już przypięte do punktów (telefon odsyła
 *    pełną mapę, więc czego nie dostanie, tego nie odda),
 *  - przeniesienie zdjęcia do galerii wizyty zmienia jego identyfikator, a punkty
 *    wskazują zdjęcia właśnie po nim.
 */
class VisitDamageMapMobileServiceTest {

    private val visitRepository: VisitRepository = mockk()
    private val visitPhotoRepository: VisitPhotoRepository = mockk(relaxed = true)
    private val tokenService: UploadContextTokenService = mockk()
    private val damagePointsService: CheckinDamagePointsService = mockk()
    private val checkinPhotoService: CheckinPhotoService = mockk()
    private val photoSessionService: PhotoSessionService = mockk()

    private val service = VisitDamageMapMobileService(
        visitRepository, visitPhotoRepository, tokenService,
        damagePointsService, checkinPhotoService, photoSessionService
    )

    private val studioId = StudioId(UUID.randomUUID())
    private val visitId = VisitId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())

    private val tenantId get() = studioId.value.toString()
    private val checkinId get() = visitId.value.toString()

    private val existingPhotoId = UUID.randomUUID()
    private val existingPhotoKey = "studio/visits/v/photos/$existingPhotoId"

    private fun visit(
        status: VisitStatus = VisitStatus.IN_PROGRESS,
        withPhotos: Boolean = true
    ): VisitEntity {
        val photo = mockk<VisitPhotoEntity>(relaxed = true).also {
            every { it.id } returns existingPhotoId
            every { it.fileId } returns existingPhotoKey
        }
        return mockk<VisitEntity>(relaxed = true).also {
            every { it.id } returns visitId.value
            every { it.status } returns status
            every { it.photos } returns if (withPhotos) mutableListOf(photo) else mutableListOf()
            every { visitRepository.findByIdAndStudioId(visitId.value, studioId.value) } returns it
            every { visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value) } returns it
        }
    }

    private fun stubSeedAndToken(expiresAt: Instant = Instant.now().plusSeconds(3600)) {
        every { damagePointsService.saveDamagePoints(tenantId, checkinId, any(), any()) } answers {
            DamagePointsResult(checkinId, thirdArg(), null, Instant.now())
        }
        every { tokenService.generateToken(tenantId, checkinId, userId.value.toString(), any(), any()) } returns
            GeneratedUploadToken(token = "tok-1", expiresAt = expiresAt)
    }

    @Test
    fun `sesja jest kluczowana identyfikatorem WIZYTY, wiec telefon dostaje ten sam formularz`() {
        visit()
        stubSeedAndToken()

        val result = service.startSession(visitId, studioId, userId, emptyList(), "sedan", rotate = false)

        assertEquals("tok-1", result.token)
        // `checkinId` sesji mobilnej = id wizyty. Na tym stoi cały pomysł: strona
        // mobilna i endpointy pod /api/mobile/checkin zostają bez zmian.
        assertEquals(checkinId, result.checkinId)
        verify {
            tokenService.generateToken(
                tenantId, checkinId, userId.value.toString(), false, UploadSessionPurpose.DAMAGE_MAP
            )
        }
    }

    @Test
    fun `sesja z karty wizyty jest oznaczona jako mapa uszkodzen, nie jako przyjecie`() {
        // Po tym oznaczeniu telefon pokazuje wyłącznie zakładkę „Uszkodzenia":
        // zdjęcie bez przypisania do punktu nie jest tym, po co ktoś skanuje ten kod.
        visit()
        stubSeedAndToken()
        val purpose = slot<UploadSessionPurpose>()
        every {
            tokenService.generateToken(tenantId, checkinId, userId.value.toString(), any(), capture(purpose))
        } returns GeneratedUploadToken("tok-1", Instant.now().plusSeconds(3600))

        service.startSession(visitId, studioId, userId, emptyList(), "sedan", rotate = false)

        assertEquals(UploadSessionPurpose.DAMAGE_MAP, purpose.captured)
    }

    @Test
    fun `zasiew przenosi punkty razem ze zdjeciami i rozwiazuje ich klucze S3`() {
        visit()
        stubSeedAndToken()
        val seeded = slot<List<DamagePointData>>()
        every { damagePointsService.saveDamagePoints(tenantId, checkinId, capture(seeded), any()) } answers {
            DamagePointsResult(checkinId, seeded.captured, null, Instant.now())
        }

        val points = listOf(
            DamagePoint(
                id = 1, x = 10.0, y = 20.0, note = "rysa",
                photos = listOf(DamagePhoto(photoId = existingPhotoId.toString()))
            ),
            DamagePoint(id = 2, x = 50.0, y = 60.0, note = "wgniecenie")
        )

        service.startSession(visitId, studioId, userId, points, "suv", rotate = false)

        assertEquals(listOf(1, 2), seeded.captured.map { it.id })
        // Zdjęcie NIE wypada po drodze: telefon odsyła pełną mapę, więc gdyby tu
        // zniknęło, jego pierwszy zapis zrzuciłby je z punktu — bez komunikatu.
        val photo = seeded.captured.first().photos.single()
        assertEquals(existingPhotoId.toString(), photo.photoId)
        // Klucz z galerii wizyty, żeby telefon pokazał miniaturę, a nie pusty kafelek.
        assertEquals(existingPhotoKey, photo.s3Key)
    }

    @Test
    fun `zdjecie, ktorego nie ma w galerii wizyty, jedzie bez klucza i nie wywraca zasiewu`() {
        visit(withPhotos = false)
        stubSeedAndToken()
        val seeded = slot<List<DamagePointData>>()
        every { damagePointsService.saveDamagePoints(tenantId, checkinId, capture(seeded), any()) } answers {
            DamagePointsResult(checkinId, seeded.captured, null, Instant.now())
        }

        service.startSession(
            visitId, studioId, userId,
            listOf(DamagePoint(1, 10.0, 20.0, "rysa", listOf(DamagePhoto("nieznane")))),
            "sedan", rotate = false
        )

        assertNull(seeded.captured.single().photos.single().s3Key)
    }

    @Test
    fun `zamknietej wizyty telefonem tez nie domalujesz`() {
        visit(status = VisitStatus.COMPLETED)

        assertThrows(ValidationException::class.java) {
            service.startSession(visitId, studioId, userId, emptyList(), "sedan", rotate = false)
        }
        verify(exactly = 0) { tokenService.generateToken(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { damagePointsService.saveDamagePoints(any(), any(), any(), any()) }
    }

    @Test
    fun `przeniesienie zdjecia oddaje mapowanie identyfikatorow po nazwie pliku tymczasowego`() = runBlocking {
        visit()
        val newPhotoId = UUID.randomUUID()
        val tempPhotoId = "3f2a91c4-0000-4000-8000-000000000001"
        coEvery { checkinPhotoService.finalizePhotos(tenantId, checkinId, visitId) } returns listOf(
            FinalizedCheckinPhoto(
                photoId = newPhotoId,
                fileId = "studio/visits/v/photos/checkin_${tempPhotoId}_123.jpg",
                // Nazwa w magazynie tymczasowym: „{photoId}.{ext}" — i to jest ten
                // identyfikator, którym punkty uszkodzeń wskazywały zdjęcie.
                fileName = "$tempPhotoId.jpg"
            )
        )
        every { photoSessionService.generateDownloadUrl(any()) } returns "https://example.test/x.jpg"

        val claimed = service.claimPhotos(visitId, studioId, userId, "Anna Kowalska").single()

        assertEquals(tempPhotoId, claimed.temporaryPhotoId)
        assertEquals(newPhotoId.toString(), claimed.photoId)
        assertEquals("https://example.test/x.jpg", claimed.thumbnailUrl)

        // Wiersz zdjęcia wizyty powstaje przez repozytorium zdjęć, a nie przez
        // przepisanie kolekcji agregatu (`orphanRemoval = true` usuwałoby wtedy
        // zdjęcia, których w kolekcji nie było).
        val rows = slot<List<VisitPhotoEntity>>()
        verify { visitPhotoRepository.saveAll(capture(rows)) }
        assertEquals(newPhotoId, rows.captured.single().id)
        verify(exactly = 0) { visitRepository.save(any()) }
    }

    @Test
    fun `brak zdjec do przeniesienia nie dotyka bazy`() = runBlocking {
        visit()
        coEvery { checkinPhotoService.finalizePhotos(tenantId, checkinId, visitId) } returns emptyList()

        assertTrue(service.claimPhotos(visitId, studioId, userId, "Anna Kowalska").isEmpty())
        verify(exactly = 0) { visitPhotoRepository.saveAll(any<List<VisitPhotoEntity>>()) }
    }

    @Test
    fun `brak sesji mobilnej to null, a nie blad`() {
        // Okno pyta o to przy otwarciu; wyjątek zamieniałby „nikt nie użył telefonu"
        // w komunikat o awarii.
        every { tokenService.getTokenForCheckin(tenantId, checkinId) } returns null

        assertNull(service.readSession(visitId, studioId))
        verify(exactly = 0) { damagePointsService.getDamagePoints(any(), any()) }
    }

    @Test
    fun `sesja bez zapisanych punktow tez jest niczym`() {
        every { tokenService.getTokenForCheckin(tenantId, checkinId) } returns "tok-1"
        every { damagePointsService.getDamagePoints(tenantId, checkinId) } returns
            DamagePointsResult(checkinId, emptyList(), null, savedAt = null)

        assertNull(service.readSession(visitId, studioId))
    }

    @Test
    fun `odczyt sesji podpisuje adresy miniatur ze zapisanych kluczy`() {
        every { tokenService.getTokenForCheckin(tenantId, checkinId) } returns "tok-1"
        every { damagePointsService.getDamagePoints(tenantId, checkinId) } returns DamagePointsResult(
            checkinId = checkinId,
            damagePoints = listOf(
                DamagePointData(
                    id = 1, x = 1.0, y = 2.0, note = "rysa",
                    photos = listOf(
                        pl.detailing.crm.checkin.qr.DamagePointPhotoData(
                            photoId = "temp-1",
                            s3Key = "temp/uploads/x/temp-1.jpg"
                        )
                    )
                )
            ),
            vehicleType = "kombi",
            savedAt = Instant.now()
        )
        every { checkinPhotoService.generateDownloadUrl("temp/uploads/x/temp-1.jpg") } returns
            "https://example.test/temp-1.jpg"

        val state = service.readSession(visitId, studioId)!!

        assertEquals("kombi", state.vehicleType)
        assertEquals("https://example.test/temp-1.jpg", state.damagePoints.single().photos.single().thumbnailUrl)
    }
}

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
import pl.detailing.crm.checkin.qr.DamagePointPhotoData
import pl.detailing.crm.checkin.qr.DamagePointsResult
import pl.detailing.crm.checkin.qr.FinalizedCheckinPhoto
import pl.detailing.crm.checkin.qr.GeneratedUploadToken
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
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

    /** Atrapa Redisa: mapowanie „zdjęcie tymczasowe → zdjęcie wizyty" w zwykłej mapie. */
    private val photoMap = mutableMapOf<String, String>()
    private val hashOps: HashOperations<String, String, String> =
        mockk<HashOperations<String, String, String>>(relaxed = true).also { ops ->
            every { ops.entries(any()) } answers { HashMap(photoMap) }
            every { ops.putAll(any(), any()) } answers { photoMap.putAll(secondArg()) }
        }
    private val redisTemplate: StringRedisTemplate = mockk<StringRedisTemplate>(relaxed = true).also {
        every { it.opsForHash<String, String>() } returns hashOps
    }

    private val service = VisitDamageMapMobileService(
        visitRepository, visitPhotoRepository, tokenService,
        damagePointsService, checkinPhotoService, photoSessionService, redisTemplate
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
    private val tempPhotoId = "3f2a91c4-0000-4000-8000-000000000001"
    private val newPhotoId = UUID.randomUUID()
    private val newPhotoKey = "studio/visits/v/photos/checkin_${tempPhotoId}_123.jpg"

    private fun stubFinalize(vararg photos: FinalizedCheckinPhoto) {
        coEvery { checkinPhotoService.finalizePhotos(tenantId, checkinId, visitId) } returns photos.toList()
    }

    private fun stubSessionPoints(vararg photoIds: String) {
        every { damagePointsService.getDamagePoints(tenantId, checkinId) } returns DamagePointsResult(
            checkinId = checkinId,
            damagePoints = listOf(
                DamagePointData(
                    id = 1, x = 10.0, y = 20.0, note = "rysa",
                    photos = photoIds.map { DamagePointPhotoData(photoId = it) }
                )
            ),
            vehicleType = "sedan",
            savedAt = Instant.now()
        )
    }

    private fun visitPhoto(id: UUID, key: String): VisitPhotoEntity =
        mockk<VisitPhotoEntity>(relaxed = true).also {
            every { it.id } returns id
            every { it.fileId } returns key
        }

    @Test
    fun `uzgodnienie przenosi zdjecie i oddaje punkt z identyfikatorem zdjecia WIZYTY`() = runBlocking {
        /*
         * Sedno zgłoszenia: zdjęcie szło na serwer, ale nie pokazywało się pod
         * uszkodzeniem. Telefon zna wyłącznie identyfikator tymczasowy — tłumaczenie
         * musi się wydarzyć tutaj, bo tylko tu jest mapowanie.
         */
        val visitEntity = visit()
        stubFinalize(FinalizedCheckinPhoto(newPhotoId, newPhotoKey, "$tempPhotoId.jpg"))
        stubSessionPoints(tempPhotoId)
        every { visitEntity.photos } returns mutableListOf(visitPhoto(newPhotoId, newPhotoKey))
        every { photoSessionService.generateDownloadUrl(newPhotoKey) } returns "https://example.test/nowe.jpg"

        val state = service.syncSession(visitId, studioId, userId, "Anna Kowalska")!!

        val photo = state.damagePoints.single().photos.single()
        assertEquals(newPhotoId.toString(), photo.photoId)
        assertEquals("https://example.test/nowe.jpg", photo.thumbnailUrl)
    }

    @Test
    fun `drugie uzgodnienie nie dubluje wiersza zdjecia`() = runBlocking {
        /*
         * Telefon przy dodaniu zdjęcia wysyła DWA zdarzenia, więc okno potrafi
         * wywołać uzgodnienie dwa razy. Wcześniej każde przenosiło ten sam plik i
         * powstawały dwa wiersze — zdjęcie pokazywało się PODWÓJNIE w „Istniejących".
         */
        val visitEntity = visit()
        stubSessionPoints(tempPhotoId)
        every { visitEntity.photos } returns mutableListOf(visitPhoto(newPhotoId, newPhotoKey))
        every { photoSessionService.generateDownloadUrl(any()) } returns "https://example.test/nowe.jpg"

        stubFinalize(FinalizedCheckinPhoto(newPhotoId, newPhotoKey, "$tempPhotoId.jpg"))
        service.syncSession(visitId, studioId, userId, "Anna Kowalska")

        // Drugie wywołanie: gdyby S3 jednak oddało ten sam plik (wyścig), mapowanie
        // musi go rozpoznać i NIE wstawiać kolejnego wiersza.
        stubFinalize(FinalizedCheckinPhoto(UUID.randomUUID(), newPhotoKey, "$tempPhotoId.jpg"))
        service.syncSession(visitId, studioId, userId, "Anna Kowalska")

        verify(exactly = 1) { visitPhotoRepository.saveAll(any<List<VisitPhotoEntity>>()) }
        assertEquals(1, photoMap.size)
    }

    @Test
    fun `placeholder telefonu sprzed konca wysylki jest pomijany, nie wstawiany martwy`() = runBlocking {
        // Telefon dopisuje zdjęcie do punktu z identyfikatorem `local-…` jeszcze przed
        // zakończeniem wysyłki i zapisuje punkty. Taki wskaźnik nie prowadzi nigdzie.
        visit()
        stubFinalize()
        stubSessionPoints("local-1717171717-abc")

        val state = service.syncSession(visitId, studioId, userId, "Anna Kowalska")!!

        assertTrue(state.damagePoints.single().photos.isEmpty())
    }

    @Test
    fun `zdjecie przypiete przed sesja zostaje przy punkcie`() = runBlocking {
        // Punkt zasiany z komputera wraca z telefonu z identyfikatorem zdjęcia WIZYTY;
        // pominięcie go zrzucałoby zdjęcia z punktów.
        val visitEntity = visit()
        stubFinalize()
        stubSessionPoints(existingPhotoId.toString())
        every { visitEntity.photos } returns mutableListOf(visitPhoto(existingPhotoId, existingPhotoKey))
        every { photoSessionService.generateDownloadUrl(existingPhotoKey) } returns "https://example.test/stare.jpg"

        val state = service.syncSession(visitId, studioId, userId, "Anna Kowalska")!!

        val photo = state.damagePoints.single().photos.single()
        assertEquals(existingPhotoId.toString(), photo.photoId)
        assertEquals("https://example.test/stare.jpg", photo.thumbnailUrl)
    }

    @Test
    fun `brak zapisanych punktow to null, a nie blad`() = runBlocking {
        // Okno pyta o to przy otwarciu; wyjątek zamieniałby „nikt nie użył telefonu"
        // w komunikat o awarii.
        visit()
        stubFinalize()
        every { damagePointsService.getDamagePoints(tenantId, checkinId) } returns
            DamagePointsResult(checkinId, emptyList(), null, savedAt = null)

        assertNull(service.syncSession(visitId, studioId, userId, "Anna Kowalska"))
    }

    @Test
    fun `uzgodnienia zamknietej wizyty nie robimy`() {
        visit(status = VisitStatus.COMPLETED)

        assertThrows(ValidationException::class.java) {
            runBlocking { service.syncSession(visitId, studioId, userId, "Anna Kowalska") }
        }
        verify(exactly = 0) { visitPhotoRepository.saveAll(any<List<VisitPhotoEntity>>()) }
    }
}

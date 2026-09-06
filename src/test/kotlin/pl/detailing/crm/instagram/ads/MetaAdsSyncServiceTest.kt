package pl.detailing.crm.instagram.ads

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import java.time.LocalDate
import java.util.UUID

/**
 * Zakończenie emisji to jedyne zdarzenie, którego nie da się odtworzyć później:
 * Biblioteka reklam pokazuje stan na teraz i nie mówi, kiedy reklama zniknęła.
 * Jeśli sync nie zapisze momentu, w którym `ad_delivery_stop_time` przestało być
 * puste, informacja przepada bezpowrotnie — dlatego jest tu osobny test.
 */
class MetaAdsSyncServiceTest {

    private val client = mockk<MetaAdLibraryClient>()
    private val snapshotRepository = mockk<MetaAdSnapshotRepository>(relaxed = true)
    private val profileRepository = mockk<InstagramProfileRepository>()

    private val service = MetaAdsSyncService(client, snapshotRepository, profileRepository)

    init {
        // Relaxed mock zwraca dla generycznego save() goły Object, którego Kotlin
        // nie potrafi rzutować na encję — stąd jawne przepuszczenie argumentu.
        every { snapshotRepository.save(any<MetaAdSnapshotEntity>()) } answers { firstArg() }
    }

    private val profileId = UUID.randomUUID()
    private val pageId = "1234567890"

    private fun profile() = InstagramProfileEntity(
        id = profileId,
        username = "carslab.krakow"
    ).apply { facebookPageId = pageId }

    private fun ad(stop: LocalDate?) = RawMetaAd(
        adArchiveId = "ad-1",
        pageId = pageId,
        pageName = "CarsLab Detailing",
        title = "Powłoka ceramiczna",
        deliveryStart = LocalDate.of(2026, 7, 12),
        deliveryStop = stop,
        reachEu = 41_200,
        platforms = listOf("FACEBOOK", "INSTAGRAM"),
        targetAges = "25-54",
        targetGender = "All",
        targetLocations = listOf(RawAdLocation("Kraków", "city", excluded = false)),
        payer = "CARSLAB SP. Z O.O.",
        beneficiary = "CARSLAB SP. Z O.O.",
        polandBreakdown = listOf(RawAgeGenderReach("25-34", 10_150, 3_200, 0)),
        snapshotUrl = "https://facebook.com/ads/library/?id=ad-1"
    )

    private fun existingRunningRow() = MetaAdSnapshotEntity(
        id = UUID.randomUUID(),
        adArchiveId = "ad-1",
        pageId = pageId,
        profileId = profileId,
        deliveryStart = LocalDate.of(2026, 7, 12),
        deliveryStop = null
    )

    @Test
    fun `wpisanie daty zakończenia przez Meta zapisuje moment wykrycia`() {
        val row = existingRunningRow()
        every { client.enabled } returns true
        every { profileRepository.findAllWithFacebookPage() } returns listOf(profile())
        every { client.fetchAdsForPages(any()) } returns listOf(ad(stop = LocalDate.of(2026, 9, 3)))
        every { snapshotRepository.findByAdArchiveIdIn(any()) } returns listOf(row)

        val result = service.syncAll()

        assertEquals(1, result.adsEnded)
        assertEquals(LocalDate.of(2026, 9, 3), row.deliveryStop)
        assertNotNull(row.endedDetectedAt, "brak momentu wykrycia — zdarzenie nie trafi do Pulsu")
    }

    @Test
    fun `powtórny odczyt zakończonej reklamy nie zgłasza zakończenia drugi raz`() {
        val row = existingRunningRow().apply {
            deliveryStop = LocalDate.of(2026, 9, 3)
            endedDetectedAt = java.time.Instant.parse("2026-09-04T05:45:00Z")
        }
        every { client.enabled } returns true
        every { profileRepository.findAllWithFacebookPage() } returns listOf(profile())
        every { client.fetchAdsForPages(any()) } returns listOf(ad(stop = LocalDate.of(2026, 9, 3)))
        every { snapshotRepository.findByAdArchiveIdIn(any()) } returns listOf(row)

        val result = service.syncAll()

        assertEquals(0, result.adsEnded)
        assertEquals(java.time.Instant.parse("2026-09-04T05:45:00Z"), row.endedDetectedAt)
    }

    @Test
    fun `reklama widziana pierwszy raz juz jako zakonczona nie jest zdarzeniem`() {
        every { client.enabled } returns true
        every { profileRepository.findAllWithFacebookPage() } returns listOf(profile())
        every { client.fetchAdsForPages(any()) } returns listOf(ad(stop = LocalDate.of(2026, 8, 1)))
        every { snapshotRepository.findByAdArchiveIdIn(any()) } returns emptyList()

        val saved = slot<MetaAdSnapshotEntity>()
        every { snapshotRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.syncAll()

        assertEquals(1, result.adsNew)
        assertEquals(0, result.adsEnded)
        assertNull(
            saved.captured.endedDetectedAt,
            "nie widzieliśmy jej trwania, więc nie mamy czego meldować jako zakończenie"
        )
    }

    @Test
    fun `zasieg bierzemy z rozbicia dla Polski, nie z liczby dla calej UE`() {
        every { client.enabled } returns true
        every { profileRepository.findAllWithFacebookPage() } returns listOf(profile())
        every { client.fetchAdsForPages(any()) } returns listOf(ad(stop = null))
        every { snapshotRepository.findByAdArchiveIdIn(any()) } returns emptyList()

        val saved = slot<MetaAdSnapshotEntity>()
        every { snapshotRepository.save(capture(saved)) } answers { saved.captured }

        service.syncAll()

        assertEquals(13_350, saved.captured.reachPl)
        assertEquals(41_200, saved.captured.reachEu)
    }

    @Test
    fun `bez tokena nie odpytujemy Meta w ogole`() {
        every { client.enabled } returns false

        val result = service.syncAll()

        assertEquals(0, result.pagesChecked)
        verify(exactly = 0) { client.fetchAdsForPages(any()) }
    }

    @Test
    fun `reklama strony ktorej nikt nie obserwuje jest pomijana`() {
        every { client.enabled } returns true
        every { profileRepository.findAllWithFacebookPage() } returns listOf(profile())
        every { client.fetchAdsForPages(any()) } returns listOf(ad(stop = null).copy(pageId = "999"))
        every { snapshotRepository.findByAdArchiveIdIn(any()) } returns emptyList()

        val result = service.syncAll()

        assertEquals(0, result.adsNew)
        verify(exactly = 0) { snapshotRepository.save(any<MetaAdSnapshotEntity>()) }
    }
}

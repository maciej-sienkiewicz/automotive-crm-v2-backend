package pl.detailing.crm.gallery.thumbnails

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import pl.detailing.crm.batchorder.infrastructure.BatchOrderPhotoRepository
import pl.detailing.crm.vehicle.infrastructure.VehiclePhotoRepository
import pl.detailing.crm.visit.infrastructure.VisitPhotoEntity
import pl.detailing.crm.visit.infrastructure.VisitPhotoRepository
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.time.Instant

/**
 * Miniatury sprzed poprawki orientacji EXIF są generowane od nowa. Stara znika dopiero
 * po zapisie nowej w bazie, a miniatura, której poprawić się nie da, nie wraca w każdym
 * przebiegu zadania.
 */
class ThumbnailBackfillJobTest {

    private val thumbnails = mockk<PhotoThumbnailService>(relaxed = true)
    private val visitPhotos = mockk<VisitPhotoRepository>(relaxed = true)
    private val vehiclePhotos = mockk<VehiclePhotoRepository>(relaxed = true)
    private val batchPhotos = mockk<BatchOrderPhotoRepository>(relaxed = true)
    private val job = ThumbnailBackfillJob(thumbnails, visitPhotos, vehiclePhotos, batchPhotos, batchSize = 30)

    private val original = "studio/visits/v/photo.jpg"
    private val legacy = "thumbs/$original.jpg"
    private val upright = "thumbs/$original.upright.jpg"

    private fun visitPhoto(thumbnail: String?): VisitPhotoEntity = mockk(relaxed = true) {
        every { fileId } returns original
        every { thumbnailFileId } returns thumbnail
        every { uploadedAt } returns Instant.parse("2026-09-01T10:00:00Z")
    }

    init {
        every { vehiclePhotos.findMissingThumbnails(any()) } returns emptyList()
        every { batchPhotos.findMissingThumbnails(any()) } returns emptyList()
        // Relaksowany mock zwróciłby obiekt złego typu i zapis rzucałby ClassCastException.
        every { visitPhotos.save(any<VisitPhotoEntity>()) } answers { firstArg() }
    }

    @Test
    fun `stara miniatura - nowa wygenerowana i zapisana, dopiero potem stara usunieta`() {
        val photo = visitPhoto(legacy)
        every { visitPhotos.findMissingThumbnails(any()) } returns listOf(photo)
        every { thumbnails.generateThumbnail(original) } returns upright

        job.backfill()

        verifyOrder {
            photo.thumbnailFileId = upright
            visitPhotos.save(photo)
            thumbnails.deleteQuietly(legacy)
        }
    }

    @Test
    fun `stara miniatura z oryginalem, ktory sie nie dekoduje - przeniesiona pod nowy klucz, bez ponawiania`() {
        val photo = visitPhoto(legacy)
        every { visitPhotos.findMissingThumbnails(any()) } returns listOf(photo)
        every { thumbnails.generateThumbnail(original) } throws IllegalArgumentException("corrupted")
        every { thumbnails.adoptLegacyThumbnail(legacy, original) } returns upright

        job.backfill()

        verifyOrder {
            photo.thumbnailFileId = upright
            visitPhotos.save(photo)
            thumbnails.deleteQuietly(legacy)
        }
    }

    @Test
    fun `stara miniatura, oryginalu juz nie ma - zostaje stara pod nowym kluczem`() {
        val photo = visitPhoto(legacy)
        every { visitPhotos.findMissingThumbnails(any()) } returns listOf(photo)
        every { thumbnails.generateThumbnail(original) } throws NoSuchKeyException.builder().build()
        every { thumbnails.adoptLegacyThumbnail(legacy, original) } returns upright

        job.backfill()

        verify { photo.thumbnailFileId = upright }
    }

    @Test
    fun `blad przejsciowy S3 - nic nie zapisane, stara miniatura zostaje do nastepnego przebiegu`() {
        val photo = visitPhoto(legacy)
        every { visitPhotos.findMissingThumbnails(any()) } returns listOf(photo)
        every { thumbnails.generateThumbnail(original) } throws RuntimeException("timeout")

        job.backfill()

        verify(exactly = 0) { visitPhotos.save(any()) }
        verify(exactly = 0) { thumbnails.deleteQuietly(any()) }
        verify(exactly = 0) { thumbnails.adoptLegacyThumbnail(any(), any()) }
    }

    @Test
    fun `zdjecie bez miniatury - jak dotad, generowana i zapisana, nic do sprzatania`() {
        val photo = visitPhoto(null)
        every { visitPhotos.findMissingThumbnails(any()) } returns listOf(photo)
        every { thumbnails.generateThumbnail(original) } returns upright

        job.backfill()

        verify { photo.thumbnailFileId = upright }
        verify(exactly = 0) { thumbnails.deleteQuietly(any()) }
    }
}

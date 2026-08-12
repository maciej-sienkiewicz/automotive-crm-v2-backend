package pl.detailing.crm.gallery.thumbnails

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.batchorder.infrastructure.BatchOrderPhotoRepository
import pl.detailing.crm.vehicle.infrastructure.VehiclePhotoRepository
import pl.detailing.crm.visit.infrastructure.VisitPhotoRepository
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import java.time.Duration
import java.time.Instant

/**
 * Creates missing thumbnails for visit, vehicle and batch-order photos.
 *
 * Photos are uploaded by the browser straight to S3 (presigned PUT), so thumbnails
 * are generated asynchronously here shortly after upload. The job also backfills
 * every photo that existed before thumbnails were introduced. Until a photo's
 * thumbnail exists, the gallery endpoints fall back to serving the original.
 *
 * When the original object is missing (upload abandoned or legacy data loss) and
 * the record is older than [ORIGINAL_MISSING_GRACE], the photo is permanently
 * marked to use the original key so it is not retried forever.
 */
@Component
class ThumbnailBackfillJob(
    private val thumbnailService: PhotoThumbnailService,
    private val visitPhotoRepository: VisitPhotoRepository,
    private val vehiclePhotoRepository: VehiclePhotoRepository,
    private val batchOrderPhotoRepository: BatchOrderPhotoRepository,
    @Value("\${gallery.thumbnails.backfill-batch-size:30}") private val batchSize: Int
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ThumbnailBackfillJob::class.java)
        private val ORIGINAL_MISSING_GRACE: Duration = Duration.ofHours(24)
    }

    @Scheduled(
        fixedDelayString = "\${gallery.thumbnails.backfill-interval-ms:120000}",
        initialDelayString = "\${gallery.thumbnails.backfill-initial-delay-ms:30000}"
    )
    fun backfill() {
        val page = PageRequest.of(0, batchSize)
        var generated = 0

        visitPhotoRepository.findMissingThumbnails(page).forEach { photo ->
            process(photo.fileId, photo.uploadedAt) { key ->
                photo.thumbnailFileId = key
                visitPhotoRepository.save(photo)
                generated++
            }
        }
        vehiclePhotoRepository.findMissingThumbnails(page).forEach { photo ->
            process(photo.fileId, photo.uploadedAt) { key ->
                photo.thumbnailFileId = key
                vehiclePhotoRepository.save(photo)
                generated++
            }
        }
        batchOrderPhotoRepository.findMissingThumbnails(page).forEach { photo ->
            process(photo.fileId, photo.uploadedAt) { key ->
                photo.thumbnailFileId = key
                batchOrderPhotoRepository.save(photo)
                generated++
            }
        }

        if (generated > 0) {
            logger.info("Thumbnail backfill: generated {} thumbnails", generated)
        }
    }

    private fun process(fileId: String, uploadedAt: Instant, persist: (String) -> Unit) {
        try {
            persist(thumbnailService.generateThumbnail(fileId))
        } catch (e: NoSuchKeyException) {
            if (uploadedAt.isBefore(Instant.now().minus(ORIGINAL_MISSING_GRACE))) {
                // Original never arrived — stop retrying, serve the original key (404s the
                // same way it always did) instead of scanning this row on every run.
                persist(fileId)
                logger.warn("Original {} missing for over {}h — marked as its own thumbnail", fileId, ORIGINAL_MISSING_GRACE.toHours())
            }
            // Otherwise the upload may still be in flight — retry on the next run.
        } catch (e: Exception) {
            logger.warn("Failed to generate thumbnail for {}: {}", fileId, e.message)
        }
    }
}

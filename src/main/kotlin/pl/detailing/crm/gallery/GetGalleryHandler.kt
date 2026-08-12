package pl.detailing.crm.gallery

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.phototags.PhotoSource
import pl.detailing.crm.phototags.PhotoTagRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import java.util.UUID

/**
 * Handler that aggregates photos from all sources (visits, vehicles, batch orders)
 * across the studio into a single paginated gallery with tag/brand/model filtering.
 *
 * Filtering, sorting and pagination are pushed down to SQL (see
 * [GalleryPhotoQueryDao]), so only one page of photos is ever loaded, enriched
 * and presigned — request cost no longer grows with the studio's total photo count.
 */
@Service
class GetGalleryHandler(
    private val galleryPhotoQueryDao: GalleryPhotoQueryDao,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val customerRepository: CustomerRepository,
    private val photoTagRepository: PhotoTagRepository,
    private val photoSessionService: PhotoSessionService,
    private val galleryTagService: GalleryTagService
) {

    @Transactional(readOnly = true)
    suspend fun handle(command: GetGalleryCommand): GetGalleryResult {
        val studioId = command.studioId

        // ── 1. Count + fetch one page, filtered and sorted in SQL ─────────────
        val total = galleryPhotoQueryDao.countPhotos(studioId, command.tags, command.brand, command.model)
        val totalPages = maxOf(1, (total + command.pageSize - 1) / command.pageSize)
        val safePage = command.page.coerceIn(1, totalPages)

        val rows = galleryPhotoQueryDao.findPage(
            studioId = studioId,
            tags = command.tags,
            brand = command.brand,
            model = command.model,
            sortOrder = command.sortOrder,
            limit = command.pageSize,
            offset = (safePage - 1) * command.pageSize
        )

        // ── 2. Resolve customer IDs for vehicle photos via primary owners ─────
        val vehicleIdsForOwnerLookup = rows
            .filter { it.source == GalleryPhotoSource.VEHICLE && it.vehicleId != null }
            .mapNotNull { it.vehicleId }
            .distinct()

        val primaryOwnerByVehicleId: Map<UUID, UUID> = if (vehicleIdsForOwnerLookup.isNotEmpty()) {
            vehicleOwnerRepository
                .findPrimaryOwnersByVehicleIds(vehicleIdsForOwnerLookup)
                .associate { it.id.vehicleId to it.id.customerId }
        } else emptyMap()

        fun customerIdOf(row: GalleryPhotoQueryDao.GalleryPhotoRow): UUID? = when (row.source) {
            GalleryPhotoSource.VEHICLE -> row.vehicleId?.let { primaryOwnerByVehicleId[it] }
            else -> row.customerId
        }

        // ── 3. Batch-load customer names (page scope only) ────────────────────
        val customerIds = rows.mapNotNull { customerIdOf(it) }.distinct()
        val customerNameById: Map<UUID, String> = if (customerIds.isNotEmpty()) {
            customerRepository.findByIdsAndStudioId(customerIds, studioId)
                .associate { c ->
                    c.id to listOfNotNull(c.firstName, c.lastName).joinToString(" ").ifBlank { c.email ?: "" }
                }
        } else emptyMap()

        // ── 4. Batch-load tags (page scope only) ──────────────────────────────
        val tagsByPhotoId = loadTagsForPage(rows)

        // ── 5. Presign URLs — only for the returned page ──────────────────────
        val pagePhotos = rows.map { row ->
            GalleryPhotoItem(
                id = row.photoId.toString(),
                fileName = row.fileName,
                // Fall back to the original until the backfill job produces a thumbnail.
                thumbnailUrl = photoSessionService.generateDownloadUrl(row.thumbnailFileId ?: row.fileId),
                fullSizeUrl = photoSessionService.generateDownloadUrl(row.fileId),
                description = row.description,
                tags = tagsByPhotoId[row.photoId] ?: emptyList(),
                uploadedAt = row.uploadedAt,
                uploadedBy = row.uploadedBy?.toString(),
                uploadedByName = row.uploadedByName,
                source = row.source,
                vehicleId = row.vehicleId?.toString(),
                vehicleBrand = row.vehicleBrand,
                vehicleModel = row.vehicleModel,
                vehicleLicensePlate = row.vehicleLicensePlate,
                vehicleYear = row.vehicleYear,
                visitId = row.visitId?.toString(),
                visitNumber = row.visitNumber,
                customerId = customerIdOf(row)?.toString(),
                customerName = customerIdOf(row)?.let { customerNameById[it] },
                batchOrderEntryId = row.batchOrderEntryId?.toString(),
                contractorName = row.contractorName
            )
        }

        // ── 6. Available tags for the studio (cached per studio) ──────────────
        val availableTags = galleryTagService.getAvailableTags(studioId)

        return GetGalleryResult(
            photos = pagePhotos,
            pagination = GalleryPaginationResult(
                total = total,
                page = safePage,
                pageSize = command.pageSize,
                totalPages = totalPages
            ),
            availableTags = availableTags
        )
    }

    private fun loadTagsForPage(rows: List<GalleryPhotoQueryDao.GalleryPhotoRow>): Map<UUID, List<String>> {
        val bySource = mapOf(
            PhotoSource.VISIT_PHOTO to rows.filter { it.source == GalleryPhotoSource.VISIT }.map { it.photoId },
            PhotoSource.VEHICLE_PHOTO to rows.filter { it.source == GalleryPhotoSource.VEHICLE }.map { it.photoId },
            PhotoSource.BATCH_ORDER_PHOTO to rows.filter { it.source == GalleryPhotoSource.BATCH_ORDER }.map { it.photoId }
        )
        return bySource.flatMap { (type, ids) ->
            if (ids.isEmpty()) emptyList()
            else photoTagRepository.findByPhotoIdsAndType(ids, type)
        }.groupBy({ it.photoId }, { it.tagName })
    }
}

package pl.detailing.crm.gallery

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.phototags.PhotoSource
import pl.detailing.crm.phototags.PhotoTagRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.util.UUID

/**
 * Handler that aggregates photos from all sources (vehicles and visits) across
 * the entire studio into a single paginated gallery with tag/brand/model filtering.
 */
@Service
class GetGalleryHandler(
    private val visitRepository: VisitRepository,
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val customerRepository: CustomerRepository,
    private val photoTagRepository: PhotoTagRepository,
    private val photoSessionService: PhotoSessionService
) {

    @Transactional(readOnly = true)
    suspend fun handle(command: GetGalleryCommand): GetGalleryResult {
        val studioId = command.studioId

        // ── 1. Resolve tag filter: photo IDs that match ALL requested tags ────
        val visitPhotoIdsMatchingTags: Set<UUID>?
        val vehiclePhotoIdsMatchingTags: Set<UUID>?

        if (command.tags.isNotEmpty()) {
            visitPhotoIdsMatchingTags = photoTagRepository
                .findPhotoIdsByStudioAndTypeMatchingAllTags(studioId, PhotoSource.VISIT_PHOTO, command.tags, command.tags.size.toLong())
                .toHashSet()
            vehiclePhotoIdsMatchingTags = photoTagRepository
                .findPhotoIdsByStudioAndTypeMatchingAllTags(studioId, PhotoSource.VEHICLE_PHOTO, command.tags, command.tags.size.toLong())
                .toHashSet()
        } else {
            visitPhotoIdsMatchingTags = null
            vehiclePhotoIdsMatchingTags = null
        }

        // ── 2. Fetch visits with photos ───────────────────────────────────────
        val visitsWithPhotos = visitRepository.findByStudioIdWithPhotos(studioId)

        // ── 3. Fetch vehicles with photos ─────────────────────────────────────
        val vehiclesWithPhotos = vehicleRepository.findByStudioIdWithPhotos(studioId)

        // ── 4. Build flat list of raw photo infos ─────────────────────────────
        data class RawPhoto(
            val photoId: UUID,
            val source: GalleryPhotoSource,
            val fileId: String,
            val fileName: String,
            val description: String?,
            val uploadedAt: java.time.Instant,
            val uploadedBy: UUID?,
            val uploadedByName: String?,
            // vehicle fields
            val vehicleId: UUID?,
            val vehicleBrand: String?,
            val vehicleModel: String?,
            val vehicleLicensePlate: String?,
            val vehicleYear: Int?,
            // visit fields (only for VISIT source)
            val visitId: UUID?,
            val visitNumber: String?,
            // customer will be resolved later
            val customerId: UUID?
        )

        val rawPhotos = mutableListOf<RawPhoto>()

        // visit photos
        for (visit in visitsWithPhotos) {
            val brand = visit.brandSnapshot
            val model = visit.modelSnapshot

            if (command.brand != null && !brand.equals(command.brand, ignoreCase = true)) continue
            if (command.model != null && !model.equals(command.model, ignoreCase = true)) continue

            for (photo in visit.photos) {
                if (visitPhotoIdsMatchingTags != null && photo.id !in visitPhotoIdsMatchingTags) continue
                rawPhotos.add(
                    RawPhoto(
                        photoId = photo.id,
                        source = GalleryPhotoSource.VISIT,
                        fileId = photo.fileId,
                        fileName = photo.fileName,
                        description = photo.description,
                        uploadedAt = photo.uploadedAt,
                        uploadedBy = photo.uploadedBy,
                        uploadedByName = photo.uploadedByName,
                        vehicleId = visit.vehicleId,
                        vehicleBrand = brand,
                        vehicleModel = model,
                        vehicleLicensePlate = visit.licensePlateSnapshot,
                        vehicleYear = visit.yearOfProductionSnapshot,
                        visitId = visit.id,
                        visitNumber = visit.visitNumber,
                        customerId = visit.customerId
                    )
                )
            }
        }

        // vehicle photos
        for (vehicle in vehiclesWithPhotos) {
            if (command.brand != null && !vehicle.brand.equals(command.brand, ignoreCase = true)) continue
            if (command.model != null && !vehicle.model.equals(command.model, ignoreCase = true)) continue

            for (photo in vehicle.photos) {
                if (vehiclePhotoIdsMatchingTags != null && photo.id !in vehiclePhotoIdsMatchingTags) continue
                rawPhotos.add(
                    RawPhoto(
                        photoId = photo.id,
                        source = GalleryPhotoSource.VEHICLE,
                        fileId = photo.fileId,
                        fileName = photo.fileName,
                        description = photo.description,
                        uploadedAt = photo.uploadedAt,
                        uploadedBy = photo.uploadedBy,
                        uploadedByName = photo.uploadedByName,
                        vehicleId = vehicle.id,
                        vehicleBrand = vehicle.brand,
                        vehicleModel = vehicle.model,
                        vehicleLicensePlate = vehicle.licensePlate,
                        vehicleYear = vehicle.yearOfProduction,
                        visitId = null,
                        visitNumber = null,
                        customerId = null   // resolved via owners below
                    )
                )
            }
        }

        // ── 5. Resolve customer IDs for vehicle photos via primary owners ─────
        val vehicleIdsForOwnerLookup = rawPhotos
            .filter { it.source == GalleryPhotoSource.VEHICLE && it.vehicleId != null }
            .map { it.vehicleId!! }
            .distinct()

        val primaryOwnerByVehicleId: Map<UUID, UUID> = if (vehicleIdsForOwnerLookup.isNotEmpty()) {
            vehicleOwnerRepository
                .findPrimaryOwnersByVehicleIds(vehicleIdsForOwnerLookup)
                .associate { it.id.vehicleId to it.id.customerId }
        } else emptyMap()

        // Patch vehicle photos with resolved customerId
        val patchedPhotos = rawPhotos.map { rp ->
            if (rp.source == GalleryPhotoSource.VEHICLE && rp.vehicleId != null) {
                rp.copy(customerId = primaryOwnerByVehicleId[rp.vehicleId])
            } else rp
        }

        // ── 6. Batch-load customer names ──────────────────────────────────────
        val customerIds = patchedPhotos.mapNotNull { it.customerId }.distinct()
        val customerNameById: Map<UUID, String> = if (customerIds.isNotEmpty()) {
            customerRepository.findByIdsAndStudioId(customerIds, studioId)
                .associate { c ->
                    c.id to listOfNotNull(c.firstName, c.lastName).joinToString(" ").ifBlank { c.email ?: "" }
                }
        } else emptyMap()

        // ── 7. Batch-load tags ────────────────────────────────────────────────
        val visitPhotoIds = patchedPhotos.filter { it.source == GalleryPhotoSource.VISIT }.map { it.photoId }
        val vehiclePhotoIds = patchedPhotos.filter { it.source == GalleryPhotoSource.VEHICLE }.map { it.photoId }

        val visitTagsByPhotoId: Map<UUID, List<String>> = if (visitPhotoIds.isNotEmpty()) {
            photoTagRepository.findByPhotoIdsAndType(visitPhotoIds, PhotoSource.VISIT_PHOTO)
                .groupBy({ it.photoId }, { it.tagName })
        } else emptyMap()

        val vehicleTagsByPhotoId: Map<UUID, List<String>> = if (vehiclePhotoIds.isNotEmpty()) {
            photoTagRepository.findByPhotoIdsAndType(vehiclePhotoIds, PhotoSource.VEHICLE_PHOTO)
                .groupBy({ it.photoId }, { it.tagName })
        } else emptyMap()

        // ── 8. Generate presigned URLs and build final list ───────────────────
        val allPhotos = patchedPhotos.map { rp ->
            val tags = when (rp.source) {
                GalleryPhotoSource.VISIT -> visitTagsByPhotoId[rp.photoId] ?: emptyList()
                GalleryPhotoSource.VEHICLE -> vehicleTagsByPhotoId[rp.photoId] ?: emptyList()
            }
            GalleryPhotoItem(
                id = rp.photoId.toString(),
                fileName = rp.fileName,
                thumbnailUrl = photoSessionService.generateDownloadUrl(rp.fileId),
                fullSizeUrl = photoSessionService.generateDownloadUrl(rp.fileId),
                description = rp.description,
                tags = tags,
                uploadedAt = rp.uploadedAt,
                uploadedBy = rp.uploadedBy?.toString(),
                uploadedByName = rp.uploadedByName,
                source = rp.source,
                vehicleId = rp.vehicleId?.toString(),
                vehicleBrand = rp.vehicleBrand,
                vehicleModel = rp.vehicleModel,
                vehicleLicensePlate = rp.vehicleLicensePlate,
                vehicleYear = rp.vehicleYear,
                visitId = rp.visitId?.toString(),
                visitNumber = rp.visitNumber,
                customerId = rp.customerId?.toString(),
                customerName = rp.customerId?.let { customerNameById[it] }
            )
        }.let { photos ->
            if (command.sortOrder == GallerySortOrder.ASC) photos.sortedBy { it.uploadedAt }
            else photos.sortedByDescending { it.uploadedAt }
        }

        // ── 9. Paginate ───────────────────────────────────────────────────────
        val total = allPhotos.size
        val totalPages = maxOf(1, (total + command.pageSize - 1) / command.pageSize)
        val safePage = command.page.coerceIn(1, totalPages)
        val start = (safePage - 1) * command.pageSize
        val end = minOf(start + command.pageSize, total)
        val pagePhotos = if (start < total) allPhotos.subList(start, end) else emptyList()

        // ── 10. Available tags for the studio ─────────────────────────────────
        val availableTags = photoTagRepository.findDistinctTagNamesByStudio(studioId)

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
}

package pl.detailing.crm.gallery

import pl.detailing.crm.shared.pii.Pii
import java.time.Instant

// ─── command ──────────────────────────────────────────────────────────────────

enum class GallerySortOrder { ASC, DESC }

data class GetGalleryCommand(
    val studioId: java.util.UUID,
    val tags: List<String> = emptyList(),
    val brand: String? = null,
    val model: String? = null,
    val page: Int = 1,
    val pageSize: Int = 20,
    val sortOrder: GallerySortOrder = GallerySortOrder.DESC
)

// ─── result ───────────────────────────────────────────────────────────────────

data class GetGalleryResult(
    val photos: List<GalleryPhotoItem>,
    val pagination: GalleryPaginationResult,
    val availableTags: List<String>
)

data class GalleryPaginationResult(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

enum class GalleryPhotoSource {
    VISIT,
    VEHICLE,
    BATCH_ORDER
}

data class GalleryPhotoItem(
    val id: String,
    val fileName: String,
    val thumbnailUrl: String,
    val fullSizeUrl: String,
    val description: String?,
    val tags: List<String>,
    val uploadedAt: Instant,
    val uploadedBy: String?,
    val uploadedByName: String?,
    val source: GalleryPhotoSource,
    val vehicleId: String?,
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val vehicleLicensePlate: String?,
    val vehicleYear: Int?,
    val visitId: String?,
    val visitNumber: String?,
    val customerId: String?,
    @Pii val customerName: String?,
    val batchOrderEntryId: String?,
    val contractorName: String?
)

// ─── API response DTOs ────────────────────────────────────────────────────────

data class GalleryResponse(
    val photos: List<GalleryPhotoResponse>,
    val pagination: GalleryPaginationResponse,
    val availableTags: List<String>
)

data class GalleryPaginationResponse(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

data class GalleryPhotoResponse(
    val id: String,
    val fileName: String,
    val thumbnailUrl: String,
    val fullSizeUrl: String,
    val description: String?,
    val tags: List<String>,
    val uploadedAt: String,
    val uploadedBy: String?,
    val uploadedByName: String?,
    val source: String,
    val vehicleId: String?,
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val vehicleLicensePlate: String?,
    val vehicleYear: Int?,
    val visitId: String?,
    val visitNumber: String?,
    val customerId: String?,
    @Pii val customerName: String?,
    val batchOrderEntryId: String?,
    val contractorName: String?
)

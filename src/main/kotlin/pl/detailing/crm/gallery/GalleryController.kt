package pl.detailing.crm.gallery

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.role.domain.Permission

// The gallery shows visit photos — viewing photos rides on VISITS_VIEW.
@RequiresPermission(Permission.VISITS_VIEW)
@RestController
@RequestMapping("/api/v1/gallery")
class GalleryController(
    private val getGalleryHandler: GetGalleryHandler
) {

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    @GetMapping
    fun getGallery(
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "20") pageSize: Int,
        @RequestParam(required = false) brand: String?,
        @RequestParam(required = false) model: String?,
        @RequestParam(required = false) tags: String?,
        @RequestParam(required = false, defaultValue = "DESC") sortOrder: String
    ): ResponseEntity<GalleryResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val parsedTags = tags
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        val parsedSortOrder = runCatching { GallerySortOrder.valueOf(sortOrder.uppercase()) }
            .getOrDefault(GallerySortOrder.DESC)

        val result = getGalleryHandler.handle(
            GetGalleryCommand(
                studioId = principal.studioId.value,
                tags = parsedTags,
                brand = brand?.takeIf { it.isNotBlank() },
                model = model?.takeIf { it.isNotBlank() },
                page = page,
                pageSize = pageSize.coerceIn(1, 100),
                sortOrder = parsedSortOrder
            )
        )

        ResponseEntity.ok(
            GalleryResponse(
                photos = result.photos.map { photo ->
                    GalleryPhotoResponse(
                        id = photo.id,
                        fileName = photo.fileName,
                        thumbnailUrl = photo.thumbnailUrl,
                        fullSizeUrl = photo.fullSizeUrl,
                        description = photo.description,
                        tags = photo.tags,
                        uploadedAt = isoFormatter.format(photo.uploadedAt),
                        uploadedBy = photo.uploadedBy,
                        uploadedByName = photo.uploadedByName,
                        source = photo.source.name,
                        vehicleId = photo.vehicleId,
                        vehicleBrand = photo.vehicleBrand,
                        vehicleModel = photo.vehicleModel,
                        vehicleLicensePlate = photo.vehicleLicensePlate,
                        vehicleYear = photo.vehicleYear,
                        visitId = photo.visitId,
                        visitNumber = photo.visitNumber,
                        customerId = photo.customerId,
                        customerName = photo.customerName,
                        batchOrderEntryId = photo.batchOrderEntryId,
                        contractorName = photo.contractorName
                    )
                },
                pagination = GalleryPaginationResponse(
                    total = result.pagination.total,
                    page = result.pagination.page,
                    pageSize = result.pagination.pageSize,
                    totalPages = result.pagination.totalPages
                ),
                availableTags = result.availableTags
            )
        )
    }
}

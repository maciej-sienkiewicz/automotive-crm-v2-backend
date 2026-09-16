package pl.detailing.crm.visit.damagemap

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.domain.DamageAnnotationPoint
import pl.detailing.crm.visit.domain.DamageAnnotationStroke
import pl.detailing.crm.visit.domain.DamagePhoto
import pl.detailing.crm.visit.domain.DamagePoint

/**
 * Mapa uszkodzeń wizyty: odczyt punktów i ich aktualizacja w trakcie wizyty.
 *
 * Osobny kontroler, a nie kolejne dwie metody w [pl.detailing.crm.visit.VisitController]:
 * ten ma już 25 endpointów i 16 wstrzykniętych handlerów. Mapa uszkodzeń jest
 * zamkniętym tematem (punkty + plik + powiadomienie klienta), więc mieszka obok
 * swoich handlerów.
 */
@RestController
@RequestMapping("/api/visits/{visitId}/damage-map")
@RequiresPermission(Permission.VISITS_VIEW)
class VisitDamageMapController(
    private val getVisitDamageMapHandler: GetVisitDamageMapHandler,
    private val updateVisitDamageMapHandler: UpdateVisitDamageMapHandler
) {

    /**
     * Punkty, od których startuje edycja mapy.
     * GET /api/visits/{visitId}/damage-map
     */
    @GetMapping
    fun getDamageMap(@PathVariable visitId: String): ResponseEntity<VisitDamageMapResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val state = getVisitDamageMapHandler.handle(VisitId.fromString(visitId), principal.studioId)
        return ResponseEntity.ok(VisitDamageMapResponse.from(state))
    }

    /**
     * Nowa wersja mapy uszkodzeń.
     * PUT /api/visits/{visitId}/damage-map
     *
     * Ciało niesie CAŁĄ mapę, nie różnicę: edytor po stronie przeglądarki i tak
     * trzyma pełną listę punktów, a przysłanie kompletu znaczy, że usunięcie punktu
     * wygląda dokładnie tak samo jak jego dodanie — nie ma drugiej ścieżki, którą
     * trzeba pamiętać.
     */
    @PutMapping
    @RequiresPermission(Permission.VISITS_CREATE)
    fun updateDamageMap(
        @PathVariable visitId: String,
        @RequestBody request: UpdateVisitDamageMapRequest
    ): ResponseEntity<UpdateVisitDamageMapResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = updateVisitDamageMapHandler.handle(
            UpdateVisitDamageMapCommand(
                visitId = VisitId.fromString(visitId),
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                damagePoints = request.damagePoints.map { it.toDomain() },
                vehicleType = request.vehicleType,
                mode = request.mode,
                notifyCustomer = request.notifyCustomer,
                notifyMessage = request.notifyMessage?.trim()?.ifBlank { null }
            )
        )

        ResponseEntity.ok(UpdateVisitDamageMapResponse.from(result))
    }
}

// ─── Request / Response ───────────────────────────────────────────────────────

data class UpdateVisitDamageMapRequest(
    val damagePoints: List<DamageMapPointDto> = emptyList(),
    val vehicleType: String? = null,
    /** Brak = nowy plik: bezpieczniejszy wariant zostaje domyślnym. */
    val mode: DamageMapUpdateMode = DamageMapUpdateMode.NEW_FILE,
    /** „Poinformuj klienta o zmianach: TAK/NIE". */
    val notifyCustomer: Boolean = false,
    val notifyMessage: String? = null
)

data class DamageMapPointDto(
    val id: Int,
    val x: Double,
    val y: Double,
    val note: String? = null,
    val photos: List<DamageMapPhotoDto>? = null
) {
    fun toDomain(): DamagePoint = DamagePoint(
        id = id,
        x = x,
        y = y,
        note = note,
        photos = photos.orEmpty().map { photo ->
            DamagePhoto(
                photoId = photo.photoId,
                strokes = photo.strokes.orEmpty().map { stroke ->
                    DamageAnnotationStroke(
                        color = stroke.color,
                        width = stroke.width,
                        points = stroke.points.orEmpty().map { DamageAnnotationPoint(x = it.x, y = it.y) }
                    )
                }
            )
        }
    )
}

data class DamageMapPhotoDto(
    val photoId: String,
    val strokes: List<DamageMapStrokeDto>? = null
)

data class DamageMapStrokeDto(
    val color: String = "#EF4444",
    val width: Double = 1.0,
    val points: List<DamageMapAnnotationPointDto>? = null
)

data class DamageMapAnnotationPointDto(
    val x: Double,
    val y: Double
)

data class VisitDamageMapResponse(
    val damagePoints: List<DamageMapPointDto>,
    val vehicleType: String?,
    val revision: Int,
    val hasDocument: Boolean,
    val pointsRecoverable: Boolean,
    val updatedAt: String?,
    val updatedByName: String?
) {
    companion object {
        fun from(state: VisitDamageMapState) = VisitDamageMapResponse(
            damagePoints = state.damagePoints.map { point ->
                DamageMapPointDto(
                    id = point.id,
                    x = point.x,
                    y = point.y,
                    note = point.note,
                    photos = point.photos.map { photo ->
                        DamageMapPhotoDto(
                            photoId = photo.photoId,
                            strokes = photo.strokes.map { stroke ->
                                DamageMapStrokeDto(
                                    color = stroke.color,
                                    width = stroke.width,
                                    points = stroke.points.map { DamageMapAnnotationPointDto(it.x, it.y) }
                                )
                            }
                        )
                    }
                )
            },
            vehicleType = state.vehicleType,
            revision = state.revision,
            hasDocument = state.hasDocument,
            pointsRecoverable = state.pointsRecoverable,
            updatedAt = state.updatedAt?.toString(),
            updatedByName = state.updatedByName
        )
    }
}

data class UpdateVisitDamageMapResponse(
    val revision: Int,
    val pointsCount: Int,
    val documentId: String?,
    val fileName: String?,
    val documentGenerated: Boolean,
    val notification: DamageMapNotificationDto?
) {
    companion object {
        fun from(result: UpdateVisitDamageMapResult) = UpdateVisitDamageMapResponse(
            revision = result.revision,
            pointsCount = result.pointsCount,
            documentId = result.documentId,
            fileName = result.fileName,
            documentGenerated = result.documentGenerated,
            notification = result.notification?.let {
                DamageMapNotificationDto(
                    emailSent = it.emailSent,
                    smsSent = it.smsSent,
                    message = it.message
                )
            }
        )
    }
}

data class DamageMapNotificationDto(
    val emailSent: Boolean,
    val smsSent: Boolean,
    val message: String
)

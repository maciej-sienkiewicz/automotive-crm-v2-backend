package pl.detailing.crm.doortodoor

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.doortodoor.domain.DoorToDoor
import pl.detailing.crm.doortodoor.get.GetDoorToDoorCommand
import pl.detailing.crm.doortodoor.get.GetDoorToDoorHandler
import pl.detailing.crm.doortodoor.upsert.UpsertDoorToDoorCommand
import pl.detailing.crm.doortodoor.upsert.UpsertDoorToDoorHandler
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.VisitId
import java.time.Instant

@RestController
@RequestMapping("/api/visits/{visitId}/door-to-door")
@RequiresPermission(Permission.VISITS_VIEW)
class DoorToDoorController(
    private val getDoorToDoorHandler: GetDoorToDoorHandler,
    private val upsertDoorToDoorHandler: UpsertDoorToDoorHandler
) {

    /**
     * GET /api/visits/{visitId}/door-to-door
     */
    @GetMapping
    fun getDoorToDoor(
        @PathVariable visitId: String
    ): ResponseEntity<DoorToDoorResponse?> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = getDoorToDoorHandler.handle(
            GetDoorToDoorCommand(
                studioId = principal.studioId,
                visitId = VisitId.fromString(visitId)
            )
        )
        ResponseEntity.ok(result?.toResponse())
    }

    /**
     * PUT /api/visits/{visitId}/door-to-door
     */
    @PutMapping
    @RequiresPermission(Permission.VISITS_CREATE)
    fun upsertDoorToDoor(
        @PathVariable visitId: String,
        @RequestBody request: UpsertDoorToDoorRequest
    ): ResponseEntity<DoorToDoorResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = upsertDoorToDoorHandler.handle(
            UpsertDoorToDoorCommand(
                studioId = principal.studioId,
                visitId = VisitId.fromString(visitId),
                userId = principal.userId,
                userName = principal.name,
                pickupCity = request.pickupAddress.city,
                pickupStreet = request.pickupAddress.street,
                deliveryCity = request.deliveryAddress.city,
                deliveryStreet = request.deliveryAddress.street,
                notes = request.notes
            )
        )
        ResponseEntity.ok(result.toResponse())
    }
}

// ─── DTOs ────────────────────────────────────────────────────────────────────

data class UpsertDoorToDoorRequest(
    val pickupAddress: DoorToDoorAddressRequest,
    val deliveryAddress: DoorToDoorAddressRequest,
    val notes: String?
)

data class DoorToDoorAddressRequest(
    val city: String,
    val street: String
)

data class DoorToDoorResponse(
    val id: String,
    val visitId: String,
    val pickupAddress: DoorToDoorAddressResponse,
    val deliveryAddress: DoorToDoorAddressResponse,
    val notes: String?,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class DoorToDoorAddressResponse(
    val city: String,
    val street: String
)

private fun DoorToDoor.toResponse() = DoorToDoorResponse(
    id = id.toString(),
    visitId = visitId.toString(),
    pickupAddress = DoorToDoorAddressResponse(pickupAddress.city, pickupAddress.street),
    deliveryAddress = DoorToDoorAddressResponse(deliveryAddress.city, deliveryAddress.street),
    notes = notes,
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt
)

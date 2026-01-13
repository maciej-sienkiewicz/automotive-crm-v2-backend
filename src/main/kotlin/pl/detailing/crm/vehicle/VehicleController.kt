package pl.detailing.crm.vehicle

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.create.CreateVehicleCommand
import pl.detailing.crm.vehicle.create.CreateVehicleHandler
import pl.detailing.crm.vehicle.create.CreateVehicleRequest
import java.time.Instant

@RestController
@RequestMapping("/api/v1/vehicles")
class VehicleController(
    private val createVehicleHandler: CreateVehicleHandler
) {

    @PostMapping
    fun createVehicle(@RequestBody request: CreateVehicleRequest): ResponseEntity<VehicleResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create vehicles")
        }

        val engineType = try {
            EngineType.valueOf(request.engineType.uppercase())
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Invalid engine type: ${request.engineType}. Valid values are: GASOLINE, DIESEL, HYBRID, ELECTRIC")
        }

        val command = CreateVehicleCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            ownerIds = request.ownerIds.map { CustomerId.fromString(it) },
            licensePlate = request.licensePlate,
            brand = request.brand,
            model = request.model,
            vin = request.vin,
            yearOfProduction = request.yearOfProduction,
            color = request.color,
            paintType = request.paintType,
            engineType = engineType,
            currentMileage = request.currentMileage
        )

        val result = createVehicleHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(VehicleResponse(
                id = result.vehicleId.toString(),
                licensePlate = result.licensePlate,
                brand = result.brand,
                model = result.model,
                vin = result.vin,
                yearOfProduction = result.yearOfProduction,
                color = result.color,
                paintType = result.paintType,
                engineType = result.engineType.name.lowercase(),
                currentMileage = result.currentMileage,
                status = result.status.name.lowercase(),
                ownerIds = result.ownerIds.map { it.toString() },
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString()
            ))
    }
}

data class VehicleResponse(
    val id: String,
    val licensePlate: String,
    val brand: String,
    val model: String,
    val vin: String?,
    val yearOfProduction: Int,
    val color: String?,
    val paintType: String?,
    val engineType: String,
    val currentMileage: Int,
    val status: String,
    val ownerIds: List<String>,
    val createdAt: String,
    val updatedAt: String
)

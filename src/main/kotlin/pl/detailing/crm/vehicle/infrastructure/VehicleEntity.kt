package pl.detailing.crm.vehicle.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.domain.Vehicle
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "vehicles",
    indexes = [
        Index(name = "idx_vehicles_studio_license_plate", columnList = "studio_id, license_plate"),
        Index(name = "idx_vehicles_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_vehicles_created_by", columnList = "created_by"),
        Index(name = "idx_vehicles_updated_by", columnList = "updated_by")
    ]
)
class VehicleEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "license_plate", nullable = true, length = 20)
    var licensePlate: String?,

    @Column(name = "brand", nullable = false, length = 100)
    var brand: String,

    @Column(name = "model", nullable = false, length = 100)
    var model: String,

    @Column(name = "year_of_production", nullable = true)
    var yearOfProduction: Int?,

    @Column(name = "color", length = 50)
    var color: String?,

    @Column(name = "paint_type", length = 50)
    var paintType: String?,

    @Column(name = "current_mileage", nullable = false)
    var currentMileage: Int,

    @Column(name = "status", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var status: VehicleStatus,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): Vehicle = Vehicle(
        id = VehicleId(id),
        studioId = StudioId(studioId),
        licensePlate = licensePlate,
        brand = brand,
        model = model,
        yearOfProduction = yearOfProduction,
        color = color,
        paintType = paintType,
        currentMileage = currentMileage,
        status = status,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(vehicle: Vehicle): VehicleEntity = VehicleEntity(
            id = vehicle.id.value,
            studioId = vehicle.studioId.value,
            licensePlate = vehicle.licensePlate,
            brand = vehicle.brand,
            model = vehicle.model,
            yearOfProduction = vehicle.yearOfProduction,
            color = vehicle.color,
            paintType = vehicle.paintType,
            currentMileage = vehicle.currentMileage,
            status = vehicle.status,
            createdBy = vehicle.createdBy.value,
            updatedBy = vehicle.updatedBy.value,
            createdAt = vehicle.createdAt,
            updatedAt = vehicle.updatedAt
        )
    }
}

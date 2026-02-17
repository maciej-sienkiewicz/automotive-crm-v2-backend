package pl.detailing.crm.vehicle.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.domain.Vehicle
import pl.detailing.crm.vehicle.domain.VehiclePhoto
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
    var updatedAt: Instant = Instant.now(),

    @OneToMany(mappedBy = "vehicle", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    val photos: MutableList<VehiclePhotoEntity> = mutableListOf()
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

/**
 * Entity for vehicle photos stored directly on the vehicle (not associated with a specific visit)
 */
@Entity
@Table(
    name = "vehicle_photos",
    indexes = [
        Index(name = "idx_vehicle_photos_vehicle_id", columnList = "vehicle_id")
    ]
)
class VehiclePhotoEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id", nullable = false)
    var vehicle: VehicleEntity,

    @Column(name = "file_id", nullable = false, length = 255)
    val fileId: String,

    @Column(name = "file_name", nullable = false, length = 255)
    val fileName: String,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String?,

    @Column(name = "uploaded_at", nullable = false, columnDefinition = "timestamp with time zone")
    val uploadedAt: Instant
) {
    fun toDomain(): VehiclePhoto = VehiclePhoto(
        id = VehiclePhotoId(id),
        fileId = fileId,
        fileName = fileName,
        description = description,
        uploadedAt = uploadedAt
    )
}

/**
 * Entity for documents assigned directly to a vehicle
 */
@Entity
@Table(
    name = "vehicle_documents",
    indexes = [
        Index(name = "idx_vehicle_documents_vehicle_studio", columnList = "vehicle_id, studio_id"),
        Index(name = "idx_vehicle_documents_uploaded_at", columnList = "vehicle_id, uploaded_at")
    ]
)
class VehicleDocumentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "vehicle_id", nullable = false, columnDefinition = "uuid")
    val vehicleId: UUID,

    @Column(name = "name", nullable = false, length = 255)
    val name: String,

    @Column(name = "file_name", nullable = false, length = 255)
    val fileName: String,

    @Column(name = "file_id", nullable = false, length = 500)
    val fileId: String,

    @Column(name = "uploaded_at", nullable = false, columnDefinition = "timestamp with time zone")
    val uploadedAt: Instant,

    @Column(name = "uploaded_by", nullable = false, columnDefinition = "uuid")
    val uploadedBy: UUID,

    @Column(name = "uploaded_by_name", nullable = false, length = 200)
    val uploadedByName: String
)

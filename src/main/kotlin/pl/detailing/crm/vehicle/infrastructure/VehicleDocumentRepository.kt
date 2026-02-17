package pl.detailing.crm.vehicle.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VehicleDocumentRepository : JpaRepository<VehicleDocumentEntity, UUID> {

    @Query("SELECT d FROM VehicleDocumentEntity d WHERE d.vehicleId = :vehicleId AND d.studioId = :studioId ORDER BY d.uploadedAt DESC")
    fun findByVehicleIdAndStudioId(
        @Param("vehicleId") vehicleId: UUID,
        @Param("studioId") studioId: UUID
    ): List<VehicleDocumentEntity>

    @Query("SELECT d FROM VehicleDocumentEntity d WHERE d.id = :id AND d.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): VehicleDocumentEntity?
}

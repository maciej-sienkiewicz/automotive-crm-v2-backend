package pl.detailing.crm.vehicle.notes

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VehicleNoteRepository : JpaRepository<VehicleNoteEntity, UUID> {
    fun findByVehicleIdAndStudioIdOrderByCreatedAtDesc(vehicleId: UUID, studioId: UUID): List<VehicleNoteEntity>
    fun findByIdAndStudioId(id: UUID, studioId: UUID): VehicleNoteEntity?
}

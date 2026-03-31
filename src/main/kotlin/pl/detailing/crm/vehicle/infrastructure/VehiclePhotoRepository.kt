package pl.detailing.crm.vehicle.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VehiclePhotoRepository : JpaRepository<VehiclePhotoEntity, UUID> {

    @Query("""
        SELECT p FROM VehiclePhotoEntity p
        WHERE p.id = :photoId AND p.vehicle.studioId = :studioId
    """)
    fun findByIdAndStudioId(
        @Param("photoId") photoId: UUID,
        @Param("studioId") studioId: UUID
    ): VehiclePhotoEntity?
}

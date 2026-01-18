package pl.detailing.crm.vehicle.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VehicleRepository : JpaRepository<VehicleEntity, UUID> {

    @Query("SELECT v FROM VehicleEntity v WHERE v.id = :id AND v.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): VehicleEntity?

    @Query("SELECT v FROM VehicleEntity v WHERE v.studioId = :studioId")
    fun findByStudioId(@Param("studioId") studioId: UUID): List<VehicleEntity>

    @Query("SELECT v FROM VehicleEntity v WHERE v.studioId = :studioId AND v.status = 'ACTIVE'")
    fun findActiveByStudioId(@Param("studioId") studioId: UUID): List<VehicleEntity>

    @Query("""
        SELECT COUNT(v) > 0 FROM VehicleEntity v
        WHERE v.studioId = :studioId
        AND v.licensePlate = :licensePlate
        AND v.status != 'ARCHIVED'
    """)
    fun existsByStudioIdAndLicensePlate(
        @Param("studioId") studioId: UUID,
        @Param("licensePlate") licensePlate: String
    ): Boolean

    @Query("""
        SELECT v FROM VehicleEntity v
        WHERE v.studioId = :studioId
        AND v.licensePlate = :licensePlate
        AND v.status != 'ARCHIVED'
    """)
    fun findByStudioIdAndLicensePlate(
        @Param("studioId") studioId: UUID,
        @Param("licensePlate") licensePlate: String
    ): VehicleEntity?
}

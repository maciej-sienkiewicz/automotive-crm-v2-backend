package pl.detailing.crm.vehicle.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VehicleOwnerRepository : JpaRepository<VehicleOwnerEntity, VehicleOwnerKey> {

    @Query("SELECT vo FROM VehicleOwnerEntity vo WHERE vo.id.vehicleId = :vehicleId")
    fun findByVehicleId(@Param("vehicleId") vehicleId: UUID): List<VehicleOwnerEntity>

    @Query("SELECT vo FROM VehicleOwnerEntity vo WHERE vo.id.customerId = :customerId")
    fun findByCustomerId(@Param("customerId") customerId: UUID): List<VehicleOwnerEntity>

    @Query("""
        SELECT vo FROM VehicleOwnerEntity vo 
        WHERE vo.id.vehicleId = :vehicleId 
        AND vo.ownershipRole = 'PRIMARY'
    """)
    fun findPrimaryOwnerByVehicleId(@Param("vehicleId") vehicleId: UUID): VehicleOwnerEntity?

    @Query("""
        SELECT COUNT(vo) > 0 FROM VehicleOwnerEntity vo
        WHERE vo.id.vehicleId = :vehicleId
        AND vo.id.customerId = :customerId
    """)
    fun existsByVehicleIdAndCustomerId(
        @Param("vehicleId") vehicleId: UUID,
        @Param("customerId") customerId: UUID
    ): Boolean

    /**
     * Batch-load primary owners for a set of vehicle IDs.
     * Used by the gallery endpoint to resolve customer info for vehicle photos.
     */
    @Query("""
        SELECT vo FROM VehicleOwnerEntity vo
        WHERE vo.id.vehicleId IN :vehicleIds
        AND vo.ownershipRole = 'PRIMARY'
    """)
    fun findPrimaryOwnersByVehicleIds(@Param("vehicleIds") vehicleIds: List<UUID>): List<VehicleOwnerEntity>

    @Query("""
        SELECT DISTINCT vo.id.customerId FROM VehicleOwnerEntity vo
        JOIN VehicleEntity v ON vo.id.vehicleId = v.id
        WHERE v.studioId = :studioId
        AND (:brand IS NULL OR LOWER(v.brand) LIKE LOWER(CONCAT('%', :brand, '%')))
        AND (:model IS NULL OR LOWER(v.model) LIKE LOWER(CONCAT('%', :model, '%')))
    """)
    fun findCustomerIdsByVehicleFilter(
        @Param("studioId") studioId: UUID,
        @Param("brand") brand: String?,
        @Param("model") model: String?
    ): List<UUID>
}

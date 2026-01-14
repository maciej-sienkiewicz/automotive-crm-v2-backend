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
}

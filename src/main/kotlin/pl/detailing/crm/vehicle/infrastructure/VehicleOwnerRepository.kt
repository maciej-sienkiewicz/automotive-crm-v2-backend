package pl.detailing.crm.vehicle.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

/** Projekcja dla wsadowego liczenia pojazdów klientów. */
interface CustomerVehicleCount {
    val customerId: UUID
    val vehicleCount: Long
}

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

    /**
     * Liczba żywych pojazdów klienta.
     *
     * Tabela powiązań właścicieli nic nie wie o soft-delete, więc samo
     * policzenie jej wierszy wlicza do statystyk pojazdy usunięte —
     * dlatego dołączamy pojazd i odsiewamy archiwum.
     */
    @Query("""
        SELECT COUNT(vo) FROM VehicleOwnerEntity vo
        JOIN VehicleEntity v ON vo.id.vehicleId = v.id
        WHERE vo.id.customerId = :customerId
        AND v.studioId = :studioId
        AND v.deletedAt IS NULL
        AND v.status != 'ARCHIVED'
    """)
    fun countActiveVehiclesByCustomerId(
        @Param("customerId") customerId: UUID,
        @Param("studioId") studioId: UUID
    ): Long

    /**
     * Wsadowy wariant [countActiveVehiclesByCustomerId] — lista klientów
     * liczyłaby inaczej pojazdy zapytaniem na klienta (N+1).
     * Klienci bez żywych pojazdów nie mają wiersza w wyniku.
     */
    @Query("""
        SELECT vo.id.customerId AS customerId, COUNT(vo) AS vehicleCount
        FROM VehicleOwnerEntity vo
        JOIN VehicleEntity v ON vo.id.vehicleId = v.id
        WHERE vo.id.customerId IN :customerIds
        AND v.studioId = :studioId
        AND v.deletedAt IS NULL
        AND v.status != 'ARCHIVED'
        GROUP BY vo.id.customerId
    """)
    fun countActiveVehiclesByCustomerIds(
        @Param("customerIds") customerIds: List<UUID>,
        @Param("studioId") studioId: UUID
    ): List<CustomerVehicleCount>

    @Query("""
        SELECT DISTINCT vo.id.customerId FROM VehicleOwnerEntity vo
        JOIN VehicleEntity v ON vo.id.vehicleId = v.id
        WHERE v.studioId = :studioId
        AND v.deletedAt IS NULL
        AND v.status != 'ARCHIVED'
        AND (:brand IS NULL OR LOWER(v.brand) LIKE LOWER(CONCAT('%', :brand, '%')))
        AND (:model IS NULL OR LOWER(v.model) LIKE LOWER(CONCAT('%', :model, '%')))
    """)
    fun findCustomerIdsByVehicleFilter(
        @Param("studioId") studioId: UUID,
        @Param("brand") brand: String?,
        @Param("model") model: String?
    ): List<UUID>
}

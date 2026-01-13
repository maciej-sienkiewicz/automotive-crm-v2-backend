package pl.detailing.crm.appointment.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AppointmentRepository : JpaRepository<AppointmentEntity, UUID> {

    @Query("SELECT a FROM AppointmentEntity a WHERE a.id = :id AND a.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): AppointmentEntity?

    @Query("SELECT a FROM AppointmentEntity a WHERE a.studioId = :studioId")
    fun findByStudioId(@Param("studioId") studioId: UUID): List<AppointmentEntity>

    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.studioId = :studioId
        AND a.status <> 'CANCELLED'
        AND a.endDateTime >= :startDateTime
        AND a.startDateTime <= :endDateTime
    """)
    fun findOverlappingAppointments(
        @Param("studioId") studioId: UUID,
        @Param("startDateTime") startDateTime: Instant,
        @Param("endDateTime") endDateTime: Instant
    ): List<AppointmentEntity>

    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.studioId = :studioId
        AND a.customerId = :customerId
        ORDER BY a.startDateTime DESC
    """)
    fun findByStudioIdAndCustomerId(
        @Param("studioId") studioId: UUID,
        @Param("customerId") customerId: UUID
    ): List<AppointmentEntity>

    @Query("""
        SELECT a FROM AppointmentEntity a
        WHERE a.studioId = :studioId
        AND a.vehicleId = :vehicleId
        ORDER BY a.startDateTime DESC
    """)
    fun findByStudioIdAndVehicleId(
        @Param("studioId") studioId: UUID,
        @Param("vehicleId") vehicleId: UUID
    ): List<AppointmentEntity>
}

package pl.detailing.crm.appointment.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AppointmentColorRepository : JpaRepository<AppointmentColorEntity, UUID> {

    @Query("SELECT ac FROM AppointmentColorEntity ac WHERE ac.id = :id AND ac.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): AppointmentColorEntity?

    @Query("SELECT ac FROM AppointmentColorEntity ac WHERE ac.studioId = :studioId")
    fun findByStudioId(@Param("studioId") studioId: UUID): List<AppointmentColorEntity>
}

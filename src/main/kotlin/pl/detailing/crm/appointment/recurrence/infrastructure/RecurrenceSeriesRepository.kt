package pl.detailing.crm.appointment.recurrence.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface RecurrenceSeriesRepository : JpaRepository<RecurrenceSeriesEntity, UUID> {
    fun findByIdAndStudioId(id: UUID, studioId: UUID): RecurrenceSeriesEntity?
}

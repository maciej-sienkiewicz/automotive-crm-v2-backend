package pl.detailing.crm.calendarevent

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Wydarzenie w kalendarzu studia: urlop, szkolenie, dostawa, remont — wszystko,
 * co zajmuje dni w grafiku, a nie jest wizytą ani rezerwacją.
 *
 * Zakres trzymamy na dniach i domykamy z obu stron: [endDate] to ostatni dzień,
 * w którym wydarzenie trwa. Wersja ekskluzywna („do, ale bez") jest wygodna dla
 * FullCalendara i tylko dla niego — w bazie czytelniejsza jest ta, którą widzi
 * użytkownik przy zapisie.
 */
@Entity
@Table(
    name = "calendar_events",
    indexes = [Index(name = "idx_calendar_events_studio_range", columnList = "studio_id,start_date,end_date")]
)
class CalendarEventEntity(
    @Id
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "title", nullable = false, length = 200)
    var title: String,

    @Column(name = "description", columnDefinition = "text")
    var description: String? = null,

    @Column(name = "start_date", nullable = false)
    var startDate: LocalDate,

    @Column(name = "end_date", nullable = false)
    var endDate: LocalDate,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_by_name", nullable = false, length = 200)
    val createdByName: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

@Repository
interface CalendarEventRepository : JpaRepository<CalendarEventEntity, UUID> {

    /** Wydarzenia, które zahaczają o widoczny zakres — także te zaczęte wcześniej. */
    @Query(
        """
        SELECT e FROM CalendarEventEntity e
        WHERE e.studioId = :studioId
          AND e.startDate <= :to
          AND e.endDate >= :from
        ORDER BY e.startDate, e.title
        """
    )
    fun findInRange(
        @Param("studioId") studioId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate
    ): List<CalendarEventEntity>

    fun findByIdAndStudioId(id: UUID, studioId: UUID): CalendarEventEntity?
}

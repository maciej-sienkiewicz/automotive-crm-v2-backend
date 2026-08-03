package pl.detailing.crm.worktime.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "work_time_entries",
    indexes = [
        Index(name = "idx_work_time_entries_user_id", columnList = "user_id"),
        Index(name = "idx_work_time_entries_user_date", columnList = "user_id, date")
    ]
)
class WorkTimeEntryEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "date", nullable = false)
    val date: LocalDate,

    @Column(name = "minutes", nullable = false)
    var minutes: Int,

    @Column(name = "note", length = 500)
    var note: String? = null,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)

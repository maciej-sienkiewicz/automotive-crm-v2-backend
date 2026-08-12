package pl.detailing.crm.visit.technicalnote

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "visit_technical_note_history")
class VisitTechnicalNoteHistoryEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "content", columnDefinition = "TEXT")
    val content: String?,

    @Column(name = "action", nullable = false, length = 10)
    val action: String,

    @Column(name = "changed_by_id", nullable = false, columnDefinition = "uuid")
    val changedById: UUID,

    @Column(name = "changed_by_name", nullable = false, length = 255)
    val changedByName: String,

    @Column(name = "changed_at", nullable = false)
    val changedAt: Instant = Instant.now()
)

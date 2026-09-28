package pl.detailing.crm.visit.servicechecks

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Usługa wizyty odhaczona jako zrobiona. Wiersz istnieje = odhaczona; odznaczenie go
 * usuwa, a ślad obu zostaje w historii wizyty (audyt). Tylko znak dla ludzi na hali -
 * nic w systemie od tego nie zależy (V169).
 */
@Entity
@Table(name = "visit_service_checks")
class VisitServiceCheckEntity(
    @Id
    @Column(name = "service_item_id", columnDefinition = "uuid", nullable = false)
    val serviceItemId: UUID,

    @Column(name = "studio_id", columnDefinition = "uuid", nullable = false)
    val studioId: UUID,

    @Column(name = "visit_id", columnDefinition = "uuid", nullable = false)
    val visitId: UUID,

    @Column(name = "checked_at", nullable = false)
    val checkedAt: Instant,

    @Column(name = "checked_by", columnDefinition = "uuid", nullable = false)
    val checkedBy: UUID,

    @Column(name = "checked_by_name", length = 255)
    val checkedByName: String?
)

@Repository
interface VisitServiceCheckRepository : JpaRepository<VisitServiceCheckEntity, UUID> {
    fun findByStudioIdAndVisitId(studioId: UUID, visitId: UUID): List<VisitServiceCheckEntity>
}

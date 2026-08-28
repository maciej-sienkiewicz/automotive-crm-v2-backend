package pl.detailing.crm.dashboard.hints

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
import java.util.UUID

@Entity
@Table(
    name = "dashboard_hint_dismissals",
    indexes = [Index(name = "idx_dashboard_hint_dismissals_user", columnList = "user_id")]
)
class DashboardHintDismissalEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "hint_key", nullable = false, length = 120)
    val hintKey: String,

    /** NULL = zamknięte na zawsze (upsell); data = drzemka do tego momentu. */
    @Column(name = "snooze_until", columnDefinition = "timestamp with time zone")
    var snoozeUntil: Instant? = null,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
)

@Repository
interface DashboardHintDismissalRepository : JpaRepository<DashboardHintDismissalEntity, UUID> {

    fun findByUserId(userId: UUID): List<DashboardHintDismissalEntity>

    fun findByUserIdAndHintKey(userId: UUID, hintKey: String): DashboardHintDismissalEntity?

    /** Drzemki, które minęły, można sprzątać — trzymanie ich tylko rośnie tabelę. */
    @Query("DELETE FROM DashboardHintDismissalEntity d WHERE d.snoozeUntil IS NOT NULL AND d.snoozeUntil < :now")
    @org.springframework.data.jpa.repository.Modifying
    fun deleteExpired(@Param("now") now: Instant): Int
}

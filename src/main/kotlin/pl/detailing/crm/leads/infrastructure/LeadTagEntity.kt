package pl.detailing.crm.leads.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.io.Serializable
import java.time.Instant
import java.util.UUID

/** Klucz złożony: jeden tag może wisieć na leadzie tylko raz. */
data class LeadTagId(
    val leadId: UUID = UUID(0, 0),
    val tagCode: String = ""
) : Serializable

@Entity
@Table(
    name = "lead_tags",
    indexes = [Index(name = "idx_lead_tags_code", columnList = "tag_code")]
)
@IdClass(LeadTagId::class)
class LeadTagEntity(
    @Id
    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    @Id
    @Column(name = "tag_code", nullable = false, length = 50)
    val tagCode: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Repository
interface LeadTagRepository : JpaRepository<LeadTagEntity, LeadTagId> {

    fun findByLeadId(leadId: UUID): List<LeadTagEntity>

    fun findByLeadIdIn(leadIds: Collection<UUID>): List<LeadTagEntity>

    @Modifying
    @Query("DELETE FROM LeadTagEntity t WHERE t.leadId = :leadId")
    fun deleteByLeadId(@Param("leadId") leadId: UUID)
}

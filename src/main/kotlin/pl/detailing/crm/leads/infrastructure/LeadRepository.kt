package pl.detailing.crm.leads.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.LeadStatus
import java.time.Instant
import java.util.UUID

@Repository
interface LeadRepository : JpaRepository<LeadEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): LeadEntity?

    fun findByAppointmentId(appointmentId: UUID): LeadEntity?

    fun findByThreadId(threadId: UUID): LeadEntity?

    @Query(
        """SELECT l FROM LeadEntity l
           WHERE l.studioId = :studioId
             AND (:status IS NULL OR l.status = :status)
             AND (:query IS NULL
                  OR LOWER(l.contactIdentifier) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%')
                  OR LOWER(COALESCE(l.customerName, '')) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%'))
           ORDER BY l.createdAt DESC"""
    )
    fun search(
        @Param("studioId") studioId: UUID,
        @Param("status") status: LeadStatus?,
        @Param("query") query: String?,
        pageable: Pageable
    ): Page<LeadEntity>

    fun findByStudioIdAndContactIdentifierOrderByCreatedAtDesc(
        studioId: UUID,
        contactIdentifier: String
    ): List<LeadEntity>

    fun findByStudioIdAndCreatedAtBetween(
        studioId: UUID,
        from: Instant,
        to: Instant
    ): List<LeadEntity>

    fun countByStudioIdAndStatus(studioId: UUID, status: LeadStatus): Long
}

@Repository
interface LeadServiceItemRepository : JpaRepository<LeadServiceItemEntity, UUID> {

    fun findByLeadIdOrderByCreatedAtAsc(leadId: UUID): List<LeadServiceItemEntity>

    fun findByLeadIdIn(leadIds: Collection<UUID>): List<LeadServiceItemEntity>

    @Modifying
    @Query("DELETE FROM LeadServiceItemEntity i WHERE i.leadId = :leadId")
    fun deleteByLeadId(@Param("leadId") leadId: UUID)
}

@Repository
interface LeadStatusHistoryRepository : JpaRepository<LeadStatusHistoryEntity, UUID> {

    fun findByLeadIdOrderByCreatedAtAsc(leadId: UUID): List<LeadStatusHistoryEntity>

    fun findByStudioIdAndCreatedAtBetween(
        studioId: UUID,
        from: Instant,
        to: Instant
    ): List<LeadStatusHistoryEntity>
}

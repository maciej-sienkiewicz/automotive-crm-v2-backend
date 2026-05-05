package pl.detailing.crm.smscredits.infrastructure

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface SmsCreditTransactionJpaRepository : JpaRepository<SmsCreditTransactionEntity, UUID> {

    @Query("SELECT t FROM SmsCreditTransactionEntity t WHERE t.studioId = :studioId ORDER BY t.createdAt DESC")
    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID, pageable: Pageable): List<SmsCreditTransactionEntity>

    fun countByStudioId(studioId: UUID): Long
}

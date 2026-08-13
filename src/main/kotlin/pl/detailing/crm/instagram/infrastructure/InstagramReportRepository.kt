package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.*

@Repository
interface InstagramReportRepository : JpaRepository<InstagramReportEntity, UUID> {

    fun findByStudioIdAndPeriodStart(studioId: UUID, periodStart: LocalDate): InstagramReportEntity?

    fun findByStudioIdOrderByPeriodStartDesc(studioId: UUID, pageable: Pageable): List<InstagramReportEntity>

    fun deleteByCreatedAtBefore(cutoff: Instant): Long
}

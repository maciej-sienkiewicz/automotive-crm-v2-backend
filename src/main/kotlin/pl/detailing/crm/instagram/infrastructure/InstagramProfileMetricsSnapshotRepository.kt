package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.*

@Repository
interface InstagramProfileMetricsSnapshotRepository : JpaRepository<InstagramProfileMetricsSnapshotEntity, UUID> {

    fun existsByProfileIdAndSnapshotDate(profileId: UUID, snapshotDate: LocalDate): Boolean

    fun findByProfileIdAndSnapshotDateAfterOrderBySnapshotDateAsc(
        profileId: UUID,
        cutoffDate: LocalDate
    ): List<InstagramProfileMetricsSnapshotEntity>

    fun deleteByProfileId(profileId: UUID)
}

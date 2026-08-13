package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.*

@Repository
interface InstagramProfileStatsWeeklyRepository : JpaRepository<InstagramProfileStatsWeeklyEntity, UUID> {

    fun findByProfileIdAndWeekStartGreaterThanEqual(
        profileId: UUID,
        fromWeek: LocalDate
    ): List<InstagramProfileStatsWeeklyEntity>

    fun findByProfileIdInAndWeekStartGreaterThanEqual(
        profileIds: Collection<UUID>,
        fromWeek: LocalDate
    ): List<InstagramProfileStatsWeeklyEntity>

    fun deleteByProfileId(profileId: UUID)
}

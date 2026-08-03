package pl.detailing.crm.worktime.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
interface WorkTimeEntryRepository : JpaRepository<WorkTimeEntryEntity, UUID> {

    @Query("SELECT e FROM WorkTimeEntryEntity e WHERE e.userId = :userId AND e.date BETWEEN :from AND :to ORDER BY e.date")
    fun findByUserIdAndDateBetween(
        @Param("userId") userId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate
    ): List<WorkTimeEntryEntity>

    fun findByUserIdAndDate(userId: UUID, date: LocalDate): WorkTimeEntryEntity?

    @Query("SELECT e FROM WorkTimeEntryEntity e WHERE e.userId = :userId AND e.studioId = :studioId AND e.date BETWEEN :from AND :to ORDER BY e.date")
    fun findByUserIdAndStudioIdAndDateBetween(
        @Param("userId") userId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate
    ): List<WorkTimeEntryEntity>

    @Query("SELECT SUM(e.minutes) FROM WorkTimeEntryEntity e WHERE e.userId = :userId AND e.date BETWEEN :from AND :to")
    fun sumMinutesByUserIdAndDateBetween(
        @Param("userId") userId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate
    ): Int?
}

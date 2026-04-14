package pl.detailing.crm.employee.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.WorkTimeStatus
import java.time.LocalDate
import java.util.UUID

@Repository
interface WorkTimeEntryRepository : JpaRepository<WorkTimeEntryEntity, UUID> {

    @Query("SELECT w FROM WorkTimeEntryEntity w WHERE w.id = :id AND w.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): WorkTimeEntryEntity?

    @Query("""
        SELECT w FROM WorkTimeEntryEntity w
        WHERE w.employeeId = :employeeId AND w.studioId = :studioId
        ORDER BY w.date DESC, w.startTime DESC
    """)
    fun findByEmployeeIdAndStudioId(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID
    ): List<WorkTimeEntryEntity>

    @Query("""
        SELECT w FROM WorkTimeEntryEntity w
        WHERE w.employeeId = :employeeId AND w.studioId = :studioId
        AND w.date >= :from AND w.date <= :to
        ORDER BY w.date ASC, w.startTime ASC
    """)
    fun findByEmployeeIdAndDateRange(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate
    ): List<WorkTimeEntryEntity>

    @Query("""
        SELECT w FROM WorkTimeEntryEntity w
        WHERE w.employeeId = :employeeId AND w.studioId = :studioId
        AND w.date >= :from AND w.date <= :to
        AND w.status = :status
        ORDER BY w.date ASC
    """)
    fun findByEmployeeIdAndDateRangeAndStatus(
        @Param("employeeId") employeeId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate,
        @Param("status") status: WorkTimeStatus
    ): List<WorkTimeEntryEntity>

    @Query("""
        SELECT w FROM WorkTimeEntryEntity w
        WHERE w.studioId = :studioId AND w.date >= :from AND w.date <= :to
        ORDER BY w.date ASC, w.employeeId ASC
    """)
    fun findByStudioIdAndDateRange(
        @Param("studioId") studioId: UUID,
        @Param("from") from: LocalDate,
        @Param("to") to: LocalDate
    ): List<WorkTimeEntryEntity>

    @Query("""
        SELECT w FROM WorkTimeEntryEntity w
        WHERE w.studioId = :studioId AND w.status = 'PENDING'
        ORDER BY w.date DESC
    """)
    fun findPendingByStudioId(@Param("studioId") studioId: UUID): List<WorkTimeEntryEntity>
}

package pl.detailing.crm.employee.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BonusEntryRepository : JpaRepository<BonusEntryEntity, UUID> {

    fun findByEmployeeIdAndStudioIdOrderByPeriodDesc(
        employeeId: UUID,
        studioId: UUID
    ): List<BonusEntryEntity>

    fun findByEmployeeIdAndStudioIdAndPeriodOrderByCreatedAtAsc(
        employeeId: UUID,
        studioId: UUID,
        period: String
    ): List<BonusEntryEntity>

    fun findByIdAndStudioId(id: UUID, studioId: UUID): BonusEntryEntity?
}

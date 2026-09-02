package pl.detailing.crm.worktime

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditActorResolver
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodRepository
import java.time.YearMonth

/**
 * Cross-Tenant Data Access — karty czasu pracy.
 *
 * Luka: `approvePeriod` / `returnPeriod` / `getPeriodDetail` szukały karty po samym
 * `userId`, ignorując `studioId` z sesji. Menedżer studia A mógł zatwierdzić (i zamrozić)
 * albo zwrócić kartę pracownika studia B, a `GET` zdradzał jej status i notatkę.
 */
class TeamWorkTimeCrossTenantTest {

    private val entryRepository = mockk<WorkTimeEntryRepository>(relaxed = true)
    private val periodRepository = mockk<WorkTimePeriodRepository>(relaxed = true)
    private val service = WorkTimeService(
        entryRepository,
        periodRepository,
        mockk<PermissionCheckService>(relaxed = true),
        mockk<AuditService>(relaxed = true),
        mockk<AuditActorResolver>(relaxed = true)
    )

    private val studioA = StudioId.random()
    private val studioB = StudioId.random()
    private val managerA = UserId.random()
    private val employeeB = UserId.random()
    private val month = YearMonth.of(2026, 8)

    private fun foreignSubmittedPeriod() = WorkTimePeriodEntity(
        userId = employeeB.value, studioId = studioB.value, period = month.toString(), status = PeriodStatus.SUBMITTED
    ).also { period ->
        // The unscoped legacy lookup would find it…
        every { periodRepository.findByUserIdAndPeriod(employeeB.value, month.toString()) } returns period
        // …the tenant-scoped one, queried with studio A, must not.
        every { periodRepository.findByUserIdAndStudioIdAndPeriod(employeeB.value, studioA.value, month.toString()) } returns null
    }

    @Test
    fun `manager of studio A cannot approve a period of studio B - 404 and no write`() {
        val period = foreignSubmittedPeriod()

        assertThrows<EntityNotFoundException> {
            service.approvePeriod(employeeB, studioA, month, approvedBy = managerA)
        }

        verify(exactly = 0) { periodRepository.save(any()) }
        verify(exactly = 0) { periodRepository.findByUserIdAndPeriod(any(), any()) }
        assert(period.status == PeriodStatus.SUBMITTED)
    }

    @Test
    fun `manager of studio A cannot return a period of studio B`() {
        foreignSubmittedPeriod()

        assertThrows<EntityNotFoundException> {
            service.returnPeriod(employeeB, studioA, month, returnedBy = managerA, note = "pwned")
        }
        verify(exactly = 0) { periodRepository.save(any()) }
    }

    @Test
    fun `manager of studio A cannot read a period detail of studio B`() {
        foreignSubmittedPeriod()

        val detail = service.getPeriodDetail(employeeB, studioA, month)

        // Nothing of the foreign period leaks: the detail reads as an empty, never-submitted card.
        verify(exactly = 0) { periodRepository.findByUserIdAndPeriod(any(), any()) }
        assert(detail.period == month.toString())
    }

    @Test
    fun `nobody approves their own card`() {
        assertThrows<ForbiddenException> {
            service.approvePeriod(managerA, studioA, month, approvedBy = managerA)
        }
        verify(exactly = 0) { periodRepository.findByUserIdAndStudioIdAndPeriod(any(), any(), any()) }
    }
}

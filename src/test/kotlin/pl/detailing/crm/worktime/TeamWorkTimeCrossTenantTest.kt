package pl.detailing.crm.worktime

import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodEntity
import java.time.YearMonth

/**
 * Cross-Tenant Data Access — karty czasu pracy.
 *
 * Luka: `approvePeriod` / `returnPeriod` / `getPeriodDetail` szukały karty po samym
 * `userId`, ignorując `studioId` z sesji. Menedżer studia A mógł zatwierdzić (i zamrozić)
 * albo zwrócić kartę pracownika studia B, a `GET` zdradzał jej status i notatkę.
 */
class TeamWorkTimeCrossTenantTest {

    private val studioA = StudioId.random()
    private val kit = WorkTimeTestKit(studioA)
    private val periodRepository = kit.periods
    private val service = kit.service

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

    // ── Lista miesięczna: te same granice dla nowych endpointów ──────────────

    @Test
    fun `month overview of studio A never lists a user or card of studio B`() {
        val own = kit.user("Anna", "Nowak")
        val foreign = kit.user("Obcy", "Pracownik", studioId = studioB)
        kit.period(foreign, month, PeriodStatus.SUBMITTED)

        val overview = kit.monthService.overview(studioA, managerA, month)

        assertEquals(listOf(own.id.toString()), overview.employees.map { it.userId })
        assertEquals(0, overview.counts.submitted)
    }

    @Test
    fun `card detail of a user from studio B is 404`() {
        val foreign = kit.user("Obcy", "Pracownik", studioId = studioB)
        kit.period(foreign, month, PeriodStatus.SUBMITTED)

        assertThrows<EntityNotFoundException> {
            kit.monthService.cardDetail(studioA, managerA, month, UserId(foreign.id))
        }
    }

    @Test
    fun `approve card endpoint of studio A cannot approve a card of studio B`() {
        val foreign = kit.user("Obcy", "Pracownik", studioId = studioB)
        val period = kit.period(foreign, month, PeriodStatus.SUBMITTED)

        assertThrows<EntityNotFoundException> {
            kit.monthService.approveCard(studioA, managerA, "Menedżer A", month, UserId(foreign.id))
        }
        assertEquals(PeriodStatus.SUBMITTED, period.status)
    }

    @Test
    fun `bulk approve and remind skip users of studio B without touching their cards`() {
        val foreign = kit.user("Obcy", "Pracownik", studioId = studioB)
        val period = kit.period(foreign, month, PeriodStatus.SUBMITTED)

        val approved = kit.monthService.bulkApprove(studioA, managerA, "Menedżer A", month, listOf(foreign.id.toString()))
        val reminded = kit.monthService.remind(studioA, managerA, month, listOf(foreign.id.toString()))

        assertTrue(approved.approved.isEmpty())
        assertEquals(listOf(foreign.id.toString()), approved.skipped.map { it.userId })
        assertTrue(reminded.reminded.isEmpty())
        assertEquals(PeriodStatus.SUBMITTED, period.status)
        assertNull(period.remindedAt)
        assertTrue(kit.events.isEmpty(), "Ani push o zatwierdzeniu, ani przypomnienie: ${kit.events}")
    }

    @Test
    fun `pending count of studio A does not count cards of studio B`() {
        val foreign = kit.user("Obcy", "Pracownik", studioId = studioB)
        kit.period(foreign, month, PeriodStatus.SUBMITTED)

        assertEquals(0, kit.monthService.pendingCount(studioA, managerA).submittedCards)
    }
}

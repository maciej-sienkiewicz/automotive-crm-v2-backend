package pl.detailing.crm.employee.leaverequest.domain

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveEntity
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.employee.leaverequest.LeaveRequestFixtures
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.LocalDate
import java.util.UUID

/** Reguły z kontraktu — każdy błąd wskazuje pole, przy którym front go pokaże. */
class LeaveRequestValidatorTest {

    private val requests = mockk<LeaveRequestRepository>()
    private val leaves = mockk<EmployeeLeaveRepository>()
    private val validator = LeaveRequestValidator(requests, leaves)

    private val studio = StudioId.random()
    private val employeeId = UUID.randomUUID()
    private val requestId = UUID.randomUUID()
    private val today = LocalDate.of(2026, 9, 30) // środa

    init {
        every { requests.findOverlappingOfEmployee(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { requests.findOnDemandOfEmployee(any(), any(), any(), any(), any(), any()) } returns emptyList()
        every { leaves.findOverlappingOfEmployee(any(), any(), any(), any()) } returns emptyList()
    }

    private fun draft(
        type: LeaveType = LeaveType.ANNUAL,
        onDemand: Boolean = false,
        start: LocalDate = today.plusDays(5),
        end: LocalDate = start,
        reason: String? = null
    ) = LeaveRequestDraft(type, onDemand, start, end, reason)

    private fun fieldOf(block: () -> Unit): String? = assertThrows<ValidationException> { block() }.field

    @Test
    fun `end before start points at endDate`() {
        assertEquals("endDate", fieldOf { validator.parse("ANNUAL", false, today.plusDays(3), today.plusDays(2), null) })
    }

    @Test
    fun `sick leave is not a request`() {
        assertEquals("leaveType", fieldOf { validator.parse("SICK", false, today, today, null) })
    }

    @Test
    fun `special leave requires a reason`() {
        assertEquals("reason", fieldOf { validator.parse("SPECIAL", false, today.plusDays(1), today.plusDays(1), "   ") })
        validator.parse("SPECIAL", false, today.plusDays(1), today.plusDays(1), "Ślub brata")
    }

    @Test
    fun `on demand only with annual leave`() {
        assertEquals("onDemand", fieldOf { validator.parse("UNPAID", true, today, today, null) })
    }

    @Test
    fun `past start is refused and today only on demand`() {
        assertEquals("startDate", fieldOf { validator.checkTerm(draft(start = today.minusDays(1)), today) })
        assertEquals("startDate", fieldOf { validator.checkTerm(draft(start = today), today) })
        assertEquals(1, validator.checkTerm(draft(onDemand = true, start = today), today))
    }

    @Test
    fun `range without working days is refused`() {
        // 03–04.10.2026 to sobota i niedziela.
        assertEquals("startDate", fieldOf {
            validator.checkTerm(draft(start = LocalDate.of(2026, 10, 3), end = LocalDate.of(2026, 10, 4)), today)
        })
    }

    @Test
    fun `overlap with own pending request is refused`() {
        val other = LeaveRequestFixtures.request(studio, employeeId, null, status = LeaveRequestStatus.PENDING)
        every { requests.findOverlappingOfEmployee(studio.value, employeeId, LeaveRequestStatus.BLOCKING, any(), any(), requestId) } returns listOf(other)
        val e = assertThrows<ValidationException> { validator.checkAgainstExisting(studio.value, employeeId, requestId, draft()) }
        assertEquals("startDate", e.field)
        assertEquals(true, e.message!!.contains(other.number))
    }

    @Test
    fun `overlap with an entry in employee_leaves (for example L4) is refused`() {
        every { leaves.findOverlappingOfEmployee(studio.value, employeeId, any(), any()) } returns listOf(
            EmployeeLeaveEntity(
                id = UUID.randomUUID(), studioId = studio.value, employeeId = employeeId, leaveType = LeaveType.SICK,
                startDate = today.plusDays(4), endDate = today.plusDays(8), note = null, createdBy = UUID.randomUUID()
            )
        )
        val e = assertThrows<ValidationException> { validator.checkAgainstExisting(studio.value, employeeId, requestId, draft()) }
        assertEquals("startDate", e.field)
        assertEquals(true, e.message!!.contains("zwolnienie lekarskie"))
    }

    @Test
    fun `on demand leave is limited to four working days per calendar year`() {
        // Wykorzystane: 3 dni na żądanie (pn–śr 02–04.03.2026).
        val used = LeaveRequestFixtures.request(
            studio, employeeId, null, status = LeaveRequestStatus.APPROVED, onDemand = true,
            start = LocalDate.of(2026, 3, 2), end = LocalDate.of(2026, 3, 4)
        )
        every { requests.findOnDemandOfEmployee(studio.value, employeeId, LeaveRequestStatus.BLOCKING, any(), any(), requestId) } returns listOf(used)

        // Czwarty dzień przechodzi…
        validator.checkAgainstExisting(studio.value, employeeId, requestId, draft(onDemand = true, start = LocalDate.of(2026, 10, 5)))
        // …piąty i szósty już nie.
        val e = assertThrows<ValidationException> {
            validator.checkAgainstExisting(
                studio.value, employeeId, requestId,
                draft(onDemand = true, start = LocalDate.of(2026, 10, 5), end = LocalDate.of(2026, 10, 6))
            )
        }
        assertEquals("onDemand", e.field)
    }

    @Test
    fun `reason longer than what fits on the document is refused`() {
        assertEquals("reason", fieldOf { validator.parse("ANNUAL", false, today.plusDays(1), today.plusDays(1), "x".repeat(251)) })
    }
}

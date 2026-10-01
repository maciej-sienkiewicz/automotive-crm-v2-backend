package pl.detailing.crm.dashboard.hints

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.employee.leaverequest.LeaveRequestFixtures
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.infrastructure.RoleEntity
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Karty czasu pracy na Tablicy: „N kart czeka na zatwierdzenie" dla tych, którzy mogą
 * zdecydować, i „kto nie uzupełnił" — od teraz także dla EMPLOYEES_MANAGE, nie tylko
 * dla właściciela.
 */
class WorktimeCardsHintTest {

    private val periods = mockk<WorkTimePeriodRepository>(relaxed = true)
    private val users = mockk<UserRepository>(relaxed = true)
    private val roles = mockk<RoleRepository>(relaxed = true)
    private val permissions = mockk<PermissionCheckService>()
    private val handler = GetDashboardHintsHandler(
        userRepository = users,
        roleRepository = roles,
        workTimePeriodRepository = periods,
        instagramReportRepository = mockk(relaxed = true),
        commThreadRepository = mockk(relaxed = true),
        ksefCredentialsRepository = mockk(relaxed = true),
        awaitingWorkService = mockk(relaxed = true),
        areaDiscovery = mockk(relaxed = true),
        visitRepository = mockk(relaxed = true),
        dismissalRepository = mockk(relaxed = true),
        permissionCheckService = permissions,
        objectMapper = mockk(relaxed = true),
        leaveRequestRepository = mockk(relaxed = true)
    )
    private val studio = StudioId.random()
    private val manager = LeaveRequestFixtures.principal(studio)

    init {
        every { permissions.hasPermission(any(), any(), any()) } returns false
        every { permissions.hasPermission(manager.userId, studio, Permission.EMPLOYEES_MANAGE) } returns true
    }

    private fun submitted(period: String, userId: UUID = UUID.randomUUID(), at: String = "2026-09-30T10:00:00Z") =
        WorkTimePeriodEntity(
            userId = userId, studioId = studio.value, period = period, status = PeriodStatus.SUBMITTED,
            submittedAt = Instant.parse(at)
        )

    @Test
    fun `polska odmiana podpowiedzi`() {
        assertEquals("1 karta czasu pracy czeka na zatwierdzenie", GetDashboardHintsHandler.worktimeCardsPendingText(1))
        assertEquals("2 karty czasu pracy czekają na zatwierdzenie", GetDashboardHintsHandler.worktimeCardsPendingText(2))
        assertEquals("4 karty czasu pracy czekają na zatwierdzenie", GetDashboardHintsHandler.worktimeCardsPendingText(4))
        assertEquals("5 kart czasu pracy czeka na zatwierdzenie", GetDashboardHintsHandler.worktimeCardsPendingText(5))
        assertEquals("12 kart czasu pracy czeka na zatwierdzenie", GetDashboardHintsHandler.worktimeCardsPendingText(12))
        assertEquals("22 karty czasu pracy czekają na zatwierdzenie", GetDashboardHintsHandler.worktimeCardsPendingText(22))
    }

    @Test
    fun `menedzer widzi karty do decyzji bez wlasnej, z linkiem do najstarszego miesiaca`() {
        every { periods.findByStudioIdAndStatus(studio.value, PeriodStatus.SUBMITTED) } returns listOf(
            submitted("2026-09", at = "2026-09-30T10:00:00Z"),
            submitted("2026-08", at = "2026-09-01T10:00:00Z"),
            submitted("2026-09", userId = manager.userId.value)
        )

        val hint = runBlocking { handler.handle(manager) }.single { it.kind == DashboardHintKind.WORKTIME_CARDS_PENDING }

        assertEquals("2 karty czasu pracy czekają na zatwierdzenie", hint.text)
        assertEquals("Przejrzyj", hint.action?.label)
        assertEquals(DashboardHintActionType.NAVIGATE, hint.action?.type)
        assertEquals("/employees/worktime?period=2026-08", hint.action?.url)
        assertEquals("WORKTIME_CARDS_PENDING_${Instant.parse("2026-09-30T10:00:00Z").epochSecond}", hint.key)
    }

    @Test
    fun `bez EMPLOYEES_MANAGE i bez kart do decyzji nie ma podpowiedzi`() {
        val worker = LeaveRequestFixtures.principal(studio)
        every { periods.findByStudioIdAndStatus(studio.value, PeriodStatus.SUBMITTED) } returns listOf(submitted("2026-09"))

        assertTrue(runBlocking { handler.handle(worker) }.none { it.kind == DashboardHintKind.WORKTIME_CARDS_PENDING })

        every { periods.findByStudioIdAndStatus(studio.value, PeriodStatus.SUBMITTED) } returns
            listOf(submitted("2026-09", userId = manager.userId.value))
        assertTrue(runBlocking { handler.handle(manager) }.none { it.kind == DashboardHintKind.WORKTIME_CARDS_PENDING })
    }

    @Test
    fun `WORKTIME_MISSING widzi takze menedzer z EMPLOYEES_MANAGE`() {
        // 28.09.2026 — okno końca miesiąca.
        handler.clock = Clock.fixed(Instant.parse("2026-09-28T10:00:00Z"), ZoneId.of("Europe/Warsaw"))
        val role = mockk<RoleEntity> {
            every { id } returns UUID.randomUUID()
            every { trackWorkTime } returns true
        }
        every { roles.findByStudioId(studio.value) } returns listOf(role)
        fun user(first: String, last: String) = mockk<UserEntity> {
            every { id } returns UUID.randomUUID()
            every { firstName } returns first
            every { lastName } returns last
            every { customRoleId } returns role.id
        }
        val anna = user("Anna", "Nowak")
        val bob = user("Bob", "Budowlany")
        every { users.findActiveByStudioId(studio.value) } returns listOf(anna, bob)
        every { periods.findByUserIdAndPeriod(anna.id, "2026-09") } returns null
        every { periods.findByUserIdAndPeriod(bob.id, "2026-09") } returns null

        val hints = runBlocking { handler.handle(manager) }

        val missing = hints.single { it.kind == DashboardHintKind.WORKTIME_MISSING }
        assertTrue("Anna Nowak" in missing.text && "Bob Budowlany" in missing.text, missing.text)
        // Wyłączenie funkcji to decyzja właściciela — menedżer go nie dostaje.
        assertTrue(hints.none { it.kind == DashboardHintKind.WORKTIME_UNUSED })
    }
}

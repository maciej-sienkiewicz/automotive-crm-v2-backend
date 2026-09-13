package pl.detailing.crm.dashboard.hints

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.leads.analytics.AwaitingLeadDto
import pl.detailing.crm.leads.analytics.AwaitingWorkDto
import pl.detailing.crm.leads.analytics.AwaitingWorkService
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.UUID

/**
 * Skupione na jednej regule — „pieniądze czekają na Twoją odpowiedź". Pozostałe
 * źródła są wyciszone (nie-owner, brak digestu, zero nieprzeczytanych), więc gdy
 * w pasku pojawia się cokolwiek, jest to właśnie ta podpowiedź.
 */
class GetDashboardHintsHandlerTest {

    private val permissionCheckService = mockk<PermissionCheckService>()
    private val awaitingWorkService = mockk<AwaitingWorkService>()
    private val commThreadRepository = mockk<CommThreadRepository>(relaxed = true)

    private val handler = GetDashboardHintsHandler(
        userRepository = mockk(relaxed = true),
        roleRepository = mockk(relaxed = true),
        workTimePeriodRepository = mockk(relaxed = true),
        instagramReportRepository = mockk(relaxed = true),
        commThreadRepository = commThreadRepository,
        ksefCredentialsRepository = mockk(relaxed = true),
        awaitingWorkService = awaitingWorkService,
        visitRepository = mockk(relaxed = true),
        dismissalRepository = mockk(relaxed = true),
        permissionCheckService = permissionCheckService,
        objectMapper = mockk(relaxed = true)
    )

    private val principal = UserPrincipal(
        userId = UserId(UUID.randomUUID()),
        studioId = StudioId(UUID.randomUUID()),
        isOwner = false,
        email = "wlasciciel@studio.pl",
        fullName = "Właściciel Studia",
        phoneNumber = "600100200"
    )

    private fun grantLeads() {
        every { permissionCheckService.hasPermission(any(), any(), any()) } returns false
        every { permissionCheckService.hasPermission(any(), any(), Permission.LEADS_MANAGE) } returns true
    }

    @Test
    fun `builds the awaiting hint word for word, red and clickable`() {
        grantLeads()
        every { commThreadRepository.countUnread(any()) } returns 0L
        every { awaitingWorkService.awaitingWork(principal.studioId) } returns AwaitingWorkDto(
            value = 1_728_300, // 17 283 zł w groszach
            count = 6,
            oldest = AwaitingLeadDto(
                leadId = UUID.randomUUID().toString(),
                name = "Maciej Sienkiewicz",
                vehicle = "Porsche 911",
                value = 320_000,
                waitingDays = 8
            )
        )

        val hints = runBlocking { handler.handle(principal) }

        assertEquals(1, hints.size)
        val hint = hints.single()
        assertEquals(DashboardHintKind.LEADS_AWAITING, hint.kind)
        assertEquals("LEADS_AWAITING", hint.key)
        assertEquals(DashboardHintSeverity.CRITICAL, hint.severity)
        assertEquals(
            "Czeka na Ciebie 17 283 zł w 6 rozmowach, w których piłka jest po Twojej stronie. " +
                "Najdłużej czeka Maciej Sienkiewicz - Porsche 911, czeka 8 dni.",
            hint.text
        )
        assertEquals(DashboardHintActionType.NAVIGATE, hint.action?.type)
        assertEquals("/leads?awaiting=1", hint.action?.url)
        assertEquals("Odpisz im", hint.action?.label)
    }

    @Test
    fun `awaiting money outranks everything else in the bar`() {
        grantLeads()
        // Nieprzeczytane maile są ponad progiem — też chcą się pokazać.
        every { commThreadRepository.countUnread(any()) } returns 50L
        every { awaitingWorkService.awaitingWork(principal.studioId) } returns AwaitingWorkDto(
            value = 500_000,
            count = 2,
            oldest = AwaitingLeadDto(
                leadId = UUID.randomUUID().toString(),
                name = "Anna Kowalska",
                vehicle = null,
                value = 250_000,
                waitingDays = 3
            )
        )

        val hints = runBlocking { handler.handle(principal) }

        // Obie podpowiedzi są w grze, ale pieniądze z piłką po naszej stronie idą pierwsze.
        assertTrue(hints.size >= 2)
        assertEquals(DashboardHintKind.LEADS_AWAITING, hints.first().kind)
        assertTrue(hints.any { it.kind == DashboardHintKind.UNREAD_MAIL })
    }

    @Test
    fun `singular conversation uses the singular locative form`() {
        grantLeads()
        every { commThreadRepository.countUnread(any()) } returns 0L
        every { awaitingWorkService.awaitingWork(principal.studioId) } returns AwaitingWorkDto(
            value = 40_000,
            count = 1,
            oldest = AwaitingLeadDto(
                leadId = UUID.randomUUID().toString(),
                name = "Jan Nowak",
                vehicle = "Audi RS6",
                value = 40_000,
                waitingDays = 1
            )
        )

        val hint = runBlocking { handler.handle(principal) }.single()

        assertEquals(
            "Czeka na Ciebie 400 zł w 1 rozmowie, w których piłka jest po Twojej stronie. " +
                "Najdłużej czeka Jan Nowak - Audi RS6, czeka 1 dzień.",
            hint.text
        )
    }

    @Test
    fun `no leads module means the hint never runs`() {
        every { permissionCheckService.hasPermission(any(), any(), any()) } returns false
        every { commThreadRepository.countUnread(any()) } returns 0L

        val hints = runBlocking { handler.handle(principal) }

        assertTrue(hints.none { it.kind == DashboardHintKind.LEADS_AWAITING })
        // Bez modułu nie ruszamy nawet rachunku — to cudza baza leadów.
        verify(exactly = 0) { awaitingWorkService.awaitingWork(any()) }
    }

    @Test
    fun `nothing awaiting means no hint`() {
        grantLeads()
        every { commThreadRepository.countUnread(any()) } returns 0L
        every { awaitingWorkService.awaitingWork(principal.studioId) } returns AwaitingWorkDto(0, 0, null)

        val hints = runBlocking { handler.handle(principal) }

        assertTrue(hints.none { it.kind == DashboardHintKind.LEADS_AWAITING })
    }
}

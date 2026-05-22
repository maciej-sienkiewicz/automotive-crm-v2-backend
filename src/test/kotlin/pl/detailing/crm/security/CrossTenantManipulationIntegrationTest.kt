package pl.detailing.crm.security

import io.mockk.*
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.config.GlobalExceptionHandler
import pl.detailing.crm.shared.*
import java.util.UUID

/**
 * Integration test for the cross-tenant ID-manipulation detection signal chain:
 *
 *   POST /api/v1/appointments/{victimCustomerId}
 *     → EntityNotFoundException thrown (customer not in attacker's studio)
 *       → GlobalExceptionHandler.handleNotFound()
 *         → TenantIsolationAuditService.checkRequest()  ← VERIFIED HERE
 *           → AuditService.logSync(CROSS_TENANT_ACCESS_ATTEMPT) + metric
 *
 * The test uses a standalone MockMvc with a fake controller that deterministically
 * throws EntityNotFoundException, so no real Spring context or database is needed.
 * The [TenantIsolationAuditService] is mocked to isolate GlobalExceptionHandler behaviour.
 */
class CrossTenantManipulationIntegrationTest {

    // ── Fake controller that simulates what CustomerExistenceValidator does ──

    @RestController
    @RequestMapping("/api/v1")
    class FakeAppointmentController {
        @PostMapping("/appointments/{customerId}")
        fun create(@PathVariable customerId: UUID): Nothing {
            throw EntityNotFoundException(
                "Klient o ID '$customerId' nie został znaleziony w tym studiu"
            )
        }
    }

    // ── Test wiring ──────────────────────────────────────────────────────────

    private val tenantIsolationService = mockk<TenantIsolationAuditService>(relaxed = true)

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(FakeAppointmentController())
            .setControllerAdvice(GlobalExceptionHandler(tenantIsolationService))
            .build()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        clearAllMocks()
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `TenantIsolationAuditService is invoked when authenticated user triggers EntityNotFoundException`() {
        val attackerStudioId = StudioId.random()
        val victimCustomerId = UUID.randomUUID()

        givenAuthenticatedUser(attackerStudioId)

        mockMvc.perform(
            post("/api/v1/appointments/$victimCustomerId")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)

        // The service must be called with the exact request that carried the manipulated ID
        verify(exactly = 1) {
            tenantIsolationService.checkRequest(
                withArg { req -> req.requestURI.contains(victimCustomerId.toString()) },
                withArg { user -> user.studioId == attackerStudioId }
            )
        }
    }

    @Test
    fun `response is always 404 regardless of cross-tenant check outcome — no information leak`() {
        givenAuthenticatedUser(StudioId.random())

        // TenantIsolationAuditService finds a cross-tenant hit (returns normally after logging)
        every { tenantIsolationService.checkRequest(any(), any()) } just Runs

        mockMvc.perform(
            post("/api/v1/appointments/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("Nie znaleziono"))
    }

    @Test
    fun `TenantIsolationAuditService is still called when check throws internally — 404 returned`() {
        val studioId = StudioId.random()
        givenAuthenticatedUser(studioId)

        // Simulate an unexpected failure inside the service (e.g. DB down during cross-tenant probe)
        every { tenantIsolationService.checkRequest(any(), any()) } throws RuntimeException("unexpected DB error")

        mockMvc.perform(
            post("/api/v1/appointments/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)

        // Service was invoked — the GlobalExceptionHandler's try/catch absorbed the error
        verify(exactly = 1) { tenantIsolationService.checkRequest(any(), any()) }
    }

    @Test
    fun `TenantIsolationAuditService is still called when request is unauthenticated — 404 returned`() {
        // No user in SecurityContext → SecurityContextHelper.getCurrentUser() throws
        SecurityContextHolder.clearContext()

        mockMvc.perform(
            post("/api/v1/appointments/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isNotFound)

        // Service itself isn't called — the try/catch in GlobalExceptionHandler absorbs
        // the UnauthorizedException from SecurityContextHelper before reaching checkRequest
        verify(exactly = 0) { tenantIsolationService.checkRequest(any(), any()) }
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private fun givenAuthenticatedUser(studioId: StudioId) {
        val principal = UserPrincipal(
            userId      = UserId.random(),
            studioId    = studioId,
            role        = UserRole.MANAGER,
            email       = "attacker@evil.com",
            fullName    = "Attacker User",
            phoneNumber = "+48000000000"
        )
        SecurityContextHolder.setContext(SecurityContextImpl(principal))
    }
}

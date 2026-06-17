package pl.detailing.crm.security

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.mockk.*
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.observability.MetricsTags
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.UUID

/**
 * Unit tests for [TenantIsolationAuditService].
 *
 * Scenarios covered:
 *  1. Cross-tenant access via URI path → audit log + metric fired
 *  2. Cross-tenant access via query parameter → audit log + metric fired
 *  3. Legitimate access (own tenant entity) → no alert
 *  4. Entity doesn't exist anywhere → no alert
 *  5. URI without UUIDs → EntityManager never queried
 *  6. DB error during check → swallowed silently, no exception propagated
 */
class TenantIsolationAuditServiceTest {

    private val entityManager = mockk<EntityManager>()
    private val auditService  = mockk<AuditService>(relaxed = true)
    private val meterRegistry = mockk<MeterRegistry>()
    private val counter       = mockk<Counter>(relaxed = true)

    private val service = TenantIsolationAuditService(entityManager, auditService, meterRegistry)

    @BeforeEach
    fun setUp() {
        every { meterRegistry.counter(any(), *anyVararg()) } returns counter
    }

    // ── 1. Cross-tenant access via URI path ──────────────────────────────────

    @Test
    fun `detects cross-tenant attempt when customer ID in path belongs to another studio`() {
        val studioA          = StudioId.random()
        val studioB          = StudioId.random()
        val victimCustomerId = UUID.randomUUID()

        val request = mockRequest("/api/v1/appointments/$victimCustomerId", "POST")
        givenEntityExistsInTable("customers", victimCustomerId, studioB.value)
        givenTablesReturnEmpty("vehicles", "visits", "appointments", forId = victimCustomerId)

        val captured = slot<LogAuditCommand>()
        every { auditService.logSync(capture(captured)) } just Runs

        service.checkRequest(request, buildUser(studioA))

        verify(exactly = 1) { auditService.logSync(any()) }
        with(captured.captured) {
            assertEquals(AuditAction.CROSS_TENANT_ACCESS_ATTEMPT, action)
            assertEquals(AuditModule.SECURITY, module)
            assertEquals(studioA, studioId)
            assertEquals(victimCustomerId.toString(), entityId)
            assertEquals(studioB.toString(), metadata["actual_studio_id"])
            assertEquals("customer", metadata["entity_type"])
            assertEquals("POST", metadata["request_method"])
        }
        verify { counter.increment() }
    }

    // ── 2. Cross-tenant access via query parameter ───────────────────────────

    @Test
    fun `detects cross-tenant attempt when victim ID passed as query parameter`() {
        val studioA          = StudioId.random()
        val studioB          = StudioId.random()
        val victimCustomerId = UUID.randomUUID()

        val request = mockk<HttpServletRequest> {
            every { requestURI }   returns "/api/v1/appointments"
            every { method }       returns "POST"
            every { parameterMap } returns mapOf("customerId" to arrayOf(victimCustomerId.toString()))
        }
        givenEntityExistsInTable("customers", victimCustomerId, studioB.value)
        givenTablesReturnEmpty("vehicles", "visits", "appointments", forId = victimCustomerId)
        every { auditService.logSync(any()) } just Runs

        service.checkRequest(request, buildUser(studioA))

        verify(exactly = 1) { auditService.logSync(any()) }
    }

    // ── 3. Legitimate access — own tenant ────────────────────────────────────

    @Test
    fun `no alert when entity belongs to the requesting studio`() {
        val studioId   = StudioId.random()
        val customerId = UUID.randomUUID()

        val request = mockRequest("/api/v1/customers/$customerId", "GET")
        givenEntityExistsInTable("customers", customerId, studioId.value)

        service.checkRequest(request, buildUser(studioId))

        verify(exactly = 0) { auditService.logSync(any()) }
        verify(exactly = 0) { counter.increment() }
    }

    // ── 4. Entity does not exist at all ──────────────────────────────────────

    @Test
    fun `no alert when UUID exists in no table`() {
        val nonExistentId = UUID.randomUUID()
        val request       = mockRequest("/api/v1/customers/$nonExistentId", "GET")

        givenTablesReturnEmpty("customers", "vehicles", "visits", "appointments", forId = nonExistentId)

        service.checkRequest(request, buildUser(StudioId.random()))

        verify(exactly = 0) { auditService.logSync(any()) }
    }

    // ── 5. URI without UUIDs ──────────────────────────────────────────────────

    @Test
    fun `EntityManager never queried when URI contains no UUIDs`() {
        val request = mockRequest("/api/v1/health", "GET")

        service.checkRequest(request, buildUser(StudioId.random()))

        verify(exactly = 0) { entityManager.createNativeQuery(any()) }
        verify(exactly = 0) { auditService.logSync(any()) }
    }

    // ── 6. DB failure is swallowed ────────────────────────────────────────────

    @Test
    fun `does not propagate exception when EntityManager throws`() {
        val id      = UUID.randomUUID()
        val request = mockRequest("/api/v1/customers/$id", "GET")

        val query = mockk<Query>()
        every { entityManager.createNativeQuery(any<String>()) } returns query
        every { query.setParameter(any<String>(), any()) }       returns query
        every { query.resultList } throws RuntimeException("DB unavailable")

        assertDoesNotThrow { service.checkRequest(request, buildUser(StudioId.random())) }
        verify(exactly = 0) { auditService.logSync(any()) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildUser(studioId: StudioId) = UserPrincipal(
        userId      = UserId.random(),
        studioId    = studioId,
        isOwner      = false,
        email       = "attacker@test.com",
        fullName    = "Test User",
        phoneNumber = "+48111222333"
    )

    private fun mockRequest(uri: String, method: String) = mockk<HttpServletRequest> {
        every { requestURI }   returns uri
        every { this@mockk.method } returns method
        every { parameterMap } returns emptyMap()
    }

    private fun givenEntityExistsInTable(table: String, id: UUID, studioId: UUID) {
        val query = mockk<Query>()
        every { entityManager.createNativeQuery(match { it.contains("FROM $table") }) } returns query
        every { query.setParameter("id", id) } returns query
        every { query.resultList }             returns listOf(studioId)
    }

    private fun givenTablesReturnEmpty(vararg tables: String, forId: UUID) {
        for (table in tables) {
            val query = mockk<Query>()
            every { entityManager.createNativeQuery(match { it.contains("FROM $table") }) } returns query
            every { query.setParameter("id", forId) } returns query
            every { query.resultList }                returns emptyList<Any>()
        }
    }
}

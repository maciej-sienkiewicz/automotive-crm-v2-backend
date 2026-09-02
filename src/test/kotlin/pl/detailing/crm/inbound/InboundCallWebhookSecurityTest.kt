package pl.detailing.crm.inbound

import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.config.GlobalExceptionHandler
import pl.detailing.crm.inbound.accept.AcceptCallHandler
import pl.detailing.crm.inbound.register.RegisterInboundCallCommand
import pl.detailing.crm.inbound.register.RegisterInboundCallHandler
import pl.detailing.crm.inbound.register.RegisterInboundCallResult
import pl.detailing.crm.inbound.reject.RejectCallHandler
import pl.detailing.crm.inbound.update.UpdateCallHandler
import pl.detailing.crm.security.TenantIsolationAuditService
import pl.detailing.crm.shared.CallId
import pl.detailing.crm.shared.CallLogStatus
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant

/**
 * Broken Authentication + cross-tenant write — webhook połączeń przychodzących.
 *
 * Luka: `POST /api/v1/inbound/calls` był anonimowy (permitAll) i zapisywał lead do
 * `studioRepository.findAll()[0]` — pierwszego studia w bazie. Każdy z internetu mógł
 * zalewać cudzy pipeline leadów fałszywymi połączeniami.
 */
class InboundCallWebhookSecurityTest {

    private val registerHandler = mockk<RegisterInboundCallHandler>()
    private val studioRepository = mockk<StudioRepository>()
    private val studioA = StudioId.random()
    private val studioB = StudioId.random()
    private lateinit var mockMvc: MockMvc

    private fun controller(secret: String) = InboundController(
        registerHandler, mockk<UpdateCallHandler>(), mockk<AcceptCallHandler>(), mockk<RejectCallHandler>(),
        studioRepository, secret
    )

    private fun build(secret: String) {
        mockMvc = MockMvcBuilders.standaloneSetup(controller(secret))
            .setControllerAdvice(GlobalExceptionHandler(mockk<TenantIsolationAuditService>(relaxed = true)))
            .build()
    }

    @BeforeEach
    fun setUp() {
        SecurityContextHolder.clearContext()
        coEvery { registerHandler.handle(any()) } answers {
            val cmd = firstArg<RegisterInboundCallCommand>()
            RegisterInboundCallResult(CallId.random(), LeadId.random(), cmd.phoneNumber, cmd.callerName, CallLogStatus.PENDING, Instant.now())
        }
        every { studioRepository.existsById(studioA.value) } returns true
        every { studioRepository.existsById(studioB.value) } returns true
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        clearAllMocks()
    }

    private val body = """{"phoneNumber":"+48600100200","callerName":"Jan","note":null,"receivedAt":null,"studioId":"${studioB.value}"}"""

    @Test
    fun `anonymous call without secret is 401 and nothing is registered`() {
        build(secret = "s3cr3t")
        mockMvc.perform(post("/api/v1/inbound/calls").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isUnauthorized)
        coVerify(exactly = 0) { registerHandler.handle(any()) }
        verify(exactly = 0) { studioRepository.findAll() }
    }

    @Test
    fun `anonymous call with a wrong secret is 401`() {
        build(secret = "s3cr3t")
        mockMvc.perform(
            post("/api/v1/inbound/calls").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(InboundController.HEADER_INBOUND_SECRET, "guess")
        ).andExpect(status().isUnauthorized)
        coVerify(exactly = 0) { registerHandler.handle(any()) }
    }

    @Test
    fun `fail closed - with no secret configured the anonymous path is disabled entirely`() {
        build(secret = "")
        mockMvc.perform(
            post("/api/v1/inbound/calls").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(InboundController.HEADER_INBOUND_SECRET, "")
        ).andExpect(status().isUnauthorized)
        coVerify(exactly = 0) { registerHandler.handle(any()) }
    }

    @Test
    fun `telephony integration with the right secret registers into the named studio`() {
        build(secret = "s3cr3t")
        mockMvc.perform(
            post("/api/v1/inbound/calls").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(InboundController.HEADER_INBOUND_SECRET, "s3cr3t")
        ).andExpect(status().isCreated)
        coVerify(exactly = 1) { registerHandler.handle(match { it.studioId == studioB }) }
    }

    @Test
    fun `integration must name a studio - missing studioId is 400`() {
        build(secret = "s3cr3t")
        mockMvc.perform(
            post("/api/v1/inbound/calls").contentType(MediaType.APPLICATION_JSON)
                .content("""{"phoneNumber":"+48600100200","callerName":null,"note":null,"receivedAt":null}""")
                .header(InboundController.HEADER_INBOUND_SECRET, "s3cr3t")
        ).andExpect(status().isBadRequest)
        coVerify(exactly = 0) { registerHandler.handle(any()) }
    }

    @Test
    fun `a logged-in user cannot redirect a call into another studio - studioId in body is ignored`() {
        build(secret = "s3cr3t")
        SecurityContextHolder.setContext(SecurityContextImpl(UserPrincipal(
            userId = UserId.random(), studioId = studioA, isOwner = false,
            email = "u@a.pl", fullName = "User A", phoneNumber = "+48000000000"
        )))

        mockMvc.perform(post("/api/v1/inbound/calls").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated)

        coVerify(exactly = 1) { registerHandler.handle(match { it.studioId == studioA }) }
        coVerify(exactly = 0) { registerHandler.handle(match { it.studioId == studioB }) }
    }

    @Test
    fun `oversized or blank fields are rejected with 400`() {
        build(secret = "s3cr3t")
        val huge = "A".repeat(5_000)
        mockMvc.perform(
            post("/api/v1/inbound/calls").contentType(MediaType.APPLICATION_JSON)
                .content("""{"phoneNumber":"","callerName":"$huge","note":"$huge","receivedAt":null,"studioId":"${studioB.value}"}""")
                .header(InboundController.HEADER_INBOUND_SECRET, "s3cr3t")
        ).andExpect(status().isBadRequest)
        coVerify(exactly = 0) { registerHandler.handle(any()) }
    }
}

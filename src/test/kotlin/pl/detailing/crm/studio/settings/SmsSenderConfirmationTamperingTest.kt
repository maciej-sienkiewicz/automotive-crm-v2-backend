package pl.detailing.crm.studio.settings

import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.config.GlobalExceptionHandler
import pl.detailing.crm.platform.PlatformStudioAdminController
import pl.detailing.crm.security.TenantIsolationAuditService
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.infrastructure.SmsAutomationConfigEntity
import pl.detailing.crm.smscampaigns.infrastructure.SmsAutomationConfigJpaRepository
import pl.detailing.crm.studio.infrastructure.StudioRepository
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

/**
 * Mass Assignment / Parameter Tampering — flaga `smsApiNameConfirmed`.
 *
 * Luka: właściciel studia mógł sam ustawić `smsApiNameConfirmed=true` przez
 * `PATCH /api/v1/company/sms-sender-config`, a ta flaga decyduje, czy SMS-y wychodzą
 * z nagłówkiem nadawcy (np. "InPost"). Weryfikacja "po naszej stronie" była fikcją.
 * Teraz studio może flagę tylko wycofać; nadaje ją operator przez /api/internal.
 */
class SmsSenderConfirmationTamperingTest {

    private val smsConfigRepository = mockk<SmsAutomationConfigJpaRepository>()
    private val studioRepository = mockk<StudioRepository>()
    private val studioId = StudioId.random()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val advice = GlobalExceptionHandler(mockk<TenantIsolationAuditService>(relaxed = true))
        mockMvc = MockMvcBuilders
            .standaloneSetup(
                CompanyController(
                    mockk<StudioSettingsRepository>(relaxed = true), studioRepository, smsConfigRepository,
                    mockk<S3Client>(relaxed = true), mockk<S3Presigner>(relaxed = true), "bucket"
                ),
                PlatformStudioAdminController(studioRepository, smsConfigRepository)
            )
            .setControllerAdvice(advice)
            .build()
        every { smsConfigRepository.save(any()) } answers { firstArg() }
        SecurityContextHolder.setContext(SecurityContextImpl(UserPrincipal(
            userId = UserId.random(), studioId = studioId, isOwner = true,
            email = "owner@studio.pl", fullName = "Owner", phoneNumber = "+48000000000"
        )))
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        clearAllMocks()
    }

    private fun pendingConfig() = SmsAutomationConfigEntity.fromDomain(SmsAutomationConfig.defaultFor(studioId)).apply {
        smsSenderName = "InPost"
        smsApiNameConfirmed = false
    }.also { every { smsConfigRepository.findByStudioId(studioId.value) } returns it }

    @Test
    fun `owner cannot self-confirm the sender name - 403 and nothing saved`() {
        val config = pendingConfig()

        mockMvc.perform(
            patch("/api/v1/company/sms-sender-config").contentType(MediaType.APPLICATION_JSON)
                .content("""{"smsApiNameConfirmed":true}""")
        )
            .andExpect(status().isForbidden)

        verify(exactly = 0) { smsConfigRepository.save(any()) }
        assert(!config.smsApiNameConfirmed)
    }

    @Test
    fun `owner may withdraw a confirmation`() {
        val config = pendingConfig().apply { smsApiNameConfirmed = true }

        mockMvc.perform(
            patch("/api/v1/company/sms-sender-config").contentType(MediaType.APPLICATION_JSON)
                .content("""{"smsApiNameConfirmed":false}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.smsApiNameConfirmed").value(false))
            .andExpect(jsonPath("$.effectiveSenderName").doesNotExist())

        assert(!config.smsApiNameConfirmed)
    }

    @Test
    fun `platform operator confirms through the internal surface`() {
        val config = pendingConfig()
        every { studioRepository.existsById(studioId.value) } returns true

        mockMvc.perform(
            put("/api/internal/studios/${studioId.value}/sms-sender-confirmation").contentType(MediaType.APPLICATION_JSON)
                .content("""{"confirmed":true}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.confirmed").value(true))
            .andExpect(jsonPath("$.senderName").value("InPost"))

        assert(config.smsApiNameConfirmed)
    }

    @Test
    fun `operator endpoint validates its input`() {
        every { studioRepository.existsById(studioId.value) } returns true
        mockMvc.perform(
            put("/api/internal/studios/${studioId.value}/sms-sender-confirmation").contentType(MediaType.APPLICATION_JSON)
                .content("""{}""")
        ).andExpect(status().isBadRequest)
    }
}

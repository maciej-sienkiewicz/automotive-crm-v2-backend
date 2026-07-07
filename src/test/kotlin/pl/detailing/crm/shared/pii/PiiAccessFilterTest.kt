package pl.detailing.crm.shared.pii

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.PII_MASK
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId

/**
 * End-to-end slice of the non-bypassable masking chain:
 * request → PiiAccessFilter (decision + header) → controller → Jackson (PiiMaskingModule).
 *
 * The controller below has NO masking logic whatsoever — exactly like production
 * controllers after the migration — yet the response is masked unless the resolved
 * context grants access. This is the "canary": if someone unregisters the module or
 * the filter, these tests fail.
 */
class PiiAccessFilterTest {

    data class CustomerDto(
        val id: String,
        @Pii val firstName: String?,
        @Pii val phone: String?,
        val companyName: String?
    )

    @RestController
    @RequestMapping
    class FakeController {
        private fun payload() = CustomerDto("c1", "Jan", "501502503", "ACME")

        @GetMapping("/api/v1/customers-fake")
        fun staff(): CustomerDto = payload()

        @GetMapping("/api/tablet/context-fake")
        fun tablet(): CustomerDto = payload()
    }

    private val permissionCheckService = mockk<PermissionCheckService>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        val converter = MappingJackson2HttpMessageConverter(
            ObjectMapper().registerModule(PiiMaskingModule())
        )
        mockMvc = MockMvcBuilders
            .standaloneSetup(FakeController())
            .setMessageConverters(converter)
            .addFilters(PiiAccessFilter(permissionCheckService))
            .build()
    }

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        PiiAccessContext.clear()
        clearAllMocks()
    }

    private fun authenticate(hasPermission: Boolean) {
        val principal = UserPrincipal(
            userId = UserId.random(),
            studioId = StudioId.random(),
            isOwner = false,
            email = "worker@studio.pl",
            fullName = "Pracownik Testowy",
            phoneNumber = "600700800"
        )
        SecurityContextHolder.context = SecurityContextImpl(principal)
        every {
            permissionCheckService.hasPermission(any(), any(), Permission.CUSTOMERS_VIEW_PERSONAL_DATA)
        } returns hasPermission
    }

    @Test
    fun `anonymous request is masked and flagged in the header`() {
        mockMvc.perform(get("/api/v1/customers-fake"))
            .andExpect(status().isOk)
            .andExpect(header().string(PII_ACCESS_HEADER, "masked"))
            .andExpect(jsonPath("$.firstName").value(PII_MASK))
            .andExpect(jsonPath("$.phone").value(PII_MASK))
            .andExpect(jsonPath("$.companyName").value("ACME"))
    }

    @Test
    fun `authenticated user without the permission gets masked data`() {
        authenticate(hasPermission = false)
        mockMvc.perform(get("/api/v1/customers-fake"))
            .andExpect(status().isOk)
            .andExpect(header().string(PII_ACCESS_HEADER, "masked"))
            .andExpect(jsonPath("$.firstName").value(PII_MASK))
            .andExpect(jsonPath("$.phone").value(PII_MASK))
    }

    @Test
    fun `authenticated user with the permission gets real data`() {
        authenticate(hasPermission = true)
        mockMvc.perform(get("/api/v1/customers-fake"))
            .andExpect(status().isOk)
            .andExpect(header().string(PII_ACCESS_HEADER, "granted"))
            .andExpect(jsonPath("$.firstName").value("Jan"))
            .andExpect(jsonPath("$.phone").value("501502503"))
    }

    @Test
    fun `signing tablet endpoints are granted by design`() {
        mockMvc.perform(get("/api/tablet/context-fake"))
            .andExpect(status().isOk)
            .andExpect(header().string(PII_ACCESS_HEADER, "granted"))
            .andExpect(jsonPath("$.firstName").value("Jan"))
    }
}

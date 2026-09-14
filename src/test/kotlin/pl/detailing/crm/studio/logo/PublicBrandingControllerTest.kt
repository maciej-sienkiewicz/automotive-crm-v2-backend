package pl.detailing.crm.studio.logo

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.detailing.crm.config.GlobalExceptionHandler
import pl.detailing.crm.security.TenantIsolationAuditService
import java.util.UUID

/**
 * Stały adres logo ma być cache'owany przez przeglądarkę na rok: to on usuwa mruganie
 * nagłówka menu po odświeżeniu. Odpowiada tylko dla aktualnego hasha, więc podmiana
 * logo nigdy nie serwuje starego pliku spod nowego adresu ani odwrotnie.
 */
class PublicBrandingControllerTest {

    private val studioId = UUID.randomUUID()
    private val service = mockk<CompanyLogoService>()
    private val mockMvc = MockMvcBuilders
        .standaloneSetup(PublicBrandingController(service))
        .setControllerAdvice(GlobalExceptionHandler(mockk<TenantIsolationAuditService>(relaxed = true)))
        .build()

    @Test
    fun `serwuje PNG z naglowkami cache na rok`() {
        every { service.loadAppLogo(studioId, "0123456789abcdef") } returns byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        mockMvc.perform(get("/api/public/branding/$studioId/logo/0123456789abcdef/app.png"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("image/png"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"))
            .andExpect(header().string(HttpHeaders.ETAG, "\"0123456789abcdef\""))
    }

    @Test
    fun `nieaktualny hash to 404`() {
        every { service.loadAppLogo(studioId, "fedcba9876543210") } returns null

        mockMvc.perform(get("/api/public/branding/$studioId/logo/fedcba9876543210/app.png"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `przegladarka z aktualnym ETagiem dostaje 304 bez pobierania bajtow`() {
        mockMvc.perform(
            get("/api/public/branding/$studioId/logo/0123456789abcdef/app.png")
                .header(HttpHeaders.IF_NONE_MATCH, "\"0123456789abcdef\"")
        ).andExpect(status().isNotModified)
    }
}

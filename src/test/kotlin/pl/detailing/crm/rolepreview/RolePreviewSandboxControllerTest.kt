package pl.detailing.crm.rolepreview

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import pl.detailing.crm.config.ErrorResponse
import pl.detailing.crm.shared.NotFoundException
import java.time.Instant

/**
 * Wejście do piaskownicy działa wyłącznie pod adresem podglądu. Gdy proxy nie dopisuje
 * nagłówka adresu podglądu, okno podglądu ma od razu dostać powód (421), a nie czekać,
 * jakby piaskownica wciąż się zakładała (404).
 */
class RolePreviewSandboxControllerTest {

    private val service = mockk<RolePreviewService>()
    private val controller = RolePreviewSandboxController(service)
    private val body = EnterRolePreviewRequest("k".repeat(43))

    @Test
    fun `wylaczony podglad nie istnieje`() {
        every { service.isAvailable() } returns false

        assertThrows<NotFoundException> {
            controller.enter(body, previewHostRequest(), MockHttpServletResponse())
        }
    }

    @Test
    fun `wejscie spoza adresu podgladu to blad konfiguracji proxy, pokazany od razu`() {
        every { service.isAvailable() } returns true

        val response = controller.enter(body, MockHttpServletRequest("POST", "/api/v1/role-preview/enter"), MockHttpServletResponse())

        assertEquals(421, response.statusCode.value())
        assertEquals("ROLE_PREVIEW_HOST", (response.body as ErrorResponse).code)
        verify(exactly = 0) { service.enter(any(), any(), any()) }
    }

    @Test
    fun `pod adresem podgladu kod trafia do wymiany na sesje`() {
        every { service.isAvailable() } returns true
        every { service.enter(any(), any(), any()) } returns EnterRolePreviewResponse("Recepcja", Instant.now())

        val response = controller.enter(body, previewHostRequest(), MockHttpServletResponse())

        assertEquals(200, response.statusCode.value())
        verify(exactly = 1) { service.enter(body.entryCode, any(), any()) }
    }

    private fun previewHostRequest() = MockHttpServletRequest("POST", "/api/v1/role-preview/enter").apply {
        addHeader(RolePreviewProperties.HOST_HEADER, "1")
    }
}

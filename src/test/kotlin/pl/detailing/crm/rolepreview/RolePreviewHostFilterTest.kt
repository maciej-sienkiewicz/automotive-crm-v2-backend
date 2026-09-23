package pl.detailing.crm.rolepreview

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContextHolder
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.rolepreview.infrastructure.RolePreviewSandboxEntity
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.studio.domain.StudioKind
import java.time.Instant
import java.util.UUID

/**
 * Wiązanie sesji z adresem: sesja piaskownicy działa wyłącznie pod adresem podglądu, a pod
 * adresem podglądu działa wyłącznie sesja piaskownicy. To ono sprawia, że piaskownica nie
 * jest drugim wejściem do aplikacji, a sesja administratora nie wycieka do okna podglądu.
 */
class RolePreviewHostFilterTest {

    private val studios = mockk<RolePreviewStudios>()
    private val service = mockk<RolePreviewService>(relaxed = true)
    private val filter = RolePreviewHostFilter(studios, service)

    private val sandboxStudio = UUID.randomUUID()
    private val realStudio = UUID.randomUUID()
    private val deletedStudio = UUID.randomUUID()
    private val sandbox = sandboxEntity(sandboxStudio)

    init {
        every { studios.kindOf(sandboxStudio) } returns StudioKind.ROLE_PREVIEW
        every { studios.kindOf(realStudio) } returns StudioKind.REGULAR
        every { studios.kindOf(deletedStudio) } returns null
        every { service.activeSandbox(StudioId(sandboxStudio), any()) } returns sandbox
        every { service.isAvailable() } returns true
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    // ── Sesja piaskownicy ─────────────────────────────────────────────────────────

    @Test
    fun `sesja piaskownicy nie dziala pod adresem aplikacji`() {
        signIn(sandboxStudio)

        val (response, chain) = run(request("GET", "/api/v1/visits", previewHost = false))

        assertRejected(response, chain, 401)
    }

    @Test
    fun `sesja piaskownicy pod adresem podgladu dziala i odnotowuje aktywnosc`() {
        signIn(sandboxStudio)

        val (_, chain) = run(request("GET", "/api/v1/visits", previewHost = true))

        assertNotNull(chain.request)
        verify(exactly = 1) { service.touch(sandbox, any()) }
    }

    @Test
    fun `sesja wygaslej piaskownicy jest uniewazniana`() {
        signIn(sandboxStudio)
        every { service.activeSandbox(StudioId(sandboxStudio), any()) } returns null
        val request = request("GET", "/api/v1/visits", previewHost = true)
        val session = MockHttpSession().also { request.setSession(it) }

        val (response, chain) = run(request)

        assertRejected(response, chain, 401)
        assertTrue(session.isInvalid)
    }

    @Test
    fun `wylaczenie podgladu konczy sesje piaskownic od razu`() {
        signIn(sandboxStudio)
        every { service.isAvailable() } returns false
        val request = request("GET", "/api/v1/visits", previewHost = true)
        val session = MockHttpSession().also { request.setSession(it) }

        val (response, chain) = run(request)

        assertRejected(response, chain, 401)
        assertTrue(session.isInvalid)
    }

    @Test
    fun `piaskownica nie dotyka tego co zaklada poswiadczenia ani nie otwiera kolejnego podgladu`() {
        signIn(sandboxStudio)
        listOf(
            "POST" to "/api/v1/auth/login",
            "POST" to "/api/v1/auth/forgot-password",
            "POST" to "/api/v1/auth/reset-password",
            "POST" to "/api/v1/demo",
            "POST" to "/api/v1/pin/switch",
            "POST" to "/api/v1/push/subscriptions",
            "POST" to "/api/v1/carddav-setup",
            "POST" to "/api/v1/tablets/pairing-codes",
            "GET" to "/api/internal/live-metrics",
            "POST" to "/api/v1/role-preview",
            "GET" to "/api/v1/role-preview/config"
        ).forEach { (method, uri) ->
            val (response, chain) = run(request(method, uri, previewHost = true))
            assertEquals(403, response.status, "$method $uri")
            assertNull(chain.request, "$method $uri nie może dojść do kontrolera")
        }
    }

    @Test
    fun `panel podgladu jest dostepny dla sesji piaskownicy`() {
        signIn(sandboxStudio)

        val (_, chain) = run(request("PUT", "/api/v1/role-preview/current/role", previewHost = true))

        assertNotNull(chain.request)
    }

    @Test
    fun `wejscie nowym kodem dochodzi do kontrolera nawet z sesja wygaslej piaskownicy`() {
        signIn(sandboxStudio)
        every { service.activeSandbox(any(), any()) } returns null

        val (_, chain) = run(request("POST", "/api/v1/role-preview/enter", previewHost = true))

        assertNotNull(chain.request)
    }

    // ── Sesje prawdziwych studiów ───────────────────────────────────────────────

    @Test
    fun `sesja prawdziwego studia nie dziala pod adresem podgladu`() {
        signIn(realStudio)

        val (response, chain) = run(request("GET", "/api/v1/visits", previewHost = true))

        assertRejected(response, chain, 401)
    }

    @Test
    fun `sesja prawdziwego studia pod adresem aplikacji przechodzi bez zmian`() {
        signIn(realStudio)

        val (_, chain) = run(request("GET", "/api/v1/visits", previewHost = false))

        assertNotNull(chain.request)
        verify(exactly = 0) { service.activeSandbox(any(), any()) }
    }

    @Test
    fun `sesja studia ktorego juz nie ma jest martwa i uniewazniona`() {
        signIn(deletedStudio)
        val request = request("GET", "/api/v1/visits", previewHost = false)
        val session = MockHttpSession().also { request.setSession(it) }

        val (response, chain) = run(request)

        assertRejected(response, chain, 401)
        assertTrue(session.isInvalid)
    }

    @Test
    fun `naglowek o innej wartosci niz 1 nie czyni adresu adresem podgladu`() {
        signIn(sandboxStudio)
        val request = MockHttpServletRequest("GET", "/api/v1/visits").apply {
            addHeader(RolePreviewProperties.HOST_HEADER, "")
        }

        val (response, chain) = run(request)

        assertRejected(response, chain, 401)
    }

    // ── Bez sesji ─────────────────────────────────────────────────────────────

    @Test
    fun `bez sesji pod adresem podgladu przechodzi tylko wejscie kodem i dane startowe aplikacji`() {
        listOf(
            "POST" to "/api/v1/role-preview/enter",
            "GET" to "/api/health",
            "GET" to "/api/v1/pwa/manifest",
            "GET" to "/api/v1/vehicle-metadata/brands",
            "GET" to "/api/public/branding/logo-abc.png"
        ).forEach { (method, uri) ->
            val (_, chain) = run(request(method, uri, previewHost = true))
            assertNotNull(chain.request, "$method $uri")
        }

        listOf(
            "POST" to "/api/v1/auth/login",
            "GET" to "/api/v1/visits",
            "GET" to "/api/public/visit-card/token",
            "POST" to "/api/public/lead-forms/token",
            "POST" to "/api/v1/vehicle-metadata/brands",
            "GET" to "/api/v1/role-preview/current"
        ).forEach { (method, uri) ->
            val (response, chain) = run(request(method, uri, previewHost = true))
            assertEquals(401, response.status, "$method $uri")
            assertNull(chain.request, "$method $uri")
        }
    }

    @Test
    fun `bez sesji pod adresem aplikacji decyduje zwykle zabezpieczenie`() {
        val (_, chain) = run(request("POST", "/api/v1/auth/login", previewHost = false))

        assertNotNull(chain.request)
    }

    @Test
    fun `pliki aplikacji nie przechodza przez filtr`() {
        signIn(sandboxStudio)

        val (_, chain) = run(request("GET", "/index.html", previewHost = false))

        assertNotNull(chain.request)
        verify(exactly = 0) { studios.kindOf(any()) }
    }

    // ── Pomocnicze ────────────────────────────────────────────────────────────

    private fun signIn(studioId: UUID) {
        SecurityContextHolder.getContext().authentication = UserPrincipal(
            userId = UserId(UUID.randomUUID()),
            studioId = StudioId(studioId),
            isOwner = false,
            email = "pracownik@podglad.invalid",
            fullName = "Pracownik Podglądowy",
            phoneNumber = "+48000000000"
        )
    }

    private fun request(method: String, uri: String, previewHost: Boolean) =
        MockHttpServletRequest(method, uri).apply {
            if (previewHost) addHeader(RolePreviewProperties.HOST_HEADER, "1")
        }

    private fun run(request: MockHttpServletRequest): Pair<MockHttpServletResponse, MockFilterChain> {
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(request, response, chain)
        return response to chain
    }

    private fun assertRejected(response: MockHttpServletResponse, chain: MockFilterChain, status: Int) {
        assertEquals(status, response.status)
        assertNull(chain.request, "odrzucone żądanie nie może dojść do kontrolera")
        assertTrue(response.contentAsString.contains("\"code\":\"ROLE_PREVIEW\""), response.contentAsString)
    }

    private fun sandboxEntity(studioId: UUID): RolePreviewSandboxEntity {
        val now = Instant.now()
        return RolePreviewSandboxEntity(
            id = UUID.randomUUID(),
            sandboxStudioId = studioId,
            sourceStudioId = UUID.randomUUID(),
            createdByUserId = UUID.randomUUID(),
            createdByName = "Anna Właścicielka",
            ownerUserId = UUID.randomUUID(),
            employeeUserId = UUID.randomUUID(),
            roleId = UUID.randomUUID(),
            roleName = "Recepcja",
            initialPermissions = "VISITS_VIEW",
            initialTrackWorkTime = false,
            entryCodeHash = "0".repeat(64),
            entryCodeExpiresAt = now.plusSeconds(120),
            enteredAt = now,
            sessionId = "session-1",
            createdAt = now,
            lastActivityAt = now,
            expiresAt = now.plusSeconds(7200)
        )
    }
}

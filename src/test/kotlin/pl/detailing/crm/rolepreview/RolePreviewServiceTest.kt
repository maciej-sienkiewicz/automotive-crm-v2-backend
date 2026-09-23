package pl.detailing.crm.rolepreview

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.infrastructure.RoleEntity
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.permission.PermissionSnapshotCache
import pl.detailing.crm.role.update.UpdateRoleCommand
import pl.detailing.crm.role.update.UpdateRoleHandler
import pl.detailing.crm.rolepreview.infrastructure.RolePreviewSandboxEntity
import pl.detailing.crm.rolepreview.infrastructure.RolePreviewSandboxRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.domain.StudioKind
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/**
 * Cykl życia podglądu roli: kto może go otworzyć, jak jednorazowy kod zamienia się w sesję,
 * że panel nie przyjmuje identyfikatorów i że zakończenie usuwa piaskownicę.
 */
class RolePreviewServiceTest {

    private val properties = RolePreviewProperties(enabled = true, baseUrl = "https://podglad.detailboost.pl/")
    private val studios = mockk<RolePreviewStudios>()
    private val sandboxRepository = mockk<RolePreviewSandboxRepository>(relaxed = true)
    private val sandboxFactory = mockk<RolePreviewSandboxFactory>()
    private val eraser = mockk<RolePreviewSandboxEraser>(relaxed = true)
    private val studioRepository = mockk<StudioRepository>()
    private val userRepository = mockk<UserRepository>()
    private val roleRepository = mockk<RoleRepository>()
    private val entitlementService = mockk<EntitlementService>()
    private val updateRoleHandler = mockk<UpdateRoleHandler>()
    private val permissionSnapshotCache = mockk<PermissionSnapshotCache>(relaxed = true)
    private val securityContextRepository = mockk<SecurityContextRepository>()
    private val auditService = mockk<AuditService>(relaxed = true)

    private fun service(props: RolePreviewProperties = properties) = RolePreviewService(
        properties = props,
        studios = studios,
        sandboxRepository = sandboxRepository,
        sandboxFactory = sandboxFactory,
        eraser = eraser,
        effects = mockk(relaxed = true),
        studioRepository = studioRepository,
        userRepository = userRepository,
        roleRepository = roleRepository,
        entitlementService = entitlementService,
        updateRoleHandler = updateRoleHandler,
        permissionSnapshotCache = permissionSnapshotCache,
        securityContextRepository = securityContextRepository,
        auditService = auditService,
        protocolTemplateProvisioner = mockk(relaxed = true),
        marketingConsentProvisioner = mockk(relaxed = true),
        careInstructionProvisioner = mockk(relaxed = true)
    )

    private val realStudio = UUID.randomUUID()
    private val sandboxStudio = UUID.randomUUID()
    private val admin = principal(realStudio, isOwner = true)
    private val code = entryCode()
    private val sandbox = sandboxEntity()

    init {
        every { studios.kindOf(realStudio) } returns StudioKind.REGULAR
        every { studios.isRolePreview(realStudio) } returns false
        every { studios.kindOf(sandboxStudio) } returns StudioKind.ROLE_PREVIEW
        every { studios.isRolePreview(sandboxStudio) } returns true
        every { entitlementService.getEntitlements(any()) } returns
            StudioEntitlements(PlanKey.FULL, "Pełny", emptySet(), emptySet())
        every { sandboxRepository.findBySandboxStudioId(sandboxStudio) } returns sandbox
    }

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    // ── Otwarcie ──────────────────────────────────────────────────────────────────

    @Test
    fun `podglad jest niedostepny dopoki nie ma adresu podgladu`() {
        val disabled = service(RolePreviewProperties(enabled = true, baseUrl = ""))

        assertFalse(disabled.isAvailable())
        assertNull(disabled.config().previewBaseUrl)
        assertThrows<ForbiddenException> { disabled.start(admin, startRequest()) }
    }

    @Test
    fun `podglad otwiera tylko prawdziwe studio`() {
        listOf(StudioKind.DEMO, StudioKind.ROLE_PREVIEW).forEach { kind ->
            every { studios.kindOf(realStudio) } returns kind
            assertThrows<ForbiddenException>(kind.name) { service().start(admin, startRequest()) }
        }
        verify(exactly = 0) { sandboxFactory.create(any()) }
    }

    @Test
    fun `kod wejscia musi byc 32 losowymi bajtami w base64url`() {
        listOf("", "krotki", code.dropLast(1), code.dropLast(1) + "=", code.dropLast(1) + "+").forEach { bad ->
            assertThrows<ValidationException>(bad) { service().start(admin, startRequest(entryCode = bad)) }
        }
        verify(exactly = 0) { sandboxFactory.create(any()) }
    }

    @Test
    fun `nieznane uprawnienie jest odrzucane zanim cokolwiek powstanie`() {
        assertThrows<ValidationException> {
            service().start(admin, startRequest(permissions = listOf("NIE_MA_TAKIEGO")))
        }
        verify(exactly = 0) { sandboxFactory.create(any()) }
    }

    @Test
    fun `studio ma limit otwartych podgladow`() {
        every { sandboxRepository.countActiveBySourceStudio(realStudio, any(), any()) } returns 5L

        assertThrows<ConflictException> { service().start(admin, startRequest()) }
        verify(exactly = 0) { sandboxFactory.create(any()) }
    }

    @Test
    fun `do bazy trafia tylko skrot kodu, a otwarcie zostaje w audycie studia`() {
        every { sandboxRepository.countActiveBySourceStudio(realStudio, any(), any()) } returns 0L
        every { studioRepository.findByStudioId(realStudio) } returns studioEntity()
        val spec = slot<SandboxSpec>()
        every { sandboxFactory.create(capture(spec)) } returns sandbox
        val audit = slot<LogAuditCommand>()
        every { auditService.logSync(capture(audit)) } returns Unit

        val response = service().start(admin, startRequest())

        assertEquals(sha256Hex(code), spec.captured.entryCodeHash)
        assertNotEquals(code, spec.captured.entryCodeHash)
        assertEquals(realStudio, spec.captured.sourceStudioId)
        assertEquals(setOf(Permission.values().first()), spec.captured.permissions)
        assertTrue(spec.captured.entryCodeExpiresAt <= spec.captured.now.plusSeconds(properties.entryCodeTtlSeconds))
        assertEquals("https://podglad.detailboost.pl", response.previewBaseUrl)
        assertEquals(StudioId(realStudio), audit.captured.studioId)
        assertEquals(AuditAction.ROLE_PREVIEW_STARTED, audit.captured.action)
    }

    // ── Wejście ───────────────────────────────────────────────────────────────────

    @Test
    fun `kod zamienia sie w sesje pracownika piaskownicy`() {
        every { sandboxRepository.consumeEntryCode(sha256Hex(code), any()) } returns 1
        every { sandboxRepository.findByEntryCodeHash(sha256Hex(code)) } returns sandbox
        every { userRepository.findByIdAndStudioId(sandbox.employeeUserId, sandboxStudio) } returns employee()
        val saved = slot<SecurityContext>()
        every { securityContextRepository.saveContext(capture(saved), any(), any()) } answers {
            // Jak HttpSessionSecurityContextRepository: zapis zakłada sesję.
            secondArg<MockHttpServletRequest>().getSession(true)
            Unit
        }
        val request = MockHttpServletRequest("POST", "/api/v1/role-preview/enter")

        service().enter(code, request, MockHttpServletResponse())

        val principal = saved.captured.authentication as UserPrincipal
        assertEquals(StudioId(sandboxStudio), principal.studioId)
        assertEquals(UserId(sandbox.employeeUserId), principal.userId)
        assertFalse(principal.isOwner)
        verify(exactly = 1) { sandboxRepository.bindSession(sandbox.id, request.getSession(false)!!.id) }
    }

    @Test
    fun `kod dziala raz`() {
        every { sandboxRepository.consumeEntryCode(any(), any()) } returns 0
        every { sandboxRepository.findByEntryCodeHash(sha256Hex(code)) } returns sandbox

        assertThrows<ConflictException> {
            service().enter(code, MockHttpServletRequest(), MockHttpServletResponse())
        }
        verify(exactly = 0) { securityContextRepository.saveContext(any(), any(), any()) }
    }

    @Test
    fun `okno podgladu czeka az piaskownica powstanie`() {
        every { sandboxRepository.consumeEntryCode(any(), any()) } returns 0
        every { sandboxRepository.findByEntryCodeHash(any()) } returns null

        assertThrows<NotFoundException> {
            service().enter(code, MockHttpServletRequest(), MockHttpServletResponse())
        }
    }

    @Test
    fun `sesja prawdziwego studia nie wchodzi do piaskownicy`() {
        SecurityContextHolder.getContext().authentication = admin

        assertThrows<ForbiddenException> {
            service().enter(code, MockHttpServletRequest(), MockHttpServletResponse())
        }
        verify(exactly = 0) { sandboxRepository.consumeEntryCode(any(), any()) }
    }

    @Test
    fun `nowy podglad w tej samej przegladarce konczy poprzedni`() {
        val previousStudio = UUID.randomUUID()
        val previous = sandboxEntity(studioId = previousStudio)
        every { studios.isRolePreview(previousStudio) } returns true
        every { sandboxRepository.findBySandboxStudioId(previousStudio) } returns previous
        every { sandboxRepository.consumeEntryCode(any(), any()) } returns 0
        every { sandboxRepository.findByEntryCodeHash(any()) } returns null
        SecurityContextHolder.getContext().authentication = principal(previousStudio, isOwner = false)
        val request = MockHttpServletRequest()
        val oldSession = MockHttpSession().also { request.setSession(it) }

        assertThrows<NotFoundException> { service().enter(code, request, MockHttpServletResponse()) }

        verify(exactly = 1) { eraser.erase(previous) }
        assertTrue(oldSession.isInvalid)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    // ── Panel ─────────────────────────────────────────────────────────────────────

    @Test
    fun `zmiana roli zawsze dotyczy roli piaskownicy z sesji`() = runBlocking {
        val role = roleEntity()
        every { roleRepository.findByIdAndStudioId(sandbox.roleId, sandboxStudio) } returns role
        val command = slot<UpdateRoleCommand>()
        coEvery { updateRoleHandler.handle(capture(command)) } returns Unit
        val code = Permission.values().first().name

        service().updateRole(principal(sandboxStudio, isOwner = false), UpdateRolePreviewRequest(listOf(code), trackWorkTime = true))

        assertEquals(StudioId(sandboxStudio), command.captured.studioId)
        assertEquals(sandbox.roleId, command.captured.roleId.value)
        assertEquals(UserId(sandbox.ownerUserId), command.captured.requestedBy)
        assertEquals(setOf(Permission.values().first()), command.captured.permissions)
        assertTrue(command.captured.trackWorkTime)
        verify(exactly = 1) { permissionSnapshotCache.evictUser(UserId(sandbox.employeeUserId), StudioId(sandboxStudio)) }
    }

    @Test
    fun `panel nie dziala z sesji prawdziwego studia`() {
        assertThrows<NotFoundException> { service().current(admin) }
        assertThrows<NotFoundException> {
            runBlocking { service().updateRole(admin, UpdateRolePreviewRequest(emptyList())) }
        }
        coVerify(exactly = 0) { updateRoleHandler.handle(any()) }
    }

    @Test
    fun `wygasla piaskownica nie ma panelu`() {
        every { sandboxRepository.findBySandboxStudioId(sandboxStudio) } returns
            sandboxEntity(lastActivityAt = Instant.now().minus(Duration.ofMinutes(properties.idleTimeoutMinutes + 1)))

        assertThrows<NotFoundException> { service().current(principal(sandboxStudio, isOwner = false)) }
    }

    // ── Koniec ────────────────────────────────────────────────────────────────────

    @Test
    fun `zakonczenie usuwa piaskownice i sesje, a w audycie studia zostaje slad`() {
        val request = MockHttpServletRequest()
        val session = MockHttpSession().also { request.setSession(it) }
        val audit = slot<LogAuditCommand>()
        every { auditService.logSync(capture(audit)) } returns Unit

        service().end(principal(sandboxStudio, isOwner = false), request)

        verify(exactly = 1) { eraser.erase(sandbox) }
        assertTrue(session.isInvalid)
        assertEquals(StudioId(sandbox.sourceStudioId), audit.captured.studioId)
        assertEquals(AuditAction.ROLE_PREVIEW_ENDED, audit.captured.action)
    }

    @Test
    fun `wylogowanie prawdziwego studia niczego nie usuwa`() {
        service().endIfSandbox(admin)
        service().endIfSandbox(null)

        verify(exactly = 0) { eraser.erase(any()) }
    }

    @Test
    fun `sprzatanie usuwa wygasle piaskownice bez wpisow w audycie i nie staje na pierwszym bledzie`() {
        val first = sandboxEntity(studioId = UUID.randomUUID())
        val second = sandboxEntity(studioId = UUID.randomUUID())
        every { sandboxRepository.findEnded(any(), any()) } returns listOf(first, second)
        every { eraser.erase(first) } throws IllegalStateException("S3 nie odpowiada")

        assertEquals(2, service().removeEnded())

        verify(exactly = 1) { eraser.erase(second) }
        verify(exactly = 0) { auditService.logSync(any()) }
    }

    @Test
    fun `wylaczony podglad sprzata wszystkie piaskownice`() {
        val alive = sandboxEntity(studioId = UUID.randomUUID())
        every { sandboxRepository.findAll() } returns listOf(alive)

        assertEquals(1, service(RolePreviewProperties(enabled = false)).removeEnded())

        verify(exactly = 1) { eraser.erase(alive) }
        verify(exactly = 0) { sandboxRepository.findEnded(any(), any()) }
    }

    @Test
    fun `piaskownica zyje do konca zycia i do przekroczenia bezczynnosci`() {
        val now = Instant.now()
        val fresh = sandboxEntity(lastActivityAt = now, expiresAt = now.plusSeconds(60))
        val idle = sandboxEntity(lastActivityAt = now.minus(Duration.ofMinutes(properties.idleTimeoutMinutes)), expiresAt = now.plusSeconds(60))
        val old = sandboxEntity(lastActivityAt = now, expiresAt = now)

        listOf(fresh to true, idle to false, old to false).forEach { (entity, alive) ->
            every { sandboxRepository.findBySandboxStudioId(sandboxStudio) } returns entity
            assertEquals(alive, service().activeSandbox(StudioId(sandboxStudio), now) != null)
        }
    }

    @Test
    fun `aktywnosc jest zapisywana najwyzej raz na minute`() {
        val now = Instant.now()

        service().touch(sandboxEntity(lastActivityAt = now.minusSeconds(30)), now)
        verify(exactly = 0) { sandboxRepository.touch(any(), any()) }

        val stale = sandboxEntity(lastActivityAt = now.minusSeconds(61))
        service().touch(stale, now)
        verify(exactly = 1) { sandboxRepository.touch(stale.id, now) }
    }

    @Test
    fun `skrot kodu jest staly i nie zdradza kodu`() {
        assertDoesNotThrow { RolePreviewService.hashEntryCode(code) }
        assertEquals(RolePreviewService.hashEntryCode(code), RolePreviewService.hashEntryCode(code))
        assertEquals(64, RolePreviewService.hashEntryCode(code).length)
        assertFalse(RolePreviewService.hashEntryCode(code).contains(code))
    }

    // ── Pomocnicze ────────────────────────────────────────────────────────────────

    private fun startRequest(
        entryCode: String = code,
        permissions: List<String> = listOf(Permission.values().first().name)
    ) = StartRolePreviewRequest(
        roleName = "Recepcja",
        permissions = permissions,
        trackWorkTime = false,
        entryCode = entryCode
    )

    private fun principal(studioId: UUID, isOwner: Boolean) = UserPrincipal(
        userId = UserId(UUID.randomUUID()),
        studioId = StudioId(studioId),
        isOwner = isOwner,
        email = "anna@studio.pl",
        fullName = "Anna Właścicielka",
        phoneNumber = "+48600100200"
    )

    private fun sandboxEntity(
        studioId: UUID = sandboxStudio,
        lastActivityAt: Instant = Instant.now(),
        expiresAt: Instant = Instant.now().plus(Duration.ofHours(2))
    ) = RolePreviewSandboxEntity(
        id = UUID.randomUUID(),
        sandboxStudioId = studioId,
        sourceStudioId = realStudio,
        createdByUserId = UUID.randomUUID(),
        createdByName = "Anna Właścicielka",
        ownerUserId = UUID.randomUUID(),
        employeeUserId = UUID.randomUUID(),
        roleId = UUID.randomUUID(),
        roleName = "Recepcja",
        initialPermissions = Permission.values().first().name,
        initialTrackWorkTime = false,
        entryCodeHash = sha256Hex(code),
        entryCodeExpiresAt = Instant.now().plusSeconds(120),
        createdAt = Instant.now(),
        lastActivityAt = lastActivityAt,
        expiresAt = expiresAt
    )

    private fun studioEntity() = StudioEntity(
        id = realStudio,
        name = "Detailing Kowalski",
        subscriptionStatus = SubscriptionStatus.ACTIVE,
        trialEndsAt = null,
        subscriptionEndsAt = null,
        trialUsed = true
    )

    private fun employee() = UserEntity(
        id = sandbox.employeeUserId,
        studioId = sandboxStudio,
        email = "pracownik-abc@podglad.invalid",
        phoneNumber = "+48000000000",
        passwordHash = "!role-preview:no-password",
        firstName = "Pracownik",
        lastName = "Podglądowy",
        isOwner = false,
        customRoleId = sandbox.roleId
    )

    private fun roleEntity() = RoleEntity(
        id = sandbox.roleId,
        studioId = sandboxStudio,
        name = "Recepcja",
        description = null,
        permissions = mutableSetOf(Permission.values().first().name),
        createdBy = sandbox.ownerUserId
    )

    private companion object {
        fun entryCode(): String {
            val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun sha256Hex(value: String): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    }
}

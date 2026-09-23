package pl.detailing.crm.rolepreview

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.careinstruction.DefaultCareInstructionProvisioner
import pl.detailing.crm.customer.consent.template.DefaultMarketingConsentProvisioner
import pl.detailing.crm.protocol.template.DefaultProtocolTemplateProvisioner
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.permission.PermissionSnapshotCache
import pl.detailing.crm.role.permissionCatalogTree
import pl.detailing.crm.role.update.UpdateRoleCommand
import pl.detailing.crm.role.update.UpdateRoleHandler
import pl.detailing.crm.rolepreview.infrastructure.RolePreviewSandboxEntity
import pl.detailing.crm.rolepreview.infrastructure.RolePreviewSandboxRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.RoleId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.domain.StudioKind
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.user.infrastructure.UserRepository
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.HexFormat

/**
 * Podgląd roli: administrator ogląda CRM oczami pracownika z daną rolą.
 *
 * Podgląd działa na prawdziwym backendzie, w piaskownicy - jednorazowym studiu z danymi
 * przykładowymi (patrz [RolePreviewSandboxFactory]). Zasady, na których stoi bezpieczeństwo:
 *
 * 1. Jedyne wejście to jednorazowy kod: generuje go przeglądarka administratora, do bazy
 *    trafia tylko jego skrót, działa raz i krótko, a wydaje go wyłącznie prawdziwe studio.
 * 2. Konta piaskownicy nie działają nigdzie indziej: nie mają hasła, logowanie, PIN, reset
 *    hasła i CardDAV je odrzucają, a sesja piaskownicy działa tylko pod adresem podglądu
 *    ([RolePreviewHostFilter]) i tylko dopóki piaskownica żyje.
 * 3. Piaskownica nie ma wyjścia na świat - bezpiecznik zatrzymuje każdą wysyłkę.
 * 4. Zmiana roli z panelu nie przyjmuje żadnych identyfikatorów: zawsze dotyczy piaskownicy,
 *    do której należy sesja.
 * 5. Razem z piaskownicą znikają wszystkie jej dane i poświadczenia ([RolePreviewSandboxEraser]).
 */
@Service
class RolePreviewService(
    private val properties: RolePreviewProperties,
    private val studios: RolePreviewStudios,
    private val sandboxRepository: RolePreviewSandboxRepository,
    private val sandboxFactory: RolePreviewSandboxFactory,
    private val eraser: RolePreviewSandboxEraser,
    private val effects: RolePreviewEffects,
    private val studioRepository: StudioRepository,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val entitlementService: EntitlementService,
    private val updateRoleHandler: UpdateRoleHandler,
    private val permissionSnapshotCache: PermissionSnapshotCache,
    private val securityContextRepository: SecurityContextRepository,
    private val auditService: AuditService,
    private val protocolTemplateProvisioner: DefaultProtocolTemplateProvisioner,
    private val marketingConsentProvisioner: DefaultMarketingConsentProvisioner,
    private val careInstructionProvisioner: DefaultCareInstructionProvisioner
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun config(): RolePreviewConfigResponse = RolePreviewConfigResponse(
        enabled = isAvailable(),
        previewBaseUrl = if (isAvailable()) properties.baseUrl.trimEnd('/') else null
    )

    // ── Otwarcie podglądu (prawdziwe studio) ────────────────────────────────────────

    fun start(principal: UserPrincipal, request: StartRolePreviewRequest): StartRolePreviewResponse {
        if (!isAvailable()) throw ForbiddenException("Podgląd roli nie jest włączony")

        // Tylko prawdziwe studio: z piaskownicy nie otwiera się kolejnej, a publiczne konto
        // DEMO nie może zakładać piaskownic (każdy może mieć dowolnie wiele kont DEMO).
        if (studios.kindOf(principal.studioId.value) != StudioKind.REGULAR) {
            throw ForbiddenException("Podgląd roli jest dostępny tylko w prawdziwym studiu")
        }

        val roleName = request.roleName.trim()
        if (roleName.isEmpty()) throw ValidationException("Podaj nazwę roli")
        if (roleName.length > MAX_ROLE_NAME_LENGTH) throw ValidationException("Nazwa roli może mieć najwyżej $MAX_ROLE_NAME_LENGTH znaków")
        val permissions = parsePermissions(request.permissions)
        val codeHash = hashEntryCode(request.entryCode)

        val now = Instant.now()
        val active = sandboxRepository.countActiveBySourceStudio(
            principal.studioId.value, now, now.minus(idleTimeout())
        )
        if (active >= properties.maxActivePerStudio) {
            throw ConflictException(
                "W tym studiu jest już otwartych $active podglądów roli. Zamknij któryś, zanim otworzysz kolejny."
            )
        }

        val sourceStudio = studioRepository.findByStudioId(principal.studioId.value)
            ?: throw NotFoundException("Studio nie istnieje")

        val sandbox = sandboxFactory.create(
            SandboxSpec(
                sourceStudioId = principal.studioId.value,
                studioName = "${sourceStudio.name} (podgląd)",
                createdByUserId = principal.userId.value,
                createdByName = principal.fullName,
                roleName = roleName,
                permissions = permissions,
                trackWorkTime = request.trackWorkTime,
                entitlements = entitlementService.getEntitlements(principal.studioId),
                entryCodeHash = codeHash,
                now = now,
                entryCodeExpiresAt = now.plusSeconds(properties.entryCodeTtlSeconds),
                expiresAt = now.plus(Duration.ofMinutes(properties.maxLifetimeMinutes))
            )
        )
        provisionDefaults(StudioId(sandbox.sandboxStudioId))

        auditService.logSync(
            LogAuditCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                userDisplayName = principal.fullName,
                module = AuditModule.USER,
                entityId = sandbox.id.toString(),
                entityDisplayName = roleName,
                action = AuditAction.ROLE_PREVIEW_STARTED,
                metadata = mapOf(
                    "roleName" to roleName,
                    "permissions" to sandbox.initialPermissions,
                    "trackWorkTime" to request.trackWorkTime.toString()
                )
            )
        )
        logger.info(
            "Otwarto podgląd roli: studio={}, piaskownica={}, przez={}",
            principal.studioId, sandbox.sandboxStudioId, principal.userId
        )

        return StartRolePreviewResponse(
            previewBaseUrl = properties.baseUrl.trimEnd('/'),
            expiresAt = sandbox.expiresAt
        )
    }

    // ── Wejście do piaskownicy (adres podglądu) ─────────────────────────────────────

    /**
     * Wymienia jednorazowy kod na sesję pracownika piaskownicy. Wywoływane wyłącznie pod
     * adresem podglądu - sprawdza to kontroler, zanim w ogóle tu trafi.
     */
    fun enter(entryCode: String, request: HttpServletRequest, response: HttpServletResponse): EnterRolePreviewResponse {
        val codeHash = hashEntryCode(entryCode)

        // Pod adresem podglądu jest jedno ciasteczko sesji: nowy podgląd w tej przeglądarce
        // kończy poprzedni, zamiast zostawiać go osieroconego do wygaśnięcia.
        val previous = SecurityContextHolder.getContext().authentication as? UserPrincipal
        if (previous != null) {
            if (!studios.isRolePreview(previous.studioId.value)) {
                // Filtr nie wpuszcza prawdziwych sesji pod adres podglądu; to jest ostatnia linia.
                throw ForbiddenException("Podgląd roli działa tylko pod osobnym adresem")
            }
            endSandboxOfStudio(previous.studioId, reason = EndReason.REPLACED)
        }
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()

        val now = Instant.now()
        if (sandboxRepository.consumeEntryCode(codeHash, now) == 0) {
            if (sandboxRepository.findByEntryCodeHash(codeHash) == null) {
                // Piaskownica mogła jeszcze nie powstać - okno podglądu otwiera się od razu
                // po kliknięciu i ponawia wejście, dopóki kod nie wygaśnie.
                throw NotFoundException("Podgląd jeszcze się przygotowuje")
            }
            throw ConflictException("Ten link do podglądu został już użyty albo wygasł. Otwórz podgląd ponownie z ustawień ról.")
        }

        val sandbox = sandboxRepository.findByEntryCodeHash(codeHash)
            ?: throw NotFoundException("Podgląd roli nie istnieje")
        val employee = userRepository.findByIdAndStudioId(sandbox.employeeUserId, sandbox.sandboxStudioId)
            ?: throw NotFoundException("Podgląd roli nie istnieje")

        val principal = UserPrincipal(
            userId = UserId(employee.id),
            studioId = StudioId(employee.studioId),
            isOwner = false,
            email = employee.email,
            phoneNumber = employee.phoneNumber,
            fullName = "${employee.firstName} ${employee.lastName}"
        )
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = principal
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)

        request.getSession(false)?.id?.let { sandboxRepository.bindSession(sandbox.id, it) }

        return EnterRolePreviewResponse(roleName = sandbox.roleName, expiresAt = sandbox.expiresAt)
    }

    // ── Panel podglądu (sesja piaskownicy) ──────────────────────────────────────────

    fun current(principal: UserPrincipal): RolePreviewStateResponse {
        val sandbox = activeSandboxOf(principal)
        val role = roleRepository.findByIdAndStudioId(sandbox.roleId, sandbox.sandboxStudioId)?.toDomain()
            ?: throw NotFoundException("Podgląd roli nie istnieje")
        val entitlements = entitlementService.getEntitlements(StudioId(sandbox.sandboxStudioId))

        return RolePreviewStateResponse(
            roleName = role.name,
            permissions = role.permissions.map { it.name }.sorted(),
            trackWorkTime = role.trackWorkTime,
            initialPermissions = sandbox.initialPermissionCodes(),
            initialTrackWorkTime = sandbox.initialTrackWorkTime,
            enabledFeatures = entitlements.enabledFeatures.map { it.name }.sorted(),
            catalog = permissionCatalogTree(),
            openedByName = sandbox.createdByName,
            expiresAt = sandbox.expiresAt,
            idleExpiresAt = sandbox.lastActivityAt.plus(idleTimeout()),
            simulatedEffects = effects.list(sandbox.sandboxStudioId).map {
                SimulatedEffectResponse(
                    channel = it.channel.name,
                    channelLabel = it.channel.label,
                    recipient = it.recipient,
                    summary = it.summary,
                    at = Instant.ofEpochMilli(it.atEpochMillis)
                )
            }
        )
    }

    /**
     * Zmienia uprawnienia podglądanej roli. Bez identyfikatorów: zawsze rola piaskownicy,
     * do której należy sesja - nie ma parametru, którym dałoby się wycelować w prawdziwą rolę.
     *
     * Zapis idzie zwykłą drogą ([UpdateRoleHandler]) w imieniu właściciela piaskownicy, więc
     * rola w piaskownicy zachowuje się dokładnie jak rola w prawdziwym studiu.
     */
    suspend fun updateRole(principal: UserPrincipal, request: UpdateRolePreviewRequest): RolePreviewStateResponse {
        val sandbox = activeSandboxOf(principal)
        val permissions = parsePermissions(request.permissions)
        val role = roleRepository.findByIdAndStudioId(sandbox.roleId, sandbox.sandboxStudioId)
            ?: throw NotFoundException("Podgląd roli nie istnieje")

        updateRoleHandler.handle(
            UpdateRoleCommand(
                studioId = StudioId(sandbox.sandboxStudioId),
                requestedBy = UserId(sandbox.ownerUserId),
                requestedByName = "Podgląd roli",
                roleId = RoleId(sandbox.roleId),
                name = role.name,
                description = role.description,
                permissions = permissions,
                trackWorkTime = request.trackWorkTime
            )
        )
        // UpdateRoleHandler czyści cache całego studia; pracownik piaskownicy jest jeden,
        // więc jego wpis kasujemy też wprost - zmiana ma działać od następnego żądania.
        permissionSnapshotCache.evictUser(UserId(sandbox.employeeUserId), StudioId(sandbox.sandboxStudioId))
        return current(principal)
    }

    /** „Zakończ podgląd" - piaskownica znika od razu, razem z sesją. */
    fun end(principal: UserPrincipal, request: HttpServletRequest) {
        if (!studios.isRolePreview(principal.studioId.value)) throw NotFoundException("Podgląd roli nie istnieje")
        request.getSession(false)?.invalidate()
        SecurityContextHolder.clearContext()
        endSandboxOfStudio(principal.studioId, reason = EndReason.CLOSED)
    }

    /** Wylogowanie z okna podglądu też kończy piaskownicę - nie ma po co czekać na jej wygaśnięcie. */
    fun endIfSandbox(principal: UserPrincipal?) {
        if (principal == null || !studios.isRolePreview(principal.studioId.value)) return
        endSandboxOfStudio(principal.studioId, reason = EndReason.CLOSED)
    }

    // ── Dla filtra i sprzątania ─────────────────────────────────────────────────────

    /**
     * Piaskownica studia, jeśli wciąż żyje: przed końcem życia i bez przekroczonej
     * bezczynności. Wygasła piaskownica nie daje dostępu, nawet jeśli sprzątanie jeszcze
     * jej nie usunęło.
     */
    fun activeSandbox(studioId: StudioId, now: Instant = Instant.now()): RolePreviewSandboxEntity? {
        val sandbox = sandboxRepository.findBySandboxStudioId(studioId.value) ?: return null
        return sandbox.takeIf { isActive(it, now) }
    }

    /** Odnotowuje aktywność - co najwyżej raz na minutę, żeby nie pisać przy każdym żądaniu. */
    fun touch(sandbox: RolePreviewSandboxEntity, now: Instant = Instant.now()) {
        if (Duration.between(sandbox.lastActivityAt, now) >= TOUCH_INTERVAL) {
            sandboxRepository.touch(sandbox.id, now)
        }
    }

    /**
     * Usuwa piaskownice po czasie życia, po bezczynności i te, do których nikt nie wszedł.
     * Przy wyłączonym podglądzie - wszystkie: i tak nikt już do nich nie wejdzie.
     */
    fun removeEnded(now: Instant = Instant.now()): Int {
        val ended = if (isAvailable()) {
            sandboxRepository.findEnded(now, now.minus(idleTimeout()))
        } else {
            sandboxRepository.findAll()
        }
        ended.forEach { sandbox ->
            runCatching { erase(sandbox, EndReason.EXPIRED) }
                .onFailure { logger.error("Nie udało się usunąć piaskownicy {}: {}", sandbox.sandboxStudioId, it.message, it) }
        }
        return ended.size
    }

    fun isAvailable(): Boolean = properties.enabled && properties.baseUrl.isNotBlank()

    // ── Wewnętrzne ──────────────────────────────────────────────────────────────────

    private fun activeSandboxOf(principal: UserPrincipal): RolePreviewSandboxEntity {
        if (!studios.isRolePreview(principal.studioId.value)) throw NotFoundException("Podgląd roli nie istnieje")
        return activeSandbox(principal.studioId) ?: throw NotFoundException("Podgląd roli wygasł")
    }

    private fun isActive(sandbox: RolePreviewSandboxEntity, now: Instant): Boolean =
        now.isBefore(sandbox.expiresAt) && now.isBefore(sandbox.lastActivityAt.plus(idleTimeout()))

    private fun endSandboxOfStudio(studioId: StudioId, reason: EndReason) {
        val sandbox = sandboxRepository.findBySandboxStudioId(studioId.value) ?: return
        erase(sandbox, reason)
    }

    private fun erase(sandbox: RolePreviewSandboxEntity, reason: EndReason) {
        eraser.erase(sandbox)
        // W audycie prawdziwego studia zostaje decyzja człowieka (zamknięcie, zastąpienie
        // nowym podglądem). Wygaśnięcie to tylko sprzątanie - wystarczy log.
        if (reason != EndReason.EXPIRED) runCatching {
            auditService.logSync(
                LogAuditCommand(
                    studioId = StudioId(sandbox.sourceStudioId),
                    userId = UserId(sandbox.createdByUserId),
                    userDisplayName = sandbox.createdByName,
                    module = AuditModule.USER,
                    entityId = sandbox.id.toString(),
                    entityDisplayName = sandbox.roleName,
                    action = AuditAction.ROLE_PREVIEW_ENDED,
                    metadata = mapOf("reason" to reason.name)
                )
            )
        }.onFailure { logger.warn("Nie udało się zapisać zakończenia podglądu {} w audycie: {}", sandbox.id, it.message) }
        logger.info("Zakończono podgląd roli: piaskownica={}, powód={}", sandbox.sandboxStudioId, reason)
    }

    /**
     * Te same domyślne dokumenty co w nowym studiu (protokoły, zgody, instrukcje pielęgnacji).
     * Provisionery piszą we własnych transakcjach, więc wołamy je dopiero po zatwierdzeniu
     * piaskownicy - błąd w nich nie może jej zostawić w połowie. Piaskownica bez któregoś
     * z dokumentów działa dalej; usunie je [RolePreviewSandboxEraser] razem z resztą.
     */
    private fun provisionDefaults(studioId: StudioId) {
        runCatching { protocolTemplateProvisioner.ensureDefaultCheckInTemplate(studioId) }
            .onFailure { logger.warn("Piaskownica {}: brak domyślnego protokołu przyjęcia: {}", studioId, it.message) }
        runCatching { protocolTemplateProvisioner.ensureDefaultCheckOutTemplate(studioId) }
            .onFailure { logger.warn("Piaskownica {}: brak domyślnego protokołu wydania: {}", studioId, it.message) }
        runCatching { marketingConsentProvisioner.ensureDefaultMarketingConsent(studioId) }
            .onFailure { logger.warn("Piaskownica {}: brak domyślnej zgody marketingowej: {}", studioId, it.message) }
        runCatching { careInstructionProvisioner.ensureDefaults(studioId) }
            .onFailure { logger.warn("Piaskownica {}: brak domyślnych instrukcji pielęgnacji: {}", studioId, it.message) }
    }

    private fun parsePermissions(codes: List<String>): Set<Permission> {
        if (codes.size > MAX_PERMISSION_CODES) throw ValidationException("Za dużo uprawnień w żądaniu")
        return codes.map { code ->
            Permission.fromApiCode(code) ?: throw ValidationException("Nieznane uprawnienie: '$code'")
        }.toSet()
    }

    private fun idleTimeout(): Duration = Duration.ofMinutes(properties.idleTimeoutMinutes)

    enum class EndReason { CLOSED, REPLACED, EXPIRED }

    companion object {
        private const val MAX_ROLE_NAME_LENGTH = 100
        private const val MAX_PERMISSION_CODES = 200
        private val TOUCH_INTERVAL: Duration = Duration.ofMinutes(1)

        /** 32 losowe bajty zakodowane base64url bez dopełnienia - dokładnie 43 znaki. */
        private val ENTRY_CODE_FORMAT = Regex("^[A-Za-z0-9_-]{43}$")

        /** Skrót kodu wejścia. Sam kod nigdy nie trafia do bazy ani do logów. */
        fun hashEntryCode(entryCode: String): String {
            if (!ENTRY_CODE_FORMAT.matches(entryCode)) throw ValidationException("Nieprawidłowy kod podglądu")
            val digest = MessageDigest.getInstance("SHA-256").digest(entryCode.toByteArray(Charsets.US_ASCII))
            return HexFormat.of().formatHex(digest)
        }
    }
}

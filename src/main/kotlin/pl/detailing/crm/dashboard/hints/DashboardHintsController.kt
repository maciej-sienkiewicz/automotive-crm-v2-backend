package pl.detailing.crm.dashboard.hints

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("/api/v1/dashboard/hints")
@RequiresPermission(Permission.VISITS_VIEW)
class DashboardHintsController(
    private val getDashboardHintsHandler: GetDashboardHintsHandler,
    private val dismissDashboardHintHandler: DismissDashboardHintHandler,
    private val disableWorkTimeTrackingHandler: DisableWorkTimeTrackingHandler
) {

    @GetMapping
    fun getHints(): ResponseEntity<DashboardHintsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(DashboardHintsResponse(getDashboardHintsHandler.handle(principal)))
    }

    @PostMapping("/{key}/dismiss")
    fun dismissHint(@PathVariable key: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        dismissDashboardHintHandler.dismiss(principal.userId, key)
        ResponseEntity.noContent().build()
    }

    /**
     * "Wyłącz funkcję" z podpowiedzi o nieużywanych Kartach Czasu Pracy:
     * zdejmuje śledzenie czasu ze wszystkich ról studia. Konfiguracja ról to
     * decyzja właściciela, więc twardy warunek zamiast RequiresPermission —
     * ta adnotacja nie zna pojęcia "tylko owner".
     */
    @PostMapping("/worktime/disable")
    fun disableWorkTime(): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (!principal.isOwner) {
            throw ForbiddenException("Tylko właściciel może wyłączyć śledzenie czasu pracy")
        }
        disableWorkTimeTrackingHandler.disableForStudio(principal.studioId, principal.userId)
        ResponseEntity.noContent().build()
    }
}

data class DashboardHintsResponse(
    val hints: List<DashboardHint>
)

@Service
class DismissDashboardHintHandler(
    private val dismissalRepository: DashboardHintDismissalRepository
) {
    companion object {
        val SNOOZE: Duration = Duration.ofDays(7)

        /** Klucz to identyfikator z naszej własnej listy — wszystko inne odrzucamy. */
        private val KEY_PATTERN = Regex("^[A-Z0-9_.@-]{1,120}$")
    }

    @Transactional
    suspend fun dismiss(userId: UserId, rawKey: String) =
        withContext(Dispatchers.IO) {
            val key = rawKey.trim()
            if (!KEY_PATTERN.matches(key)) {
                throw ValidationException("Nieznany identyfikator podpowiedzi")
            }

            // Trwałość zamknięcia dyktuje rodzaj podpowiedzi, nie klient:
            // inaczej każdy mógłby wyciszyć sobie na zawsze wszystko, łącznie
            // z przypomnieniami, które mają wracać.
            val permanent = key.startsWith("KSEF_UPSELL") || key.startsWith("WORKTIME_UNUSED")
            val snoozeUntil = if (permanent) null else Instant.now().plus(SNOOZE)

            val existing = dismissalRepository.findByUserIdAndHintKey(userId.value, key)
            if (existing != null) {
                existing.snoozeUntil = snoozeUntil
                dismissalRepository.save(existing)
            } else {
                dismissalRepository.save(
                    DashboardHintDismissalEntity(
                        userId = userId.value,
                        hintKey = key,
                        snoozeUntil = snoozeUntil
                    )
                )
            }
            Unit
        }
}

@Service
class DisableWorkTimeTrackingHandler(
    private val roleRepository: RoleRepository
) {
    @Transactional
    suspend fun disableForStudio(studioId: StudioId, @Suppress("UNUSED_PARAMETER") requestedBy: UserId) =
        withContext(Dispatchers.IO) {
            val roles = roleRepository.findByStudioId(studioId.value)
                .filter { it.trackWorkTime }
            roles.forEach { it.trackWorkTime = false }
            roleRepository.saveAll(roles)
            Unit
        }
}

package pl.detailing.crm.rolepreview

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.rolepreview.infrastructure.RolePreviewSandboxRepository
import pl.detailing.crm.studio.domain.StudioKind
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Duration
import java.time.Instant

/**
 * Sprząta piaskownice podglądu roli, które się skończyły, choć nikt ich nie zamknął:
 * po czasie życia, po bezczynności i takie, do których nikt nie wszedł kodem.
 *
 * Dostęp do wygasłej piaskownicy kończy się wcześniej i bez tego zadania - filtr sprawdza
 * ważność przy każdym żądaniu. Tu chodzi o to, żeby po piaskownicy nie zostały dane.
 *
 * Drugie przejście łapie sieroty: studia ROLE_PREVIEW bez wpisu w rejestrze (np. po awarii
 * w połowie usuwania), starsze niż najdłuższe możliwe życie piaskownicy z zapasem.
 */
@Component
class RolePreviewCleanupJob(
    private val rolePreviewService: RolePreviewService,
    private val sandboxRepository: RolePreviewSandboxRepository,
    private val studioRepository: StudioRepository,
    private val eraser: RolePreviewSandboxEraser,
    private val properties: RolePreviewProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${crm.role-preview.cleanup-interval-ms:60000}",
        initialDelayString = "\${crm.role-preview.cleanup-interval-ms:60000}"
    )
    fun cleanup() {
        val now = Instant.now()
        runCatching { rolePreviewService.removeEnded(now) }
            .onSuccess { if (it > 0) logger.info("Usunięto wygasłe piaskownice podglądu roli: {}", it) }
            .onFailure { logger.error("Sprzątanie piaskownic podglądu roli nie powiodło się: {}", it.message, it) }

        runCatching { removeOrphans(now) }
            .onFailure { logger.error("Sprzątanie osieroconych piaskownic nie powiodło się: {}", it.message, it) }
    }

    private fun removeOrphans(now: Instant) {
        val cutoff = now.minus(Duration.ofMinutes(properties.maxLifetimeMinutes)).minus(ORPHAN_GRACE)
        studioRepository.findIdsByKindCreatedBefore(StudioKind.ROLE_PREVIEW, cutoff)
            .filter { sandboxRepository.findBySandboxStudioId(it) == null }
            .forEach { studioId ->
                runCatching { eraser.eraseOrphan(studioId) }
                    .onSuccess { logger.warn("Usunięto osieroconą piaskownicę podglądu roli {}", studioId) }
                    .onFailure { logger.error("Nie udało się usunąć osieroconej piaskownicy {}: {}", studioId, it.message, it) }
            }
    }

    private companion object {
        val ORPHAN_GRACE: Duration = Duration.ofHours(1)
    }
}

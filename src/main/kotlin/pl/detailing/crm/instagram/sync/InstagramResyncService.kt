package pl.detailing.crm.instagram.sync

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import java.time.Duration

/** Wynik ręcznego ponowienia. */
data class ResyncResultDto(
    /** Ile profili faktycznie próbowaliśmy odświeżyć (tylko te z błędem). */
    val attempted: Int,
    /** Ile z nich wróciło do normy. */
    val recovered: Int,
    /** Ile nadal się nie powiodło. */
    val stillFailing: Int
)

/** Ponowienie odbite przez cooldown — chroni dzienny budżet wywołań RapidAPI. */
class ResyncCooldownException(val retryAfterSeconds: Long) :
    RuntimeException("Ponowienie było już uruchamiane przed chwilą")

/**
 * Ręczne ponowienie pobrania danych dla profili oznaczonych jako niedostępne.
 *
 * Każde wywołanie kosztuje realne zapytania do RapidAPI, a budżet jest dzienny i wspólny
 * dla całej instalacji — dlatego quota jest chroniona na trzech poziomach:
 *
 *  1. Ponawiamy **wyłącznie profile z ustawioną flagą błędu**. Kliknięcie, gdy wszystko
 *     działa, nie wykonuje ani jednego zapytania.
 *  2. Cooldown per studio ([cooldownMinutes]) — zakładany dopiero wtedy, gdy realnie
 *     wydaliśmy quotę, więc kliknięcie „na pusto" nie blokuje użytkownika.
 *  3. Twardy limit [MAX_PROFILES_PER_RUN] profili na jedno wywołanie.
 *
 * Ponad tym wszystkim nadal obowiązuje dzienny budżet z RapidApiCallGate.
 */
@Service
class InstagramResyncService(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val orchestrator: InstagramSyncOrchestrator,
    private val redisTemplate: StringRedisTemplate,
    @Value("\${instagram.resync.cooldown-minutes:10}") private val cooldownMinutes: Long
) {
    private val log = LoggerFactory.getLogger(InstagramResyncService::class.java)

    companion object {
        private const val MAX_PROFILES_PER_RUN = 10
    }

    fun resyncFailed(studioId: StudioId): ResyncResultDto {
        val failed = studioProfileRepository
            .findByStudioIdAndStatus(studioId.value, InstagramProfileStatus.ACTIVE)
            .let { links -> profileRepository.findAllById(links.map { it.profileId }) }
            .filter { it.apiError }
            .take(MAX_PROFILES_PER_RUN)

        // Nie ma czego ponawiać — nie wydajemy quoty i nie uruchamiamy cooldownu.
        if (failed.isEmpty()) return ResyncResultDto(attempted = 0, recovered = 0, stillFailing = 0)

        val cooldownKey = "instagram:resync:cooldown:${studioId.value}"
        if (redisTemplate.hasKey(cooldownKey)) {
            throw ResyncCooldownException(redisTemplate.getExpire(cooldownKey).coerceAtLeast(1))
        }
        redisTemplate.opsForValue().set(cooldownKey, "1", Duration.ofMinutes(cooldownMinutes))

        var recovered = 0
        failed.forEach { profile ->
            runCatching { orchestrator.resyncProfile(profile) }
                .onSuccess { if (it) recovered++ }
                .onFailure { log.warn("Instagram resync: @{} nadal niedostępny: {}", profile.username, it.message) }
        }

        log.info(
            "Instagram resync [studioId={}]: próbowano={}, odzyskano={}",
            studioId, failed.size, recovered
        )
        return ResyncResultDto(
            attempted = failed.size,
            recovered = recovered,
            stillFailing = failed.size - recovered
        )
    }
}

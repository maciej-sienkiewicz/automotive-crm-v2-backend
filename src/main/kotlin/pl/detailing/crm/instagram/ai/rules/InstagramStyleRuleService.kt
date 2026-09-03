package pl.detailing.crm.instagram.ai.rules

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.ai.infrastructure.InstagramStyleRuleEntity
import pl.detailing.crm.instagram.ai.infrastructure.InstagramStyleRuleRepository
import pl.detailing.crm.instagram.ai.model.CreateStyleRuleRequest
import pl.detailing.crm.instagram.ai.model.StyleRuleResponse
import pl.detailing.crm.instagram.ai.model.UpdateStyleRuleRequest
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * CRUD reguł stylistycznych studia.
 *
 * Każda operacja jest kluczowana parą (id, studioId) — regułę innego studia traktujemy
 * jak nieistniejącą (404), żeby po samym kodzie odpowiedzi nie dało się sprawdzić,
 * czy dany identyfikator w ogóle istnieje.
 */
@Service
class InstagramStyleRuleService(
    private val repository: InstagramStyleRuleRepository
) {
    private val logger = LoggerFactory.getLogger(InstagramStyleRuleService::class.java)

    companion object {
        const val MAX_RULE_LENGTH = 500

        /**
         * Reguły trafiają do promptu w całości, a prompt ma jeszcze zmieścić przykłady
         * few-shot i treść posta — powyżej dwudziestu aktywnych reguł budżet tokenów
         * przestaje się spinać, a model i tak zaczyna gubić dalsze pozycje listy.
         */
        const val MAX_ACTIVE_RULES = 20
    }

    @Transactional(readOnly = true)
    fun list(studioId: StudioId): List<StyleRuleResponse> =
        repository.findByStudioIdOrderByCreatedAtAsc(studioId.value).map { it.toResponse() }

    /** Reguły aktywne — dokładnie ta lista trafia do generatora i weryfikatora. */
    @Transactional(readOnly = true)
    fun activeRuleTexts(studioId: StudioId): List<String> =
        repository.findByStudioIdAndActiveTrueOrderByCreatedAtAsc(studioId.value).map { it.ruleText }

    @Transactional
    fun create(studioId: StudioId, request: CreateStyleRuleRequest): StyleRuleResponse {
        val ruleText = validateRuleText(request.ruleText)

        val activeCount = repository.countByStudioIdAndActiveTrue(studioId.value)
        if (activeCount >= MAX_ACTIVE_RULES) {
            throw ValidationException(
                "Osiągnięto limit $MAX_ACTIVE_RULES aktywnych reguł stylistycznych. " +
                    "Wyłącz lub usuń istniejącą regułę, zanim dodasz kolejną."
            )
        }

        val now = Instant.now()
        val saved = repository.save(
            InstagramStyleRuleEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                ruleText = ruleText,
                active = true,
                createdAt = now,
                updatedAt = now
            )
        )
        logger.info("Style rule created: studioId={}, ruleId={}", studioId, saved.id)
        return saved.toResponse()
    }

    @Transactional
    fun update(studioId: StudioId, ruleId: UUID, request: UpdateStyleRuleRequest): StyleRuleResponse {
        val rule = requireOwnRule(studioId, ruleId)

        request.ruleText?.let { rule.ruleText = validateRuleText(it) }

        request.active?.let { active ->
            // Reaktywacja to dodanie kolejnej pozycji do promptu — liczy się do tego samego limitu.
            if (active && !rule.active && repository.countByStudioIdAndActiveTrue(studioId.value) >= MAX_ACTIVE_RULES) {
                throw ValidationException(
                    "Osiągnięto limit $MAX_ACTIVE_RULES aktywnych reguł stylistycznych. " +
                        "Wyłącz inną regułę, zanim aktywujesz tę."
                )
            }
            rule.active = active
        }

        rule.updatedAt = Instant.now()
        logger.info("Style rule updated: studioId={}, ruleId={}, active={}", studioId, ruleId, rule.active)
        return repository.save(rule).toResponse()
    }

    @Transactional
    fun delete(studioId: StudioId, ruleId: UUID) {
        val rule = requireOwnRule(studioId, ruleId)
        repository.delete(rule)
        logger.info("Style rule deleted: studioId={}, ruleId={}", studioId, ruleId)
    }

    // ── Pomocnicze ────────────────────────────────────────────────────────────

    private fun requireOwnRule(studioId: StudioId, ruleId: UUID): InstagramStyleRuleEntity =
        repository.findByIdAndStudioId(ruleId, studioId.value)
            ?: throw EntityNotFoundException("Nie znaleziono reguły stylistycznej o id: $ruleId")

    private fun validateRuleText(raw: String): String {
        val ruleText = raw.trim()
        if (ruleText.isEmpty()) throw ValidationException("Treść reguły nie może być pusta.")
        if (ruleText.length > MAX_RULE_LENGTH) {
            throw ValidationException("Reguła może mieć maksymalnie $MAX_RULE_LENGTH znaków (ma ${ruleText.length}).")
        }
        return ruleText
    }

    private fun InstagramStyleRuleEntity.toResponse() = StyleRuleResponse(
        id = id.toString(),
        ruleText = ruleText,
        active = active,
        createdAt = createdAt.toEpochMilli(),
        updatedAt = updatedAt.toEpochMilli()
    )
}

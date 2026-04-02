package pl.detailing.crm.smscampaigns.infrastructure

import org.springframework.stereotype.Component
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository

/**
 * Adapter that implements the domain port [SmsAutomationConfigRepository] using JPA.
 * Handles the upsert logic: find-by-studioId, preserve the entity PK on updates.
 */
@Component
class SmsAutomationConfigRepositoryAdapter(
    private val jpaRepository: SmsAutomationConfigJpaRepository
) : SmsAutomationConfigRepository {

    override fun findByStudioId(studioId: StudioId): SmsAutomationConfig? =
        jpaRepository.findByStudioId(studioId.value)?.toDomain()

    override fun save(config: SmsAutomationConfig): SmsAutomationConfig {
        val existing = jpaRepository.findByStudioId(config.studioId.value)
        val entity = if (existing != null) {
            // Update in-place, preserving id and createdAt
            existing.preVisitEnabled = config.preVisit.enabled
            existing.preVisitOffsetMinutes = config.preVisit.offsetMinutes
            existing.preVisitMessageTemplate = config.preVisit.messageTemplate
            existing.postVisitEnabled = config.postVisit.enabled
            existing.postVisitOffsetMinutes = config.postVisit.offsetMinutes
            existing.postVisitMessageTemplate = config.postVisit.messageTemplate
            existing.updatedAt = java.time.Instant.now()
            existing
        } else {
            SmsAutomationConfigEntity.fromDomain(config)
        }
        return jpaRepository.save(entity).toDomain()
    }

    override fun findAllWithAnyRuleEnabled(): List<SmsAutomationConfig> =
        jpaRepository.findAllWithAnyRuleEnabled().map { it.toDomain() }
}

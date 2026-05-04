package pl.detailing.crm.email.infrastructure

import org.springframework.stereotype.Component
import pl.detailing.crm.email.domain.EmailAutomationConfig
import pl.detailing.crm.email.domain.EmailAutomationConfigRepository
import pl.detailing.crm.shared.StudioId
import java.time.Instant

@Component
class EmailAutomationConfigRepositoryAdapter(
    private val jpaRepository: EmailAutomationConfigJpaRepository
) : EmailAutomationConfigRepository {

    override fun findByStudioId(studioId: StudioId): EmailAutomationConfig? =
        jpaRepository.findByStudioId(studioId.value)?.toDomain()

    override fun save(config: EmailAutomationConfig): EmailAutomationConfig {
        val existing = jpaRepository.findByStudioId(config.studioId.value)
        val entity = if (existing != null) {
            existing.visitWelcomeEnabled = config.visitWelcome.enabled
            existing.visitWelcomeSubjectTemplate = config.visitWelcome.subjectTemplate
            existing.visitWelcomeBodyTemplate = config.visitWelcome.bodyTemplate
            existing.visitReadyForPickupEnabled = config.visitReadyForPickup.enabled
            existing.visitReadyForPickupSubjectTemplate = config.visitReadyForPickup.subjectTemplate
            existing.visitReadyForPickupBodyTemplate = config.visitReadyForPickup.bodyTemplate
            existing.updatedAt = Instant.now()
            existing
        } else {
            EmailAutomationConfigEntity.fromDomain(config)
        }
        return jpaRepository.save(entity).toDomain()
    }
}

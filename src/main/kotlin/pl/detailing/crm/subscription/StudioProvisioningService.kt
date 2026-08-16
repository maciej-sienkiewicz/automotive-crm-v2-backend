package pl.detailing.crm.subscription

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.studio.domain.Studio
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import java.time.Instant
import java.util.UUID

/**
 * The ONE place a studio comes into existence.
 *
 * Exists as a separate bean so the provisioning invariant — "a studio and its
 * `studio_subscription_plans` row are created atomically" — is enforced by a
 * REAL transaction. Putting @Transactional on a suspend method running under
 * `withContext(Dispatchers.IO)` (the previous shape) silently provides no such
 * guarantee; a plain blocking bean method does.
 *
 * If [EntitlementService.ensurePlanAssigned] fails (e.g. catalog seeder failure),
 * the studio row rolls back with it — signup fails whole, no orphan is left.
 */
@Service
class StudioProvisioningService(
    private val studioRepository: StudioRepository,
    private val entitlementService: EntitlementService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun provisionStudio(name: String): Studio {
        val studio = Studio(
            id = StudioId.random(),
            name = name,
            subscriptionStatus = SubscriptionStatus.NO_PLAN,
            trialEndsAt = null,
            subscriptionEndsAt = null,
            trialUsed = false,
            createdAt = Instant.now(),
            emailAlias = UUID.randomUUID().toString().replace("-", "")
        )
        studioRepository.save(StudioEntity.fromDomain(studio))
        entitlementService.ensurePlanAssigned(studio.id)
        logger.info("Provisioned studio={} with default plan row", studio.id)
        return studio
    }
}

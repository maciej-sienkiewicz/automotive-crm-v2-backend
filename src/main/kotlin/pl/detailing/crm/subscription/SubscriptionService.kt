package pl.detailing.crm.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.studio.domain.Studio
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class SubscriptionService(
    private val studioRepository: StudioRepository
) {

    suspend fun createStudioWithTrial(name: String): Studio = withContext(Dispatchers.IO) {
        val trialEndsAt = Instant.now().plus(14, ChronoUnit.DAYS)

        val studio = Studio(
            id = StudioId.random(),
            name = name,
            subscriptionStatus = SubscriptionStatus.TRIALING,
            trialEndsAt = trialEndsAt,
            createdAt = Instant.now()
        )

        val entity = StudioEntity.fromDomain(studio)
        studioRepository.save(entity)

        studio
    }

    suspend fun getStudio(studioId: StudioId): Studio = withContext(Dispatchers.IO) {
        val entity = studioRepository.findByStudioId(studioId.value)
            ?: throw EntityNotFoundException("Studio not found: $studioId")
        entity.toDomain()
    }

    suspend fun validateAccess(studioId: StudioId) = withContext(Dispatchers.IO) {
        val studio = getStudio(studioId)

        if (!studio.isAccessible()) {
            throw ForbiddenException(
                "Studio access denied. Subscription status: ${studio.subscriptionStatus}"
            )
        }
    }

    @Transactional
    suspend fun expireTrials() = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val expiredStudios = studioRepository.findExpiredTrials(now)

        expiredStudios.forEach { entity ->
            entity.subscriptionStatus = SubscriptionStatus.EXPIRED
            studioRepository.save(entity)
        }

        expiredStudios.size
    }

    @Transactional
    suspend fun activateSubscription(studioId: StudioId) = withContext(Dispatchers.IO) {
        val entity = studioRepository.findByStudioId(studioId.value)
            ?: throw EntityNotFoundException("Studio not found: $studioId")

        entity.subscriptionStatus = SubscriptionStatus.ACTIVE
        entity.trialEndsAt = null
        studioRepository.save(entity)
    }

    suspend fun getSubscriptionInfo(studioId: StudioId): SubscriptionInfo = withContext(Dispatchers.IO) {
        val studio = getStudio(studioId)

        SubscriptionInfo(
            status = studio.subscriptionStatus,
            daysRemaining = studio.getDaysRemaining(),
            isAccessible = studio.isAccessible()
        )
    }
}

data class SubscriptionInfo(
    val status: SubscriptionStatus,
    val daysRemaining: Long?,
    val isAccessible: Boolean
)
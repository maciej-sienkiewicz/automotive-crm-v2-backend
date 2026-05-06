package pl.detailing.crm.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.studio.domain.Studio
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.domain.SubscriptionPayment
import pl.detailing.crm.subscription.domain.SubscriptionPlan
import pl.detailing.crm.subscription.domain.SubscriptionPlanType
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentEntity
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentJpaRepository
import pl.detailing.crm.smscredits.payment.MockPaymentGateway
import pl.detailing.crm.smscredits.payment.PaymentRequest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class SubscriptionService(
    private val studioRepository: StudioRepository,
    private val paymentJpaRepository: SubscriptionPaymentJpaRepository,
    private val paymentGateway: MockPaymentGateway
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val TRIAL_DURATION_DAYS = 60L
    }

    // ─── Studio creation ──────────────────────────────────────────────────────

    suspend fun createStudioWithTrial(name: String): Studio = withContext(Dispatchers.IO) {
        val trialEndsAt = Instant.now().plus(TRIAL_DURATION_DAYS, ChronoUnit.DAYS)

        val studio = Studio(
            id = StudioId.random(),
            name = name,
            subscriptionStatus = SubscriptionStatus.TRIALING,
            trialEndsAt = trialEndsAt,
            subscriptionEndsAt = null,
            trialUsed = true,
            createdAt = Instant.now(),
            emailAlias = UUID.randomUUID().toString().replace("-", "")
        )

        studioRepository.save(StudioEntity.fromDomain(studio))
        studio
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    suspend fun getStudio(studioId: StudioId): Studio = withContext(Dispatchers.IO) {
        studioRepository.findByStudioId(studioId.value)?.toDomain()
            ?: throw EntityNotFoundException("Studio not found: $studioId")
    }

    suspend fun validateAccess(studioId: StudioId) = withContext(Dispatchers.IO) {
        val studio = getStudio(studioId)
        if (!studio.isAccessible()) {
            throw ForbiddenException("Brak dostępu. Status subskrypcji: ${studio.subscriptionStatus}")
        }
    }

    suspend fun getSubscriptionInfo(studioId: StudioId): SubscriptionInfo = withContext(Dispatchers.IO) {
        val studio = getStudio(studioId)
        studio.toSubscriptionInfo()
    }

    // ─── Purchase ─────────────────────────────────────────────────────────────

    @Transactional
    suspend fun purchaseSubscription(studioId: StudioId, planType: SubscriptionPlanType): SubscriptionInfo =
        withContext(Dispatchers.IO) {
            val entity = studioRepository.findByStudioId(studioId.value)
                ?: throw EntityNotFoundException("Studio not found: $studioId")

            val plan = SubscriptionPlan.forType(planType)

            val paymentResult = paymentGateway.charge(
                PaymentRequest(
                    amountInCents = plan.priceGrossInCents,
                    currency = plan.currency,
                    description = "Subskrypcja ${plan.name} — ${plan.durationDays} dni",
                    studioId = studioId.value
                )
            )

            if (!paymentResult.success) {
                throw ValidationException("Płatność nie powiodła się: ${paymentResult.message}")
            }

            // Correct renewal: extend from existing end date if subscription is still active,
            // otherwise start from now — so two consecutive purchases stack properly.
            val now = Instant.now()
            val baseDate = if (
                entity.subscriptionStatus == SubscriptionStatus.ACTIVE &&
                entity.subscriptionEndsAt != null &&
                entity.subscriptionEndsAt!!.isAfter(now)
            ) entity.subscriptionEndsAt!! else now

            val newEndsAt = baseDate.plus(plan.durationDays.toLong(), ChronoUnit.DAYS)

            entity.subscriptionStatus = SubscriptionStatus.ACTIVE
            entity.subscriptionEndsAt = newEndsAt
            entity.trialEndsAt = null
            studioRepository.save(entity)

            paymentJpaRepository.save(
                SubscriptionPaymentEntity(
                    id = UUID.randomUUID(),
                    studioId = studioId.value,
                    planType = planType,
                    durationDays = plan.durationDays,
                    amountInCents = plan.priceGrossInCents,
                    currency = plan.currency,
                    paymentTransactionId = paymentResult.transactionId,
                    subscriptionEndsAt = newEndsAt,
                    createdAt = now
                )
            )

            logger.info(
                "Studio={} purchased {} subscription — extends to {} (txId={})",
                studioId, planType, newEndsAt, paymentResult.transactionId
            )

            entity.toDomain().toSubscriptionInfo()
        }

    // ─── Maintenance ──────────────────────────────────────────────────────────

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
    suspend fun expireSubscriptions() = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val expiredStudios = studioRepository.findExpiredSubscriptions(now)

        expiredStudios.forEach { entity ->
            entity.subscriptionStatus = SubscriptionStatus.EXPIRED
            studioRepository.save(entity)
        }

        expiredStudios.size
    }
}

// ─── Supporting types ─────────────────────────────────────────────────────────

data class SubscriptionInfo(
    val status: SubscriptionStatus,
    val daysRemaining: Long?,
    val subscriptionEndsAt: Instant?,
    val trialEndsAt: Instant?,
    val isAccessible: Boolean,
    val trialUsed: Boolean
)

private fun Studio.toSubscriptionInfo() = SubscriptionInfo(
    status = subscriptionStatus,
    daysRemaining = getDaysRemaining(),
    subscriptionEndsAt = subscriptionEndsAt,
    trialEndsAt = trialEndsAt,
    isAccessible = isAccessible(),
    trialUsed = trialUsed
)

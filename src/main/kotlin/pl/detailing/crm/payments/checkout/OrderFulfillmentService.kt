package pl.detailing.crm.payments.checkout

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.payments.order.PaymentOrderEntity
import pl.detailing.crm.payments.order.PaymentOrderStatus
import pl.detailing.crm.payments.order.PaymentOrderType
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.management.PendingPlanChangeRepository
import pl.detailing.crm.subscription.infrastructure.SubscriptionEventType
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogEntity
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Applies the business effect of a PAID [PaymentOrderEntity].
 *
 * Called exactly once per order by the P24 webhook (or synchronously by the
 * checkout in mock mode). Idempotency is guaranteed by the caller flipping the
 * order status PENDING → PAID inside the same transaction.
 */
@Service
class OrderFulfillmentService(
    private val studioRepository: StudioRepository,
    private val entitlementService: EntitlementService,
    private val paymentLogRepository: SubscriptionPaymentLogRepository,
    private val pendingPlanChangeRepository: PendingPlanChangeRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val BILLING_PERIOD_DAYS = 30L
    }

    @Transactional
    fun fulfill(order: PaymentOrderEntity) {
        val studioId = StudioId(order.studioId)

        when (order.type) {
            PaymentOrderType.INITIAL_PURCHASE -> fulfillInitialPurchase(order, studioId)
            PaymentOrderType.RENEWAL -> fulfillRenewal(order, studioId)
            PaymentOrderType.PLAN_UPGRADE -> fulfillPlanUpgrade(order, studioId)
            PaymentOrderType.ADD_ON_PURCHASE -> fulfillAddOnPurchase(order, studioId)
        }

        entitlementService.evictEntitlementsCache(studioId)
        logger.info("Order fulfilled id={} type={} studio={}", order.id, order.type, studioId)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun fulfillInitialPurchase(order: PaymentOrderEntity, studioId: StudioId) {
        val planKey = requireNotNull(order.planKey) { "INITIAL_PURCHASE order without planKey: ${order.id}" }

        val studio = requireStudio(order)
        studio.subscriptionStatus = SubscriptionStatus.ACTIVE
        studio.subscriptionEndsAt = Instant.now().plus(BILLING_PERIOD_DAYS, ChronoUnit.DAYS)
        studio.trialEndsAt = null
        studioRepository.save(studio)

        entitlementService.assignPlan(studioId, planKey)
        order.addOnKeys.forEach { entitlementService.activateAddOn(studioId, it) }

        log(order, SubscriptionEventType.SUBSCRIPTION_PURCHASE, order.description)
    }

    private fun fulfillRenewal(order: PaymentOrderEntity, studioId: StudioId) {
        val studio = requireStudio(order)
        val now = Instant.now()
        val base = studio.subscriptionEndsAt?.takeIf { it.isAfter(now) } ?: now

        studio.subscriptionStatus = SubscriptionStatus.ACTIVE
        studio.subscriptionEndsAt = base.plus(BILLING_PERIOD_DAYS, ChronoUnit.DAYS)
        studio.trialEndsAt = null
        studioRepository.save(studio)

        log(order, SubscriptionEventType.SUBSCRIPTION_RENEWAL, order.description)
    }

    private fun fulfillPlanUpgrade(order: PaymentOrderEntity, studioId: StudioId) {
        val planKey = requireNotNull(order.planKey) { "PLAN_UPGRADE order without planKey: ${order.id}" }
        // The buyer changed their mind — a paid upgrade supersedes any scheduled downgrade.
        pendingPlanChangeRepository.cancelPendingForStudio(order.studioId)
        entitlementService.assignPlan(studioId, planKey)
        log(order, SubscriptionEventType.PLAN_UPGRADE, order.description)
    }

    private fun fulfillAddOnPurchase(order: PaymentOrderEntity, studioId: StudioId) {
        order.addOnKeys.forEach { entitlementService.activateAddOn(studioId, it) }
        log(order, SubscriptionEventType.ADD_ON_ACTIVATION, order.description)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun requireStudio(order: PaymentOrderEntity) =
        studioRepository.findByStudioId(order.studioId)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: ${order.studioId}")

    private fun log(order: PaymentOrderEntity, eventType: SubscriptionEventType, description: String) {
        paymentLogRepository.save(
            SubscriptionPaymentLogEntity(
                studioId = order.studioId,
                eventType = eventType,
                amountInCents = order.amountCents,
                currency = order.currency,
                transactionId = order.p24OrderId?.toString() ?: order.sessionId,
                planKey = order.planKey,
                addOnKey = order.addOnKeys.singleOrNull(),
                description = description
            )
        )
    }
}

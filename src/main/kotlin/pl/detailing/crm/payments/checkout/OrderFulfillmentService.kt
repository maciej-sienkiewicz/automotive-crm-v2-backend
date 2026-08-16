package pl.detailing.crm.payments.checkout

import io.micrometer.core.instrument.MeterRegistry
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
import pl.detailing.crm.smscampaigns.CommunicationOnboardingService
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.management.PendingPlanChangeRepository
import pl.detailing.crm.subscription.infrastructure.SubscriptionEventType
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogEntity
import pl.detailing.crm.subscription.infrastructure.SubscriptionPaymentLogRepository
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Applies the business effect of a PAID [PaymentOrderEntity].
 *
 * Failure semantics: [fulfill] joins the caller's transaction (the PENDING→PAID
 * flip in CheckoutService.completeOrder), so a failure here rolls the flip back
 * and the order stays PENDING — the P24 webhook retry re-drives fulfillment.
 * "PAID without activation" is therefore never a persistent state; every failure
 * is additionally counted (`subscription.fulfillment.failures`) and logged with
 * full order context so a stuck retry loop pages someone instead of rotting.
 */
@Service
class OrderFulfillmentService(
    private val studioRepository: StudioRepository,
    private val entitlementService: EntitlementService,
    private val paymentLogRepository: SubscriptionPaymentLogRepository,
    private val pendingPlanChangeRepository: PendingPlanChangeRepository,
    private val communicationOnboardingService: CommunicationOnboardingService,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        const val BILLING_PERIOD_DAYS = 30L
    }

    @Transactional
    fun fulfill(order: PaymentOrderEntity) {
        val studioId = StudioId(order.studioId)

        try {
            when (order.type) {
                PaymentOrderType.INITIAL_PURCHASE -> fulfillInitialPurchase(order, studioId)
                PaymentOrderType.RENEWAL -> fulfillRenewal(order, studioId)
                PaymentOrderType.PLAN_UPGRADE -> fulfillPlanUpgrade(order, studioId)
                PaymentOrderType.ADD_ON_PURCHASE -> fulfillAddOnPurchase(order, studioId)
            }

            // Whatever was bought, if the studio can now send messages, make the
            // feature actually usable: templates on, starter credits in. Checked on
            // the resulting entitlements rather than on the order, because messaging
            // arrives both as an add-on and as part of the FULL plan.
            provisionCommunicationIfEnabled(studioId)
        } catch (ex: Exception) {
            meterRegistry.counter("subscription.fulfillment.failures", "orderType", order.type.name).increment()
            logger.error(
                "Fulfillment FAILED orderId={} type={} studio={} amountCents={} — transaction will roll " +
                "back to PENDING and be re-driven by the P24 webhook retry. Investigate before retries exhaust.",
                order.id, order.type, studioId, order.amountCents, ex
            )
            throw ex
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
        // Self-heal instead of exploding after the money is captured: checkout already
        // validates the plan row exists, but if provisioning drifted between checkout
        // and webhook, re-establish the invariant and deliver what was paid for.
        entitlementService.ensurePlanAssigned(studioId, order.planKey ?: PlanKey.BASIC)
        order.addOnKeys.forEach { entitlementService.activateAddOn(studioId, it) }
        log(order, SubscriptionEventType.ADD_ON_ACTIVATION, order.description)
    }

    /**
     * Runs the communication module's onboarding when the studio holds SMS_EMAIL
     * after fulfillment. Safe to reach on every order — the service itself is
     * idempotent, and a studio that already had messaging simply keeps what it had.
     */
    private fun provisionCommunicationIfEnabled(studioId: StudioId) {
        val entitlements = entitlementService.getEntitlements(studioId)
        if (!entitlements.hasFeature(FeatureKey.SMS_EMAIL)) return
        communicationOnboardingService.onCommunicationEnabled(studioId, entitlements.planKey)
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
                addOnKey = order.addOnKeys.singleOrNull()?.name,
                description = description
            )
        )
    }
}

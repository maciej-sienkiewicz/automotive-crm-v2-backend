package pl.detailing.crm.payments.checkout

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.payments.order.*
import pl.detailing.crm.payments.p24.Przelewy24Client
import pl.detailing.crm.payments.p24.Przelewy24Properties
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.infrastructure.AddOnJpaRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.PlanJpaRepository
import pl.detailing.crm.subscription.pricing.ProrationService
import java.util.UUID

// ─── API types ────────────────────────────────────────────────────────────────

data class CheckoutRequest(
    val type: PaymentOrderType,
    val planKey: PlanKey? = null,
    val addOnKeys: List<AddOnKey> = emptyList()
)

/**
 * [paymentUrl] — Przelewy24 payment page to redirect the buyer to; null when the
 *   order required no payment (or mock mode) and was fulfilled immediately.
 */
data class CheckoutResponse(
    val orderId: UUID,
    val status: PaymentOrderStatus,
    val amountCents: Long,
    val currency: String,
    val description: String,
    val paymentUrl: String?
)

/**
 * Creates payment orders for every paid subscription operation and hands the buyer
 * off to Przelewy24. Zero-amount operations (e.g. add-on activation during trial)
 * are fulfilled immediately without a payment round-trip.
 *
 * Pricing rules (Product decision):
 *   - FULL already contains every module — orders combining FULL with add-ons are rejected.
 *   - À la carte modules are priced above their share of the FULL bundle, so
 *     self-assembled packages always cost more than FULL (BASIC 99 + all modules 256 = 355 vs FULL 299).
 */
@Service
class CheckoutService(
    private val properties: Przelewy24Properties,
    private val p24Client: Przelewy24Client,
    private val orderRepository: PaymentOrderRepository,
    private val fulfillmentService: OrderFulfillmentService,
    private val studioRepository: StudioRepository,
    private val entitlementService: EntitlementService,
    private val prorationService: ProrationService,
    private val planRepository: PlanJpaRepository,
    private val addOnRepository: AddOnJpaRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun checkout(studioId: StudioId, buyerEmail: String, request: CheckoutRequest): CheckoutResponse {
        val draft = when (request.type) {
            PaymentOrderType.INITIAL_PURCHASE -> prepareInitialPurchase(studioId, request)
            PaymentOrderType.RENEWAL -> prepareRenewal(studioId)
            PaymentOrderType.PLAN_UPGRADE -> preparePlanUpgrade(studioId, request)
            PaymentOrderType.ADD_ON_PURCHASE -> prepareAddOnPurchase(studioId, request)
        }

        val order = orderRepository.save(
            PaymentOrderEntity(
                studioId = studioId.value,
                sessionId = "CRM-${UUID.randomUUID()}",
                type = request.type,
                planKey = draft.planKey,
                addOnKeysRaw = PaymentOrderEntity.encodeAddOnKeys(draft.addOnKeys),
                amountCents = draft.amountCents,
                description = draft.description
            )
        )

        // Nothing to charge, mock explicitly enabled, or no P24 credentials → settle immediately.
        val useMock = properties.mockMode || !properties.isConfigured
        if (order.amountCents <= 0 || useMock) {
            order.status = PaymentOrderStatus.PAID
            order.paidAt = java.time.Instant.now()
            orderRepository.save(order)
            fulfillmentService.fulfill(order)
            logger.info(
                "Order {} settled without P24 (amount={} mockMode={} configured={})",
                order.id, order.amountCents, properties.mockMode, properties.isConfigured
            )
            return order.toResponse(paymentUrl = null)
        }

        val token = p24Client.registerTransaction(
            Przelewy24Client.RegisterTransactionCommand(
                sessionId = order.sessionId,
                amountCents = order.amountCents,
                description = order.description,
                email = buyerEmail,
                urlReturn = "${properties.frontendBaseUrl}/payments/result?orderId=${order.id}",
                urlStatus = "${properties.backendBaseUrl}/api/v1/payments/p24/status"
            )
        )
        order.p24Token = token
        orderRepository.save(order)

        return order.toResponse(paymentUrl = properties.paymentPageUrl(token))
    }

    /**
     * Settles an order after a verified P24 notification. Idempotent: a second
     * notification for an already-PAID order is a no-op.
     */
    @Transactional
    fun completeOrder(sessionId: String, p24OrderId: Long) {
        val order = orderRepository.findBySessionId(sessionId)
            ?: throw EntityNotFoundException("Zamówienie nie zostało znalezione: $sessionId")

        if (order.status == PaymentOrderStatus.PAID) {
            logger.info("Order {} already PAID — duplicate notification ignored", order.id)
            return
        }
        if (order.status != PaymentOrderStatus.PENDING) {
            throw ValidationException("Zamówienie ${order.id} ma status ${order.status} i nie może zostać opłacone")
        }

        order.status = PaymentOrderStatus.PAID
        order.p24OrderId = p24OrderId
        order.paidAt = java.time.Instant.now()
        orderRepository.save(order)

        fulfillmentService.fulfill(order)
    }

    @Transactional
    fun failOrder(sessionId: String, reason: String) {
        val order = orderRepository.findBySessionId(sessionId) ?: return
        if (order.status != PaymentOrderStatus.PENDING) return
        order.status = PaymentOrderStatus.FAILED
        order.failureReason = reason.take(500)
        orderRepository.save(order)
        logger.warn("Order {} marked FAILED: {}", order.id, reason)
    }

    fun getOrder(studioId: StudioId, orderId: UUID): PaymentOrderEntity =
        orderRepository.findByIdAndStudioId(orderId, studioId.value)
            ?: throw EntityNotFoundException("Zamówienie nie zostało znalezione: $orderId")

    // ─── Order drafts ─────────────────────────────────────────────────────────

    private data class OrderDraft(
        val planKey: PlanKey?,
        val addOnKeys: List<AddOnKey>,
        val amountCents: Long,
        val description: String
    )

    /** First purchase: full month of plan + selected modules. Only for NO_PLAN/EXPIRED studios. */
    private fun prepareInitialPurchase(studioId: StudioId, request: CheckoutRequest): OrderDraft {
        val planKey = request.planKey
            ?: throw ValidationException("Wybierz pakiet (BASIC lub FULL).")
        validatePlanAddOnCombination(planKey, request.addOnKeys)

        val studio = requireStudio(studioId)
        if (studio.subscriptionStatus == SubscriptionStatus.ACTIVE) {
            throw ValidationException("Studio ma już aktywną subskrypcję — użyj przedłużenia lub zmiany pakietu.")
        }

        val plan = requirePlan(planKey)
        val addOns = requirePurchasableAddOns(request.addOnKeys)
        val amount = plan.monthlyPriceGrossCents + addOns.sumOf { it.monthlyPriceGrossCents!! }

        val moduleNames = addOns.joinToString(", ") { it.name }
        return OrderDraft(
            planKey = planKey,
            addOnKeys = request.addOnKeys,
            amountCents = amount,
            description = "Pakiet ${plan.name} — 30 dni" +
                    if (addOns.isNotEmpty()) " + moduły: $moduleNames" else ""
        )
    }

    /** Renewal: 30 more days at the current plan + active modules price. */
    private fun prepareRenewal(studioId: StudioId): OrderDraft {
        val studio = requireStudio(studioId)
        if (studio.subscriptionStatus == SubscriptionStatus.NO_PLAN) {
            throw ValidationException("Studio nie ma jeszcze pakietu — wybierz pakiet zamiast przedłużenia.")
        }

        val entitlements = entitlementService.getEntitlements(studioId)
        val plan = requirePlan(entitlements.planKey)
        val activeAddOns = addOnRepository.findAllByKeyIn(entitlements.activeAddOnKeys)
        val amount = plan.monthlyPriceGrossCents + activeAddOns.sumOf { it.monthlyPriceGrossCents ?: 0L }

        return OrderDraft(
            planKey = entitlements.planKey,
            addOnKeys = entitlements.activeAddOnKeys.toList(),
            amountCents = amount,
            description = "Przedłużenie subskrypcji (${plan.name}) — 30 dni"
        )
    }

    /** Mid-period upgrade to a more expensive plan, charged pro rata. */
    private fun preparePlanUpgrade(studioId: StudioId, request: CheckoutRequest): OrderDraft {
        val newPlanKey = request.planKey
            ?: throw ValidationException("Wybierz pakiet docelowy.")
        if (request.addOnKeys.isNotEmpty()) {
            throw ValidationException("Zmiana pakietu nie może zawierać dodatkowych modułów.")
        }

        val entitlements = entitlementService.getEntitlements(studioId)
        val currentPlan = requirePlan(entitlements.planKey)
        val newPlan = requirePlan(newPlanKey)

        if (newPlan.monthlyPriceGrossCents <= currentPlan.monthlyPriceGrossCents) {
            throw ValidationException("Ta operacja obsługuje tylko przejście na droższy pakiet. Downgrade wykonaj przez zmianę planu (bez płatności).")
        }

        val proration = prorationService.calculatePlanUpgrade(
            studioId, currentPlan.monthlyPriceGrossCents, newPlan.monthlyPriceGrossCents
        )

        return OrderDraft(
            planKey = newPlanKey,
            addOnKeys = emptyList(),
            amountCents = proration?.proratedAmountCents ?: 0L,
            description = "Zmiana pakietu na ${newPlan.name}" +
                    (proration?.let { " — ${it.daysRemaining} dni (proporcjonalnie)" } ?: " (okres próbny)")
        )
    }

    /** Mid-period purchase of a single module, charged pro rata. Free during trial. */
    private fun prepareAddOnPurchase(studioId: StudioId, request: CheckoutRequest): OrderDraft {
        val addOnKey = request.addOnKeys.singleOrNull()
            ?: throw ValidationException("Wybierz dokładnie jeden moduł do dokupienia.")

        val entitlements = entitlementService.getEntitlements(studioId)
        if (entitlements.planKey == PlanKey.FULL) {
            throw ValidationException("Pakiet FULL zawiera już wszystkie moduły.")
        }
        if (addOnKey in entitlements.activeAddOnKeys) {
            throw ValidationException("Ten moduł jest już aktywny.")
        }

        val addOn = requirePurchasableAddOns(listOf(addOnKey)).single()
        val proration = prorationService.calculateAddOnActivation(studioId, addOn.monthlyPriceGrossCents!!)

        return OrderDraft(
            planKey = entitlements.planKey,
            addOnKeys = listOf(addOnKey),
            amountCents = proration?.proratedAmountCents ?: 0L,
            description = "Moduł ${addOn.name}" +
                    (proration?.let { " — ${it.daysRemaining} dni (proporcjonalnie)" } ?: " (okres próbny)")
        )
    }

    // ─── Validation helpers ───────────────────────────────────────────────────

    private fun validatePlanAddOnCombination(planKey: PlanKey, addOnKeys: List<AddOnKey>) {
        if (planKey == PlanKey.FULL && addOnKeys.isNotEmpty()) {
            throw ValidationException("Pakiet FULL zawiera wszystkie moduły — nie można dobierać modułów.")
        }
        if (addOnKeys.size != addOnKeys.distinct().size) {
            throw ValidationException("Lista modułów zawiera duplikaty.")
        }
    }

    private fun requireStudio(studioId: StudioId) =
        studioRepository.findByStudioId(studioId.value)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")

    private fun requirePlan(planKey: PlanKey) =
        planRepository.findByKey(planKey)
            ?: throw EntityNotFoundException("Pakiet nie został znaleziony: $planKey")

    private fun requirePurchasableAddOns(keys: List<AddOnKey>) = keys.map { key ->
        val addOn = addOnRepository.findByKey(key)
            ?: throw EntityNotFoundException("Moduł nie istnieje: $key")
        if (!addOn.isAvailable) throw ValidationException("Moduł '${addOn.name}' nie jest jeszcze dostępny.")
        if (addOn.monthlyPriceGrossCents == null) throw ValidationException("Moduł '${addOn.name}' nie ma ustalonej ceny.")
        addOn
    }
}

fun PaymentOrderEntity.toResponse(paymentUrl: String?) = CheckoutResponse(
    orderId = id,
    status = status,
    amountCents = amountCents,
    currency = currency,
    description = description,
    paymentUrl = paymentUrl
)

package pl.detailing.crm.payments.checkout

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.payments.order.*
import pl.detailing.crm.payments.p24.Przelewy24Client
import pl.detailing.crm.payments.p24.Przelewy24Properties
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.rolepreview.SimulatedEffectChannel
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.PaymentsUnavailableException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionConflictException
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.infrastructure.AddOnJpaRepository
import pl.detailing.crm.subscription.entitlement.infrastructure.PlanJpaRepository
import pl.detailing.crm.subscription.lifecycle.BillingDates
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import pl.detailing.crm.subscription.management.PlanManagementService
import pl.detailing.crm.subscription.pricing.MidPeriodPurchaseMode
import pl.detailing.crm.subscription.pricing.PricingService
import pl.detailing.crm.subscription.pricing.ProrationService
import java.time.Duration
import java.time.Instant
import java.util.UUID

// ─── API types ────────────────────────────────────────────────────────────────

data class CheckoutRequest(
    val type: PaymentOrderType,
    val planKey: PlanKey? = null,
    val addOnKeys: List<AddOnKey> = emptyList()
)

/**
 * [paymentUrl] — Przelewy24 payment page to redirect the buyer to; null when the
 *   order required no payment (or explicit mock mode) and was settled immediately —
 *   wtedy [status] mówi, jak się skończyło (FULFILLED albo REFUND_REQUIRED).
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
 * are settled immediately without a payment round-trip.
 *
 * Pricing rules (Product decision):
 *   - FULL already contains every module — orders combining FULL with add-ons are rejected.
 *   - À la carte modules are priced above their share of the FULL bundle, so
 *     self-assembled packages always cost more than FULL (BASIC 99 + all modules 256 = 355 vs FULL 299).
 *
 * Zmiany po audycie subskrypcji (docs/SUBSCRIPTION_AUDIT_2026-10.md):
 *  - INTENCJA PRZED P24: zamówienie jest zatwierdzone w bazie, zanim pójdzie żądanie do P24,
 *    a samo wywołanie HTTP biegnie poza transakcją (wcześniej w środku — połączenie z puli
 *    czekało na P24 bez timeoutu, P4). Notyfikacja zawsze znajdzie zamówienie.
 *  - JEDNO OTWARTE ZAMÓWIENIE NA PRODUKT: podwójne kliknięcie albo druga karta dostaje to
 *    samo zamówienie zamiast drugiego do opłacenia (P7). Rozstrzyga to blokada studia,
 *    a ostatnią linią obrony jest częściowy unikat `uq_payment_orders_one_open_per_product`.
 *  - FAIL-CLOSED: bez poświadczeń P24 i bez jawnego mocka checkout odpowiada 503 — dawniej
 *    takie zamówienia realizowały się za darmo (P3).
 *  - Upgrade i dokupienie modułu tylko w trialu albo w trwającym opłaconym okresie — dawniej
 *    po wygaśnięciu kosztowały 0 zł (S4).
 *  - Odnowienie kosztuje tyle, ile plan i moduły KOLEJNEGO okresu (S1).
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
    private val pricingService: PricingService,
    private val planManagementService: PlanManagementService,
    private val planRepository: PlanJpaRepository,
    private val addOnRepository: AddOnJpaRepository,
    private val accessPolicy: SubscriptionAccessPolicy,
    private val rolePreviewGuard: RolePreviewOutboundGuard,
    transactionManager: PlatformTransactionManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    private sealed interface OpenOrder {
        data class Reused(val order: PaymentOrderEntity) : OpenOrder
        data class Created(val order: PaymentOrderEntity) : OpenOrder
    }

    fun checkout(studioId: StudioId, buyerEmail: String, request: CheckoutRequest): CheckoutResponse {
        // Zakupy są tylko dla właściciela, którym pracownik piaskownicy nigdy nie jest - to
        // druga linia: podgląd roli nie zakłada zamówień i niczego nie płaci.
        rolePreviewGuard.requireOutsideSandbox(studioId.value, SimulatedEffectChannel.PAYMENT, "płatność za ${request.type.name}")

        val draft = when (request.type) {
            PaymentOrderType.INITIAL_PURCHASE -> prepareInitialPurchase(studioId, request)
            PaymentOrderType.RENEWAL -> prepareRenewal(studioId)
            PaymentOrderType.PLAN_UPGRADE -> preparePlanUpgrade(studioId, request)
            PaymentOrderType.ADD_ON_PURCHASE -> prepareAddOnPurchase(studioId, request)
        }

        val settlesWithoutGateway = draft.amountCents <= 0 || properties.mockMode
        if (!settlesWithoutGateway && !properties.isConfigured) {
            logger.error("Checkout {} studia {} odrzucony: Przelewy24 nieskonfigurowane, mock wyłączony", request.type, studioId)
            throw PaymentsUnavailableException()
        }

        val order = when (val open = openOrderFor(studioId, request.type, draft)) {
            is OpenOrder.Reused -> {
                logger.info("Checkout {} studia {}: zwracam otwarte zamówienie {}", request.type, studioId, open.order.id)
                return open.order.toResponse(paymentUrl = open.order.p24Token?.let(properties::paymentPageUrl))
            }
            is OpenOrder.Created -> open.order
        }

        if (settlesWithoutGateway) {
            tx.executeWithoutResult {
                orderRepository.lockById(order.id)!!.markPaid(p24OrderId = null, at = accessPolicy.now())
            }
            fulfillmentService.fulfillIfPaid(order.id)
            val settled = orderRepository.findById(order.id).orElseThrow()
            logger.info(
                "Order {} settled without P24 (amount={} mockMode={}) → {}",
                order.id, order.amountCents, properties.mockMode, settled.status
            )
            return settled.toResponse(paymentUrl = null)
        }

        // HTTP do P24 poza transakcją — zamówienie jest już zatwierdzone w bazie.
        val token = try {
            p24Client.registerTransaction(
                Przelewy24Client.RegisterTransactionCommand(
                    sessionId = order.sessionId,
                    amountCents = order.amountCents,
                    description = order.description,
                    email = buyerEmail,
                    urlReturn = "${properties.frontendBaseUrl}/payments/result?orderId=${order.id}",
                    urlStatus = "${properties.backendBaseUrl}/api/v1/payments/p24/status"
                )
            )
        } catch (e: Exception) {
            tx.executeWithoutResult { orderRepository.lockById(order.id)?.fail("Rejestracja w Przelewy24 nie powiodła się: ${e.message}") }
            logger.error("Rejestracja zamówienia {} w P24 nie powiodła się", order.id, e)
            throw PaymentsUnavailableException("Nie udało się połączyć z bramką płatności. Spróbuj ponownie za chwilę.")
        }

        val registered = tx.execute {
            orderRepository.lockById(order.id)!!.also { if (it.status == PaymentOrderStatus.PENDING) it.p24Token = token }
        }!!
        return registered.toResponse(paymentUrl = properties.paymentPageUrl(token))
    }

    fun getOrder(studioId: StudioId, orderId: UUID): PaymentOrderEntity =
        orderRepository.findByIdAndStudioId(orderId, studioId.value)
            ?: throw EntityNotFoundException("Zamówienie nie zostało znalezione: $orderId")

    // ─── One open order per product ───────────────────────────────────────────

    /**
     * Pod blokadą studia: zwraca otwarte zamówienie na ten sam produkt, jeśli wciąż da się je
     * opłacić (token P24 ważny, ta sama kwota), a starsze wygasza. Wygaszone (EXPIRED) nadal
     * przyjmuje spóźnioną płatność — kupujący, który jeszcze je opłaca, niczego nie traci.
     */
    private fun openOrderFor(studioId: StudioId, type: PaymentOrderType, draft: OrderDraft): OpenOrder = tx.execute {
        val studio = studioRepository.lockById(studioId.value)
            ?: throw EntityNotFoundException("Studio nie zostało znalezione: $studioId")
        revalidateUnderLock(studio, studioId, type, draft)
        val addOnKeysRaw = PaymentOrderEntity.encodeAddOnKeys(draft.addOnKeys)

        val awaitingActivation = orderRepository.findOpenForProduct(
            studioId.value, type, draft.planKey, addOnKeysRaw, listOf(PaymentOrderStatus.PAID)
        )
        if (awaitingActivation.isNotEmpty()) {
            throw ValidationException("Płatność za ten zakup została już przyjęta — trwa aktywacja. Odśwież stronę za chwilę.")
        }

        val now = accessPolicy.now()
        val reusableSince = now.minus(Duration.ofMinutes((properties.transactionTimeLimitMinutes - 1).coerceAtLeast(1).toLong()))
        val open = orderRepository.lockOpenForProduct(
            studioId.value, type, draft.planKey, addOnKeysRaw, listOf(PaymentOrderStatus.PENDING)
        )
        open.firstOrNull { it.p24Token != null && it.createdAt.isAfter(reusableSince) && samePrice(it, draft) }
            ?.let { return@execute OpenOrder.Reused(it) }
        // Zamówienie bez tokenu sprzed chwili = pierwsze kliknięcie właśnie rejestruje się w P24.
        // Wygaszenie go tutaj dałoby dwie żywe strony płatności na ten sam zakup (pierwsza
        // dostaje token już po wygaszeniu) — drugie kliknięcie ma poczekać, nie zastąpić pierwsze.
        if (open.any { it.p24Token == null && it.createdAt.isAfter(now.minus(REGISTRATION_IN_PROGRESS)) }) {
            throw SubscriptionConflictException(
                code = "CHECKOUT_IN_PROGRESS",
                message = "Płatność za ten zakup jest właśnie przygotowywana. Spróbuj ponownie za chwilę."
            )
        }
        open.forEach { it.expire("Zastąpione nowszym zamówieniem na ten sam zakup") }
        orderRepository.flush()

        OpenOrder.Created(
            orderRepository.save(
                PaymentOrderEntity(
                    studioId = studioId.value,
                    sessionId = "CRM-${UUID.randomUUID()}",
                    type = type,
                    planKey = draft.planKey,
                    addOnKeysRaw = addOnKeysRaw,
                    amountCents = draft.amountCents,
                    description = draft.description,
                    createdAt = now,
                    pricedUntil = draft.pricedUntil
                )
            )
        )
    }!!

    /**
     * Wykonalność sprawdzona drugi raz, pod blokadą studia i na stanie z bazy. Szkic zamówienia
     * powstaje wcześniej, z cache'u i bez blokady: podwójne kliknięcie „Aktywuj moduł" czekało
     * na blokadę trzymaną przez realizację pierwszego i zakładało drugie zamówienie na moduł,
     * który właśnie stał się aktywny — kończące się zwrotem (przegląd planu naprawczego).
     */
    private fun revalidateUnderLock(studio: StudioEntity, studioId: StudioId, type: PaymentOrderType, draft: OrderDraft) {
        when (type) {
            PaymentOrderType.INITIAL_PURCHASE ->
                if (studio.subscriptionStatus == SubscriptionStatus.ACTIVE || studio.subscriptionStatus == SubscriptionStatus.PAST_DUE) {
                    throw ValidationException("Studio ma już subskrypcję — użyj przedłużenia lub zmiany pakietu.")
                }
            PaymentOrderType.PLAN_UPGRADE -> {
                val currentPlan = requirePlan(entitlementService.readCurrent(studioId).planKey)
                if (requirePlan(draft.planKey).monthlyPriceGrossCents <= currentPlan.monthlyPriceGrossCents) {
                    throw ValidationException("Studio ma już pakiet ${currentPlan.name}.")
                }
            }
            PaymentOrderType.ADD_ON_PURCHASE -> {
                val current = entitlementService.readCurrent(studioId)
                if (current.planKey == PlanKey.FULL) throw ValidationException("Pakiet FULL zawiera już wszystkie moduły.")
                if (draft.addOnKeys.any { it in current.activeAddOnKeys }) throw ValidationException("Ten moduł jest już aktywny.")
            }
            // Skład odnowienia porównuje realizacja (OrderFulfillmentService.applyRenewal).
            PaymentOrderType.RENEWAL -> Unit
        }
    }

    /**
     * Ta sama cena — z tolerancją 1 grosza dla dopłaty proporcjonalnej za TEN SAM okres: proporcja
     * liczona co do sekundy zmienia się o grosz co kilka minut, a starsze zamówienie (dłuższe okno)
     * nigdy nie jest tańsze od nowego. Bez tolerancji ponowne kliknięcie trafiające w zmianę grosza
     * zakładałoby drugie zamówienie zamiast wskazać pierwsze.
     */
    private fun samePrice(open: PaymentOrderEntity, draft: OrderDraft): Boolean =
        open.amountCents == draft.amountCents ||
            (draft.pricedUntil != null && open.pricedUntil == draft.pricedUntil &&
                open.amountCents - draft.amountCents in 0..1)

    // ─── Order drafts ─────────────────────────────────────────────────────────

    private data class OrderDraft(
        val planKey: PlanKey,
        val addOnKeys: List<AddOnKey>,
        val amountCents: Long,
        val description: String,
        /** Koniec okresu, do którego policzono proporcję — tylko dopłaty w trakcie okresu. */
        val pricedUntil: Instant? = null
    )

    /** First purchase: full month of plan + selected modules. For NO_PLAN, TRIALING and EXPIRED studios. */
    private fun prepareInitialPurchase(studioId: StudioId, request: CheckoutRequest): OrderDraft {
        val planKey = request.planKey
            ?: throw ValidationException("Wybierz pakiet (BASIC lub FULL).")
        validatePlanAddOnCombination(planKey, request.addOnKeys)

        val studio = requireStudio(studioId)
        if (studio.subscriptionStatus == SubscriptionStatus.ACTIVE || studio.subscriptionStatus == SubscriptionStatus.PAST_DUE) {
            throw ValidationException("Studio ma już subskrypcję — użyj przedłużenia lub zmiany pakietu.")
        }

        val plan = requirePlan(planKey)
        val addOns = requirePurchasableAddOns(request.addOnKeys)
        val amount = plan.monthlyPriceGrossCents + addOns.sumOf { it.monthlyPriceGrossCents!! }

        val moduleNames = addOns.joinToString(", ") { it.name }
        return OrderDraft(
            planKey = planKey,
            addOnKeys = request.addOnKeys.sortedBy { it.name },
            amountCents = amount,
            description = "Pakiet ${plan.name} — 30 dni" +
                    if (addOns.isNotEmpty()) " + moduły: $moduleNames" else ""
        )
    }

    /** Renewal: 30 more days at the price of the NEXT period (plan po downgradzie, moduły bez wyłączenia). */
    private fun prepareRenewal(studioId: StudioId): OrderDraft {
        val studio = requireStudio(studioId)
        if (studio.subscriptionStatus == SubscriptionStatus.NO_PLAN) {
            throw ValidationException("Studio nie ma jeszcze pakietu — wybierz pakiet zamiast przedłużenia.")
        }

        val next = pricingService.nextPeriodPrice(studioId)
        return OrderDraft(
            planKey = next.planKey,
            addOnKeys = next.addOnKeys,
            amountCents = next.amountCents,
            description = "Przedłużenie subskrypcji (${next.planName}) — 30 dni"
        )
    }

    /** Mid-period upgrade to a more expensive plan, charged pro rata z zaliczeniem opłaconych modułów. */
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

        val proration = when (prorationService.midPeriodPurchaseMode(studioId)) {
            MidPeriodPurchaseMode.TRIAL_FREE -> null
            MidPeriodPurchaseMode.PRORATED -> prorationService.calculatePlanUpgrade(
                studioId, currentPlan.monthlyPriceGrossCents, newPlan.monthlyPriceGrossCents,
                planManagementService.paidAddOnCredits(entitlements)
            )!!
            MidPeriodPurchaseMode.NOT_ALLOWED -> throw ValidationException(PlanManagementService.NOT_ALLOWED_EXPLANATION)
        }

        return OrderDraft(
            planKey = newPlanKey,
            addOnKeys = emptyList(),
            amountCents = proration?.proratedAmountCents ?: 0L,
            description = "Zmiana pakietu na ${newPlan.name}" +
                (proration?.let { " — ${it.daysRemaining} dni (proporcjonalnie)" } ?: " (okres próbny)"),
            pricedUntil = proration?.periodEndsAt
        )
    }

    /** Mid-period purchase of a single module, charged pro rata. Free during trial. */
    private fun prepareAddOnPurchase(studioId: StudioId, request: CheckoutRequest): OrderDraft {
        val addOnKey = request.addOnKeys.singleOrNull()
            ?: throw ValidationException("Wybierz dokładnie jeden moduł do dokupienia.")

        // Feasibility BEFORE payment: an add-on needs a plan row to attach to.
        // Failing here costs the user a click; failing in fulfillment costs them money.
        if (!entitlementService.hasPlanAssigned(studioId)) {
            throw ValidationException(
                "Studio nie ma przypisanego pakietu — wybierz pakiet (BASIC lub FULL) przed dokupieniem modułu."
            )
        }

        val entitlements = entitlementService.getEntitlements(studioId)
        if (entitlements.planKey == PlanKey.FULL) {
            throw ValidationException("Pakiet FULL zawiera już wszystkie moduły.")
        }
        entitlements.addOnCancellations[addOnKey]?.let { cancelAt ->
            val studio = requireStudio(studioId)
            throw ValidationException(
                if (planManagementService.isAddOnResumable(studio, cancelAt))
                    "Ten moduł działa do ${BillingDates.format(cancelAt)} i jest opłacony do końca okresu — przywróć go zamiast kupować ponownie."
                else
                    "Ten moduł działa do ${BillingDates.format(cancelAt)}, a kolejny okres opłacono już bez niego — od tego dnia możesz go dokupić na resztę okresu."
            )
        }
        if (addOnKey in entitlements.activeAddOnKeys) {
            throw ValidationException("Ten moduł jest już aktywny.")
        }

        val addOn = requirePurchasableAddOns(listOf(addOnKey)).single()
        val proration = when (prorationService.midPeriodPurchaseMode(studioId)) {
            MidPeriodPurchaseMode.TRIAL_FREE -> null
            MidPeriodPurchaseMode.PRORATED -> prorationService.calculateAddOnActivation(studioId, addOn.monthlyPriceGrossCents!!)!!
            MidPeriodPurchaseMode.NOT_ALLOWED -> throw ValidationException(PlanManagementService.NOT_ALLOWED_EXPLANATION)
        }

        return OrderDraft(
            planKey = entitlements.planKey,
            addOnKeys = listOf(addOnKey),
            amountCents = proration?.proratedAmountCents ?: 0L,
            description = "Moduł ${addOn.name}" +
                (proration?.let { " — ${it.daysRemaining} dni (proporcjonalnie)" } ?: " (okres próbny)"),
            pricedUntil = proration?.periodEndsAt
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

    companion object {
        /** Ile czeka drugie kliknięcie, zanim uzna rejestrację pierwszego za porzuconą. */
        private val REGISTRATION_IN_PROGRESS: Duration = Duration.ofMinutes(1)
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

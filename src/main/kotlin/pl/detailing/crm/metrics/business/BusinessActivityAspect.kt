package pl.detailing.crm.metrics.business

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.metrics.domain.ActorKind
import pl.detailing.crm.metrics.domain.MetricEventType
import pl.detailing.crm.metrics.ingest.MetricEventRecorder
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import java.util.UUID

/**
 * Records resource consumption that leaves no durable trace of its own.
 *
 * ## Two kinds of metric, two mechanisms — and why
 *
 * **Things that already have a table** (reservations, visits, customers, invoices) are
 * counted by the nightly roll-up straight from `appointments` / `visits` / `audit_logs`.
 * That is not laziness — it is strictly better:
 *
 * - it is *exact*, because the source table is the same row the business itself trusts;
 * - it is *backfillable*, so the console shows twelve months of history on the day the
 *   module ships instead of starting from zero;
 * - it cannot drift, whereas a parallel event counter and the appointments table will
 *   eventually disagree, and then nobody knows which number to quote to a customer.
 *
 * **Things that leave no trace** — an SMS handed to the provider, an e-mail dispatched —
 * have no row to count later. Those are captured here, at the moment they happen.
 *
 * ## Why the gateway is the only instrumentation point
 *
 * `OutboundCommunicationGateway` is the mandatory path for every outbound message in the
 * system, which makes these counters exhaustive by construction. Instrumenting call sites
 * instead would guarantee a missed one within two features — and the first sign of that
 * is an SMS invoice that does not reconcile with what the platform believes it sent.
 *
 * ## Ordering
 *
 * Every advice records **after** the underlying call returns. A send that threw is counted
 * as a failure, never as a success: these counters can under-count on a crash, never
 * over-count. Under-counting is recoverable; over-counting silently corrupts every
 * derived figure and every conversation based on it.
 *
 * Note on `suspend` handlers: this aspect deliberately targets only plain (non-suspending)
 * methods. Spring AOP around-advice on a Kotlin `suspend` function sees `COROUTINE_SUSPENDED`
 * rather than the eventual result, so an advice written the obvious way would record
 * "success" before the coroutine had finished — including for the ones that go on to throw.
 * That is exactly the over-counting the paragraph above rules out, which is the second
 * reason reservations are counted from their table instead.
 */
@Aspect
@Component
class BusinessActivityAspect(
    private val recorder: MetricEventRecorder
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── SMS: per tenant and, by summation, platform-wide ─────────────────────

    @Pointcut("execution(* pl.detailing.crm.communication.OutboundCommunicationGateway.sendSms(..))")
    fun marketingSms() {}

    @Pointcut("execution(* pl.detailing.crm.communication.OutboundCommunicationGateway.sendTransactionalSms(..))")
    fun transactionalSms() {}

    /** `sendSms(customerId, studioId, phoneNumber, message, …)` — studio is arg 1, body arg 3. */
    @Around("marketingSms()")
    fun recordMarketingSms(pjp: ProceedingJoinPoint): Any? =
        recordSms(pjp, studioArgIndex = 1, messageArgIndex = 3, category = "MARKETING")

    /** `sendTransactionalSms(studioId, phoneNumber, message, …)` — studio is arg 0, body arg 2. */
    @Around("transactionalSms()")
    fun recordTransactionalSms(pjp: ProceedingJoinPoint): Any? =
        recordSms(pjp, studioArgIndex = 0, messageArgIndex = 2, category = "TRANSACTIONAL")

    private fun recordSms(
        pjp: ProceedingJoinPoint,
        studioArgIndex: Int,
        messageArgIndex: Int,
        category: String
    ): Any? {
        val studioId = (pjp.args.getOrNull(studioArgIndex) as? UUID)?.let(::StudioId)
        val segments = smsSegments(pjp.args.getOrNull(messageArgIndex) as? String)

        return try {
            val result = pjp.proceed()
            runSafely("SMS") {
                val delivered = (result as? SmsDeliveryResult)?.success == true
                recorder.record(
                    eventType = if (delivered) MetricEventType.SMS_SENT else MetricEventType.SMS_FAILED,
                    studioId = studioId,
                    userId = currentPrincipal()?.userId,
                    actorKind = currentActorKind(),
                    quantity = segments,
                    payload = mapOf("category" to category)
                )
            }
            result
        } catch (ex: Throwable) {
            runSafely("SMS") {
                recorder.record(
                    eventType = MetricEventType.SMS_FAILED,
                    studioId = studioId,
                    quantity = segments,
                    payload = mapOf("category" to category, "reason" to ex.javaClass.simpleName)
                )
            }
            throw ex
        }
    }

    // ── E-mail ───────────────────────────────────────────────────────────────

    @Pointcut("execution(* pl.detailing.crm.communication.OutboundCommunicationGateway.sendEmail(..))")
    fun emailSend() {}

    /** `sendEmail(customerId, studioId, to, subject, …)` — studio is arg 1. */
    @Around("emailSend()")
    fun recordEmail(pjp: ProceedingJoinPoint): Any? {
        val studioId = (pjp.args.getOrNull(1) as? UUID)?.let(::StudioId)

        return try {
            val result = pjp.proceed()
            runSafely("e-mail") {
                val delivered = (result as? EmailDeliveryResult)?.success == true
                recorder.record(
                    eventType = if (delivered) MetricEventType.EMAIL_SENT else MetricEventType.EMAIL_FAILED,
                    studioId = studioId,
                    userId = currentPrincipal()?.userId,
                    actorKind = currentActorKind()
                )
            }
            result
        } catch (ex: Throwable) {
            runSafely("e-mail") {
                recorder.record(
                    eventType = MetricEventType.EMAIL_FAILED,
                    studioId = studioId,
                    payload = mapOf("reason" to ex.javaClass.simpleName)
                )
            }
            throw ex
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * GSM-7 segmentation: 160 characters for a single message, 153 per part once it splits
     * (the concatenation header costs seven). Messages containing non-GSM characters are
     * really UCS-2 (70 / 67), which this deliberately does not model — the provider's
     * invoice is the source of truth for billing. This number exists to answer "is this
     * studio about to run out of credits", and for that purpose an approximation that errs
     * on the low side is fine and clearly labelled as such.
     */
    internal fun smsSegments(message: String?): Long {
        val length = message?.length ?: return 1
        return when {
            length <= 160 -> 1
            else -> ((length + 152) / 153).toLong()
        }
    }

    private fun currentPrincipal(): UserPrincipal? =
        SecurityContextHolder.getContext().authentication as? UserPrincipal

    private fun currentActorKind(): ActorKind? =
        currentPrincipal()?.let { if (it.isOwner) ActorKind.OWNER else ActorKind.EMPLOYEE }

    private inline fun runSafely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (ex: Exception) {
            log.debug("Nie udało się zarejestrować metryki ({}): {}", what, ex.message)
        }
    }
}

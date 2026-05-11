package pl.detailing.crm.observability

import io.micrometer.core.instrument.MeterRegistry
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Pointcut
import org.springframework.stereotype.Component
import pl.detailing.crm.email.provider.EmailDeliveryResult
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.smscampaigns.provider.SmsDeliveryResult
import java.util.UUID

/**
 * AOP aspect that instruments every outbound communication dispatched through
 * [OutboundCommunicationGateway] — the single mandatory gateway for all emails
 * and SMS in the system.
 *
 * Because ALL sends must go through the gateway, these counters are exhaustive.
 * No other instrumentation point is needed for communication volume metrics.
 *
 * Recorded metrics
 * ─────────────────
 * crm_communication_emails_sent_total  – Counter
 *   Tags: studio_id, result (sent | failed | no_credits)
 *
 * crm_communication_sms_sent_total     – Counter
 *   Tags: studio_id, result (sent | failed | no_credits)
 *
 * Result values
 * ─────────────
 * • sent       – provider accepted the message
 * • failed     – provider rejected, or consent check blocked the send
 * • no_credits – studio has insufficient SMS credits (InsufficientSmsCreditsException)
 */
@Aspect
@Component
class CommunicationMetricsAspect(private val registry: MeterRegistry) {

    // ── Pointcuts ────────────────────────────────────────────────────────────

    /** Marketing SMS: sendSms(customerId, studioId, phoneNumber, message, context) */
    @Pointcut("execution(* pl.detailing.crm.communication.OutboundCommunicationGateway.sendSms(..))")
    fun marketingSmsSend() {}

    /** Transactional SMS (no consent gate): sendTransactionalSms(studioId, phoneNumber, message) */
    @Pointcut("execution(* pl.detailing.crm.communication.OutboundCommunicationGateway.sendTransactionalSms(..))")
    fun transactionalSmsSend() {}

    /** Email: sendEmail(customerId, studioId, to, subject, bodyText, attachments, context) */
    @Pointcut("execution(* pl.detailing.crm.communication.OutboundCommunicationGateway.sendEmail(..))")
    fun emailSend() {}

    // ── Advice ───────────────────────────────────────────────────────────────

    @Around("marketingSmsSend()")
    fun recordMarketingSms(pjp: ProceedingJoinPoint): Any? {
        // args: (customerId: UUID, studioId: UUID, phoneNumber, message, context)
        val studioId = (pjp.args.getOrNull(1) as? UUID)?.toString() ?: MetricsTags.TAG_VALUE_UNKNOWN
        return recordSms(pjp, studioId)
    }

    @Around("transactionalSmsSend()")
    fun recordTransactionalSms(pjp: ProceedingJoinPoint): Any? {
        // args: (studioId: UUID, phoneNumber, message)
        val studioId = (pjp.args.getOrNull(0) as? UUID)?.toString() ?: MetricsTags.TAG_VALUE_UNKNOWN
        return recordSms(pjp, studioId)
    }

    @Around("emailSend()")
    fun recordEmail(pjp: ProceedingJoinPoint): Any? {
        // args: (customerId: UUID, studioId: UUID, to, subject, bodyText, attachments, context)
        val studioId = (pjp.args.getOrNull(1) as? UUID)?.toString() ?: MetricsTags.TAG_VALUE_UNKNOWN

        return try {
            val rawResult = pjp.proceed()
            val result = rawResult as? EmailDeliveryResult
            val outcome = if (result?.success == true) RESULT_SENT else RESULT_FAILED
            emailCounter(studioId, outcome)
            rawResult
        } catch (ex: Throwable) {
            emailCounter(studioId, RESULT_FAILED)
            throw ex
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun recordSms(pjp: ProceedingJoinPoint, studioId: String): Any? =
        try {
            val rawResult = pjp.proceed()
            val result = rawResult as? SmsDeliveryResult
            val outcome = if (result?.success == true) RESULT_SENT else RESULT_FAILED
            smsCounter(studioId, outcome)
            rawResult
        } catch (ex: InsufficientSmsCreditsException) {
            smsCounter(studioId, RESULT_NO_CREDITS)
            throw ex
        } catch (ex: Throwable) {
            smsCounter(studioId, RESULT_FAILED)
            throw ex
        }

    private fun smsCounter(studioId: String, result: String) =
        registry.counter(
            MetricsTags.COMM_SMS_SENT,
            MetricsTags.TAG_STUDIO_ID, studioId,
            MetricsTags.TAG_RESULT, result
        ).increment()

    private fun emailCounter(studioId: String, result: String) =
        registry.counter(
            MetricsTags.COMM_EMAIL_SENT,
            MetricsTags.TAG_STUDIO_ID, studioId,
            MetricsTags.TAG_RESULT, result
        ).increment()

    companion object {
        private const val RESULT_SENT = "sent"
        private const val RESULT_FAILED = "failed"
        private const val RESULT_NO_CREDITS = "no_credits"
    }
}

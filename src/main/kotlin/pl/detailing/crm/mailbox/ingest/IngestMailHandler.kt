package pl.detailing.crm.mailbox.ingest

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.inbound.email.domain.EmailClassificationResult
import pl.detailing.crm.inbound.email.domain.EmailLeadClassifier
import pl.detailing.crm.leads.comments.AddLeadCommentCommand
import pl.detailing.crm.leads.comments.LeadCommentHandler
import pl.detailing.crm.leads.create.CreateLeadCommand
import pl.detailing.crm.leads.create.CreateLeadHandler
import pl.detailing.crm.leads.estimation.analyze.AnalyzeLeadCommand
import pl.detailing.crm.leads.estimation.analyze.AnalyzeLeadHandler
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.mailbox.domain.EmailCleaningService
import pl.detailing.crm.mailbox.domain.MailDirection
import pl.detailing.crm.mailbox.domain.MailIngestPort
import pl.detailing.crm.mailbox.domain.MailSendStatus
import pl.detailing.crm.mailbox.domain.MailThreadClassification
import pl.detailing.crm.mailbox.domain.RawIncomingEmail
import pl.detailing.crm.mailbox.domain.ThreadingService
import pl.detailing.crm.mailbox.infrastructure.MailMessageEntity
import pl.detailing.crm.mailbox.infrastructure.MailMessageRepository
import pl.detailing.crm.mailbox.infrastructure.MailSenderRuleRepository
import pl.detailing.crm.mailbox.infrastructure.MailThreadRepository
import pl.detailing.crm.shared.LeadClientRepliedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Single entry point for every mail source (IMAP sync, Cloudflare webhook, future OAuth
 * providers). Each message is stored as part of a thread first, and only then does the
 * classification funnel decide whether the thread deserves a lead.
 *
 * The funnel exists to keep LLM cost proportional to value:
 *  - F0 rejects bulk/auto-generated mail and known senders with plain header rules,
 *  - F1 lets an already-classified thread inherit its verdict, so a five-message
 *    conversation is classified once rather than five times,
 *  - F2 calls the existing [EmailLeadClassifier] only for what survives.
 */
@Service
class IngestMailHandler(
    private val threadingService: ThreadingService,
    private val cleaningService: EmailCleaningService,
    private val classifier: EmailLeadClassifier,
    private val threadRepository: MailThreadRepository,
    private val messageRepository: MailMessageRepository,
    private val senderRuleRepository: MailSenderRuleRepository,
    private val leadRepository: LeadRepository,
    private val createLeadHandler: CreateLeadHandler,
    private val analyzeLeadHandler: AnalyzeLeadHandler,
    private val leadCommentHandler: LeadCommentHandler,
    private val eventPublisher: ApplicationEventPublisher,
    private val auditService: AuditService
) : MailIngestPort {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun ingest(studioId: UUID, mailAccountId: UUID?, email: RawIncomingEmail) {
        handle(studioId, mailAccountId, email)
    }

    @Transactional
    suspend fun handle(studioId: UUID, mailAccountId: UUID?, email: RawIncomingEmail): MailIngestResult {
        val messageId = ThreadingService.normalizeMessageId(email.messageId)
            ?: ThreadingService.syntheticMessageId(email.fromEmail, email.sentAt, email.subject)

        messageRepository.findByStudioIdAndMessageId(studioId, messageId)?.let { existing ->
            // Already stored — typically our own reply coming back from the Sent folder.
            if (existing.providerUid == null && email.providerUid != null) {
                existing.providerUid = email.providerUid
                messageRepository.save(existing)
            }
            return MailIngestResult.Duplicate
        }

        val participant = if (email.direction == MailDirection.INBOUND) {
            email.fromEmail.lowercase()
        } else {
            (email.toEmails.firstOrNull() ?: email.fromEmail).lowercase()
        }

        val thread = threadingService.resolveThread(
            studioId = studioId,
            mailAccountId = mailAccountId,
            inReplyTo = ThreadingService.normalizeMessageId(email.inReplyTo),
            references = email.references,
            externalThreadKey = email.externalThreadKey,
            participantEmail = participant,
            subject = email.subject,
            sentAt = email.sentAt
        )

        val bodyClean = cleaningService.clean(email.bodyHtml, email.bodyText)

        messageRepository.save(
            MailMessageEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                threadId = thread.id,
                mailAccountId = mailAccountId,
                direction = email.direction,
                messageId = messageId,
                inReplyTo = ThreadingService.normalizeMessageId(email.inReplyTo),
                referencesIds = email.references.joinToString(" ").ifBlank { null },
                fromEmail = email.fromEmail.lowercase(),
                fromName = email.fromName?.take(255),
                toEmails = email.toEmails.joinToString(",").ifBlank { null },
                subject = email.subject?.take(1000),
                sentAt = email.sentAt,
                bodyHtml = email.bodyHtml,
                bodyTextClean = bodyClean,
                hasAttachments = email.hasAttachments,
                providerUid = email.providerUid,
                sendStatus = if (email.direction == MailDirection.OUTBOUND) MailSendStatus.SENT else MailSendStatus.RECEIVED
            )
        )
        threadingService.touch(thread, email.sentAt, email.direction)

        // Our own outgoing mail never creates or advances a lead — it only belongs to the thread.
        if (email.direction == MailDirection.OUTBOUND) return MailIngestResult.Stored

        val existingLeadId = thread.leadId
        if (existingLeadId != null) {
            appendReplyToLead(studioId, existingLeadId, email, bodyClean)
            return MailIngestResult.ReplyAppended(existingLeadId)
        }

        val verdict = classifyThread(studioId, thread.classification, email, bodyClean)
        thread.classification = verdict.classification
        threadRepository.save(thread)

        if (verdict.classification != MailThreadClassification.INQUIRY) {
            log.debug(
                "[MAILBOX] Thread {} classified as {} for studio {}, no lead created",
                thread.id, verdict.classification, studioId
            )
            return MailIngestResult.Ignored(verdict.classification.name)
        }

        val leadId = createLead(studioId, email, bodyClean, verdict.detected)
        thread.leadId = leadId.value
        threadRepository.save(thread)
        return MailIngestResult.LeadCreated(leadId.value)
    }

    /**
     * Verdict of the funnel. The extracted details ride along so the caller can seed the
     * background estimation without asking the model a second time.
     */
    private data class ClassificationVerdict(
        val classification: MailThreadClassification,
        val detected: EmailClassificationResult.LeadDetected? = null
    )

    private suspend fun classifyThread(
        studioId: UUID,
        current: MailThreadClassification,
        email: RawIncomingEmail,
        bodyClean: String
    ): ClassificationVerdict {
        // F1 — an already-judged thread keeps its verdict; follow-ups cost nothing.
        if (current != MailThreadClassification.PENDING) return ClassificationVerdict(current)

        // F0 — header rules and learned sender rules, no model involved.
        if (email.headers.containsKey("list-unsubscribe") || email.headers.containsKey("list-id")) {
            return ClassificationVerdict(MailThreadClassification.OTHER)
        }
        email.headers["auto-submitted"]?.let { value ->
            if (!value.equals("no", ignoreCase = true)) return ClassificationVerdict(MailThreadClassification.OTHER)
        }
        email.headers["precedence"]?.let { value ->
            if (value.lowercase() in BULK_PRECEDENCE) return ClassificationVerdict(MailThreadClassification.OTHER)
        }
        val sender = email.fromEmail.lowercase()
        val domain = sender.substringAfterLast('@', "")
        senderRuleRepository.findByStudioIdAndPatternIn(studioId, listOfNotNull(sender, domain.ifBlank { null }))
            .firstOrNull()
            ?.let { return ClassificationVerdict(it.classification) }

        // F2 — the existing OpenAI classifier, on denoised text only.
        return when (val result = classifier.classify(email.fromEmail, email.subject, bodyClean)) {
            is EmailClassificationResult.LeadDetected ->
                ClassificationVerdict(MailThreadClassification.INQUIRY, result)

            else -> ClassificationVerdict(MailThreadClassification.OTHER)
        }
    }

    private suspend fun createLead(
        studioId: UUID,
        email: RawIncomingEmail,
        bodyClean: String,
        detected: EmailClassificationResult.LeadDetected?
    ): LeadId {
        val created = createLeadHandler.handle(
            CreateLeadCommand(
                studioId = StudioId(studioId),
                source = LeadSource.EMAIL,
                contactIdentifier = email.fromEmail.lowercase(),
                customerName = detected?.extractedName ?: email.fromName,
                initialMessage = bodyClean.ifBlank { email.bodyText.orEmpty() },
                estimatedValue = 0L
            )
        )

        // The webhook and the IMAP poll must not wait for the estimation model.
        val leadId = created.leadId
        val needs = detected?.requestedServices ?: emptyList()
        val make = detected?.vehicleMake
        val model = detected?.vehicleModel
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                analyzeLeadHandler.handle(
                    AnalyzeLeadCommand(
                        leadId = leadId,
                        studioId = StudioId(studioId),
                        preExtractedNeeds = needs,
                        preExtractedVehicleMake = make,
                        preExtractedVehicleModel = model
                    )
                )
            } catch (e: Exception) {
                log.error("[MAILBOX] Background analysis failed for leadId={}: {}", leadId, e.message)
            }
        }

        log.info("[MAILBOX] Lead created from mail thread: leadId={}, studioId={}", leadId, studioId)
        return leadId
    }

    /**
     * A reply on a thread that already has a lead is attached as a comment, matching what the
     * sales view already renders, and re-raises the lead's activity flag.
     */
    private suspend fun appendReplyToLead(
        studioId: UUID,
        leadIdValue: UUID,
        email: RawIncomingEmail,
        bodyClean: String
    ) {
        val lead = leadRepository.findById(leadIdValue).orElse(null)
        if (lead == null) {
            log.warn("[MAILBOX] Thread points at missing lead {} for studio {}", leadIdValue, studioId)
            return
        }
        val leadId = LeadId(leadIdValue)
        val subjectLine = email.subject?.takeIf { it.isNotBlank() }?.let { "Temat: $it\n\n" }.orEmpty()

        leadCommentHandler.addComment(
            AddLeadCommentCommand(
                leadId = leadId,
                studioId = StudioId(studioId),
                userId = SYSTEM_USER_ID,
                userName = SYSTEM_USER_NAME,
                content = "Klient odpowiedział na maila. Treść:\n\n$subjectLine${bodyClean.trim()}"
                    .take(LEAD_COMMENT_MAX_LENGTH),
                skipAuditEntry = true
            )
        )

        val activityAt = Instant.now()
        lead.newActivityAt = activityAt
        leadRepository.save(lead)

        eventPublisher.publishEvent(
            LeadClientRepliedEvent(
                source = this,
                studioId = StudioId(studioId),
                leadId = leadId,
                customerName = lead.customerName ?: lead.contactIdentifier,
                activityAt = activityAt
            )
        )
        auditService.logSync(
            LogAuditCommand(
                studioId = StudioId(studioId),
                userId = SYSTEM_USER_ID,
                userDisplayName = SYSTEM_USER_NAME,
                module = AuditModule.LEAD,
                entityId = leadIdValue.toString(),
                entityDisplayName = lead.customerName ?: lead.contactIdentifier,
                action = AuditAction.LEAD_CUSTOMER_ANSWERED
            )
        )
        log.info("[MAILBOX] Reply appended to lead {} for studio {}", leadIdValue, studioId)
    }

    companion object {
        private val SYSTEM_USER_ID = UserId(UUID(0, 0))
        private const val SYSTEM_USER_NAME = "System"
        private val BULK_PRECEDENCE = setOf("bulk", "junk", "list")

        /** lead_comments enforces a 5000-character limit; denoised bodies stay well inside it. */
        private const val LEAD_COMMENT_MAX_LENGTH = 5000
    }
}

sealed class MailIngestResult {
    data class LeadCreated(val leadId: UUID) : MailIngestResult()
    data class ReplyAppended(val leadId: UUID) : MailIngestResult()
    data class Ignored(val reason: String) : MailIngestResult()
    data object Stored : MailIngestResult()
    data object Duplicate : MailIngestResult()
}

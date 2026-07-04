package pl.detailing.crm.inbound.email.application

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
import pl.detailing.crm.audit.domain.FieldChange
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
import pl.detailing.crm.shared.LeadClientRepliedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class ProcessInboundEmailHandler(
    private val studioRepository: StudioRepository,
    private val emailLeadClassifier: EmailLeadClassifier,
    private val createLeadHandler: CreateLeadHandler,
    private val analyzeLeadHandler: AnalyzeLeadHandler,
    private val leadRepository: LeadRepository,
    private val leadCommentHandler: LeadCommentHandler,
    private val eventPublisher: ApplicationEventPublisher,
    private val auditService: AuditService,
) {
    companion object {
        // Window for dedup: reply from the same address within 60 days may reuse an open lead
        private const val DEDUP_DAYS = 60L
        // Only OPEN leads accept follow-up emails as comments. A closed lead (COMPLETED/LOST/
        // NO_SHOW) means the previous matter is done, so a new email starts a fresh lead — the
        // same client can be interested in several independent services over time.
        private val OPEN_STATUSES = listOf(
            LeadStatus.NEW.name,
            LeadStatus.IN_PROGRESS.name,
            LeadStatus.CONFIRMED.name
        )
        private val SYSTEM_USER_ID = UserId(UUID(0, 0))
        private const val SYSTEM_USER_NAME = "System"
    }
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: ProcessInboundEmailCommand): ProcessInboundEmailResult {
        val studioEntity = studioRepository.findByEmailAlias(command.alias)
        if (studioEntity == null) {
            log.warn("[INBOUND_EMAIL] Received email for unknown alias='{}', from='{}'", command.alias, command.from)
            return ProcessInboundEmailResult.Ignored("Unknown alias")
        }

        val studioId = StudioId(studioEntity.id)

        log.debug("[INBOUND_EMAIL] Classifying email for studioId={}, from='{}'", studioId, command.from)
        log.debug(
            "[INBOUND_EMAIL] Content: subject='{}' body='{}'",
            command.subject,
            command.body.take(500).replace("\n", " ↵ ")
        )

        val classification = emailLeadClassifier.classify(
            from = command.from,
            subject = command.subject,
            body = command.body
        )

        if (classification !is EmailClassificationResult.LeadDetected) {
            log.debug("[INBOUND_EMAIL] Email not classified as lead for studioId={}, from='{}'", studioId, command.from)
            return ProcessInboundEmailResult.Ignored("Not a lead inquiry")
        }

        // Dedup: if an OPEN lead from this address exists within the window, add a reply comment
        // instead. Closed leads are ignored so a new inquiry becomes its own lead.
        val since = Instant.now().minus(DEDUP_DAYS, ChronoUnit.DAYS)
        val existingLead = leadRepository.findLatestOpenByContactIdentifier(
            studioId = studioId.value,
            contactIdentifier = command.from,
            statuses = OPEN_STATUSES,
            since = since
        )

        if (existingLead != null) {
            val leadId = LeadId(existingLead.id)
            val commentContent = "Klient odpowiedział na maila. Treść:\n\n${command.body.trim()}"

            leadCommentHandler.addComment(AddLeadCommentCommand(
                leadId = leadId,
                studioId = studioId,
                userId = SYSTEM_USER_ID,
                userName = SYSTEM_USER_NAME,
                content = commentContent
            ))

            val activityAt = Instant.now()
            existingLead.newActivityAt = activityAt
            leadRepository.save(existingLead)

            eventPublisher.publishEvent(
                LeadClientRepliedEvent(
                    source = this,
                    studioId = studioId,
                    leadId = leadId,
                    customerName = existingLead.customerName ?: existingLead.contactIdentifier,
                    activityAt = activityAt
                )
            )
            auditService.logSync(LogAuditCommand(
                studioId = studioId,
                userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000000")),
                userDisplayName = "System",
                module = AuditModule.LEAD,
                entityId = existingLead.id.toString(),
                entityDisplayName = existingLead.customerName ?: existingLead.contactIdentifier,
                action = AuditAction.LEAD_CUSTOMER_ANSWERED,
            ))

            log.info(
                "[INBOUND_EMAIL] Email reply appended as comment to existing lead: leadId={}, studioId={}, from='{}'",
                leadId, studioId, command.from
            )
            return ProcessInboundEmailResult.ReplyAppended(leadId = leadId.toString())
        }

        val leadCommand = CreateLeadCommand(
            studioId = studioId,
            source = LeadSource.EMAIL,
            contactIdentifier = command.from,
            customerName = classification.extractedName,
            initialMessage = buildLeadMessage(command, classification),
            estimatedValue = 0L
        )

        val created = createLeadHandler.handle(leadCommand)

        log.info(
            "[INBOUND_EMAIL] Lead created: leadId={}, studioId={}, from='{}'",
            created.leadId, studioId, command.from
        )

        // Trigger AI estimation in background — email webhook must not wait for LLM calls
        val capturedLeadId = created.leadId
        val capturedStudioId = studioId
        val capturedServices = classification.requestedServices
        val capturedVehicleMake = classification.vehicleMake
        val capturedVehicleModel = classification.vehicleModel

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                analyzeLeadHandler.handle(
                    AnalyzeLeadCommand(
                        leadId = capturedLeadId,
                        studioId = capturedStudioId,
                        preExtractedNeeds = capturedServices,
                        preExtractedVehicleMake = capturedVehicleMake,
                        preExtractedVehicleModel = capturedVehicleModel
                    )
                )
            } catch (e: Exception) {
                log.error("[LEAD_ANALYSIS] Background analysis failed for leadId={}: {}", capturedLeadId, e.message)
            }
        }

        return ProcessInboundEmailResult.LeadCreated(leadId = created.leadId.toString())
    }

    private fun buildLeadMessage(
        command: ProcessInboundEmailCommand,
        classification: EmailClassificationResult.LeadDetected
    ): String = buildString {
        // Original body is the primary content — never paraphrase what the client wrote.
        // The sales team must see the exact message to avoid losing details.
        appendLine(command.body.trim())

        // Append structured metadata block extracted by LLM as quick reference for sales team.
        val vehicleParts = listOfNotNull(
            classification.vehicleMake,
            classification.vehicleModel,
            classification.vehicleYear?.toString()
        )
        val hasMetadata = vehicleParts.isNotEmpty() || classification.requestedServices.isNotEmpty()
    }.trimEnd()
}

sealed class ProcessInboundEmailResult {
    data class LeadCreated(val leadId: String) : ProcessInboundEmailResult()
    data class ReplyAppended(val leadId: String) : ProcessInboundEmailResult()
    data class Ignored(val reason: String) : ProcessInboundEmailResult()
}

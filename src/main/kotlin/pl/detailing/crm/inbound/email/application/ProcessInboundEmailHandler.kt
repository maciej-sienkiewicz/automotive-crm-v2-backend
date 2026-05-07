package pl.detailing.crm.inbound.email.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.inbound.email.domain.EmailClassificationResult
import pl.detailing.crm.inbound.email.domain.EmailLeadClassifier
import pl.detailing.crm.leads.create.CreateLeadCommand
import pl.detailing.crm.leads.create.CreateLeadHandler
import pl.detailing.crm.leads.estimation.analyze.AnalyzeLeadCommand
import pl.detailing.crm.leads.estimation.analyze.AnalyzeLeadHandler
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository

@Service
class ProcessInboundEmailHandler(
    private val studioRepository: StudioRepository,
    private val emailLeadClassifier: EmailLeadClassifier,
    private val createLeadHandler: CreateLeadHandler,
    private val analyzeLeadHandler: AnalyzeLeadHandler
) {
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

        val classification = emailLeadClassifier.classify(
            from = command.from,
            subject = command.subject,
            body = command.body
        )

        if (classification !is EmailClassificationResult.LeadDetected) {
            log.debug("[INBOUND_EMAIL] Email not classified as lead for studioId={}, from='{}'", studioId, command.from)
            return ProcessInboundEmailResult.Ignored("Not a lead inquiry")
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
        if (hasMetadata) {
            appendLine()
            appendLine("---")
            if (vehicleParts.isNotEmpty()) {
                appendLine("Pojazd: ${vehicleParts.joinToString(" ")}")
            }
            if (classification.requestedServices.isNotEmpty()) {
                appendLine("Usługi: ${classification.requestedServices.joinToString(", ")}")
            }
        }
    }.trimEnd()
}

sealed class ProcessInboundEmailResult {
    data class LeadCreated(val leadId: String) : ProcessInboundEmailResult()
    data class Ignored(val reason: String) : ProcessInboundEmailResult()
}

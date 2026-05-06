package pl.detailing.crm.inbound.email.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.inbound.email.domain.EmailClassificationResult
import pl.detailing.crm.inbound.email.domain.EmailLeadClassifier
import pl.detailing.crm.leads.create.CreateLeadCommand
import pl.detailing.crm.leads.create.CreateLeadHandler
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository

@Service
class ProcessInboundEmailHandler(
    private val studioRepository: StudioRepository,
    private val emailLeadClassifier: EmailLeadClassifier,
    private val createLeadHandler: CreateLeadHandler
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

        return ProcessInboundEmailResult.LeadCreated(leadId = created.leadId.toString())
    }

    private fun buildLeadMessage(command: ProcessInboundEmailCommand, classification: EmailClassificationResult.LeadDetected): String {
        val subjectPart = command.subject?.let { "Temat: $it\n" } ?: ""
        return "${subjectPart}${classification.summary}"
    }
}

sealed class ProcessInboundEmailResult {
    data class LeadCreated(val leadId: String) : ProcessInboundEmailResult()
    data class Ignored(val reason: String) : ProcessInboundEmailResult()
}

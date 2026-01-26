package pl.detailing.crm.leads.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class CreateLeadHandler(
    private val leadRepository: LeadRepository
) {
    private val log = LoggerFactory.getLogger(CreateLeadHandler::class.java)

    @Transactional
    suspend fun handle(command: CreateLeadCommand): CreateLeadResult =
        withContext(Dispatchers.IO) {
            // Validate estimated value
            if (command.estimatedValue < 0) {
                throw ValidationException("Estimated value cannot be negative")
            }

            // Validate contact identifier
            if (command.contactIdentifier.isBlank()) {
                throw ValidationException("Contact identifier cannot be blank")
            }

            // For phone source, validate phone format
            if (command.source == LeadSource.PHONE) {
                val normalizedPhone = normalizePolishPhone(command.contactIdentifier.trim())
                if (!isValidPolishPhone(normalizedPhone)) {
                    throw ValidationException("Invalid Polish phone number format: ${command.contactIdentifier}")
                }
            }

            // Create lead
            val lead = Lead(
                id = LeadId.random(),
                studioId = command.studioId,
                source = command.source,
                status = LeadStatus.IN_PROGRESS,
                contactIdentifier = command.contactIdentifier.trim(),
                customerName = command.customerName?.trim(),
                initialMessage = command.initialMessage?.trim(),
                estimatedValue = command.estimatedValue,
                requiresVerification = false, // Manual entries don't require verification
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            // Save entity
            val entity = LeadEntity.fromDomain(lead)
            leadRepository.save(entity)

            log.info("[LEADS] Created lead: leadId={}, studioId={}, source={}, contact={}",
                lead.id.value, lead.studioId.value, lead.source, lead.contactIdentifier)

            CreateLeadResult(
                leadId = lead.id,
                source = lead.source,
                status = lead.status,
                contactIdentifier = lead.contactIdentifier,
                customerName = lead.customerName,
                initialMessage = lead.initialMessage,
                estimatedValue = lead.estimatedValue,
                requiresVerification = lead.requiresVerification,
                createdAt = lead.createdAt,
                updatedAt = lead.updatedAt
            )
        }
}

data class CreateLeadResult(
    val leadId: LeadId,
    val source: LeadSource,
    val status: LeadStatus,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long,
    val requiresVerification: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

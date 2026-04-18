package pl.detailing.crm.smscampaigns.reminder

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.smscampaigns.reminder.ai.SmsContentGeneratorService
import pl.detailing.crm.smscampaigns.reminder.ai.SmsGenerationContext
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class GenerateSmsContentCommand(
    val studioId: StudioId,
    val visitId: VisitId,
    val studioName: String,
    val scheduledFor: Instant,
    val phoneNumber: String,
)

data class GenerateSmsContentResult(
    val content: String,
    val charCount: Int
)

/**
 * Loads visit + customer context and asks the LLM to draft a retention SMS.
 *
 * Does NOT persist anything — the result is returned to the user for review
 * and optional editing before scheduling via [ScheduleVisitSmsReminderHandler].
 */
@Service
class GenerateSmsContentHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val generator: SmsContentGeneratorService
) {
    @Transactional(readOnly = true)
    suspend fun handle(command: GenerateSmsContentCommand): GenerateSmsContentResult {
        val visitEntity = visitRepository.findByIdAndStudioId(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Wizyta nie znaleziona: ${command.visitId.value}")

        visitEntity.serviceItems.size // force-load lazy collection

        val customerEntity = customerRepository.findByIdAndStudioId(
            id = visitEntity.customerId,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Klient nie znaleziony: ${visitEntity.customerId}")

        val daysSinceService = Duration.between(command.scheduledFor, Instant.now()).toDays().toInt()

        val services = visitEntity.serviceItems.map { it.serviceName }

        val context = SmsGenerationContext(
            customerFirstName = customerEntity.firstName ?: "Kliencie",
            vehicleBrand = visitEntity.brandSnapshot,
            vehicleModel = visitEntity.modelSnapshot,
            licensePlate = visitEntity.licensePlateSnapshot,
            services = services.ifEmpty { listOf("detailing") },
            studioName = command.studioName,
            daysSinceService = daysSinceService,
            phoneNumber = command.phoneNumber,
        )

        val content = generator.generate(context)
        return GenerateSmsContentResult(content = content, charCount = content.length)
    }
}

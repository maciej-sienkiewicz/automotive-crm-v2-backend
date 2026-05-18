package pl.detailing.crm.customer.consent.update

import jakarta.transaction.Transactional
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.create.CreateConsentHandler
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class UpdateConsentHandler(
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val createConsentHandler: CreateConsentHandler
) {

    @Transactional
    suspend fun handle(command: UpdateConsentCommand): Unit =
        withContext(Dispatchers.IO) {
            val entity = consentDefinitionRepository.findByIdAndStudioId(
                command.definitionId.value, command.studioId.value
            ) ?: throw NotFoundException("Zgoda nie została znaleziona")

            command.marketingChannels?.let { channels ->
                createConsentHandler.validateChannelUniqueness(
                    command.studioId, channels, excludeId = command.definitionId
                )
                entity.marketingChannels = channels.toMutableSet()
            }

            command.name?.let { entity.name = it.trim() }
            command.description?.let { entity.description = it.trim() }
            command.stage?.let { entity.stage = it }
            command.displayOrder?.let { entity.displayOrder = it }
            entity.updatedBy = command.updatedBy.value
            entity.updatedAt = Instant.now()

            consentDefinitionRepository.save(entity)
        }
}

data class UpdateConsentCommand(
    val studioId: StudioId,
    val updatedBy: UserId,
    val definitionId: ConsentDefinitionId,
    val name: String?,
    val description: String?,
    val stage: ProtocolStage?,
    val marketingChannels: Set<MarketingChannel>?,
    val displayOrder: Int?
)

package pl.detailing.crm.leads.userquote.save

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.userquote.infrastructure.LeadUserQuoteEntity
import pl.detailing.crm.leads.userquote.infrastructure.LeadUserQuoteItemEntity
import pl.detailing.crm.leads.userquote.infrastructure.LeadUserQuoteRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.UUID

data class UserQuoteItemInput(
    val serviceId: UUID?,
    val serviceName: String?,
    val priceNet: Long,
    val vatRate: Int,
    val priceGross: Long
)

data class SaveUserQuoteCommand(
    val leadId: LeadId,
    val studioId: StudioId,
    val items: List<UserQuoteItemInput>
)

data class UserQuoteItemResult(
    val id: String,
    val serviceId: String?,
    val serviceName: String,
    val priceNet: Long,
    val vatRate: Int,
    val priceGross: Long
)

data class SaveUserQuoteResult(
    val id: String,
    val leadId: String,
    val items: List<UserQuoteItemResult>,
    val totalNet: Long,
    val totalGross: Long,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Service
class SaveUserQuoteHandler(
    private val leadRepository: LeadRepository,
    private val userQuoteRepository: LeadUserQuoteRepository,
    private val serviceRepository: ServiceRepository,
    private val transactionTemplate: TransactionTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: SaveUserQuoteCommand): SaveUserQuoteResult =
        withContext(Dispatchers.IO) {
            transactionTemplate.execute { _ -> doSave(command) }!!
        }

    private fun doSave(command: SaveUserQuoteCommand): SaveUserQuoteResult {
        val leadEntity = leadRepository.findById(command.leadId.value)
            .orElseThrow { EntityNotFoundException("Lead nie został znaleziony: ${command.leadId}") }

        if (leadEntity.studioId != command.studioId.value) {
            throw ForbiddenException("Lead nie należy do tego studia")
        }

        if (command.items.isEmpty()) {
            throw ValidationException("Wycena użytkownika musi zawierać co najmniej jedną pozycję")
        }

        val requestedServiceIds = command.items.mapNotNull { it.serviceId }
        val catalogServices = if (requestedServiceIds.isNotEmpty()) {
            serviceRepository.findActiveByStudioId(command.studioId.value)
                .filter { it.id in requestedServiceIds }
                .associateBy { it.id }
        } else emptyMap()

        requestedServiceIds.forEach { serviceId ->
            if (serviceId !in catalogServices) {
                throw EntityNotFoundException("Usługa '$serviceId' nie została znaleziona w katalogu")
            }
        }

        val existing = userQuoteRepository.findByLeadId(command.leadId.value)
        val now = Instant.now()

        val quoteEntity = if (existing != null) {
            existing.items.clear()
            existing.updatedAt = now
            existing
        } else {
            LeadUserQuoteEntity(
                id = UUID.randomUUID(),
                leadId = command.leadId.value,
                studioId = command.studioId.value,
                createdAt = now,
                updatedAt = now
            )
        }

        val itemEntities = command.items.map { item ->
            val resolvedName: String = if (item.serviceId != null) {
                catalogServices[item.serviceId]!!.name
            } else {
                item.serviceName?.trim()?.takeIf { it.isNotBlank() }
                    ?: throw ValidationException("serviceName jest wymagane gdy serviceId nie jest podane")
            }

            LeadUserQuoteItemEntity(
                id = UUID.randomUUID(),
                quote = quoteEntity,
                serviceId = item.serviceId,
                serviceName = resolvedName,
                priceNet = item.priceNet,
                vatRate = item.vatRate,
                priceGross = item.priceGross
            )
        }
        quoteEntity.items.addAll(itemEntities)

        val saved = userQuoteRepository.save(quoteEntity)

        log.info("[LEADS] User quote saved: leadId={}, itemCount={}", command.leadId, saved.items.size)

        return saved.toResult()
    }
}

fun LeadUserQuoteEntity.toResult() = SaveUserQuoteResult(
    id = id.toString(),
    leadId = leadId.toString(),
    items = items.map {
        UserQuoteItemResult(
            id = it.id.toString(),
            serviceId = it.serviceId?.toString(),
            serviceName = it.serviceName,
            priceNet = it.priceNet,
            vatRate = it.vatRate,
            priceGross = it.priceGross
        )
    },
    totalNet = items.sumOf { it.priceNet },
    totalGross = items.sumOf { it.priceGross },
    createdAt = createdAt,
    updatedAt = updatedAt
)

package pl.detailing.crm.leads.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.leads.domain.Lead
import pl.detailing.crm.leads.domain.LeadCategory
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.domain.LeadTag
import pl.detailing.crm.leads.update.LeadStatusService
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.NewLeadCreatedEvent
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

data class CreateLeadCommand(
    val studioId: StudioId,
    val userId: UserId,
    val source: LeadSource,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long,
    val userName: String,
    val initialStatus: LeadStatus = LeadStatus.NEW,
    val customerId: CustomerId? = null,
    val threadId: UUID? = null,
    val category: LeadCategory? = null
)

data class CreateLeadResult(val leadId: LeadId)

/**
 * Manual/phone/voice lead creation. E-mail leads normally come in through
 * [pl.detailing.crm.leads.convert.MarkThreadAsLeadHandler], which builds on this.
 */
@Service
class CreateLeadHandler(
    private val leadRepository: LeadRepository,
    private val statusService: LeadStatusService,
    private val soleUserResolver: SoleUserResolver,
    private val tagService: LeadTagService,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: CreateLeadCommand): CreateLeadResult = withContext(Dispatchers.IO) {
        val autoAssignee = soleUserResolver.resolveForStudio(command.studioId.value)

        val lead = Lead(
            id = LeadId.random(),
            studioId = command.studioId,
            source = command.source,
            status = command.initialStatus,
            contactIdentifier = command.contactIdentifier.trim(),
            customerName = command.customerName?.trim(),
            initialMessage = command.initialMessage,
            estimatedValue = command.estimatedValue,
            requiresVerification = false,
            customerId = command.customerId,
            assignedUserId = autoAssignee?.id?.let { UserId(it) },
            assignedUserName = autoAssignee?.name,
            threadId = command.threadId,
            category = command.category,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        // The body of a `@Transactional suspend` function on Dispatchers.IO escapes the
        // interceptor-managed transaction (see AuditLogWriter) — TransactionTemplate it is.
        val entity = transactionTemplate.execute {
            val saved = leadRepository.save(LeadEntity.fromDomain(lead))
            statusService.recordCreation(saved, command.userId.value, command.userName)
            // Analityka „o co pytają" liczy po tagach, a ta ścieżka (telefon, formularz,
            // notatka głosowa) przyjmuje kategorię. Kody są wspólne, więc zapisujemy ją
            // także jako tag — inaczej lead spoza poczty wypadałby ze statystyk.
            command.category?.let { category ->
                LeadTag.fromCode(category.name)?.let { tagService.replaceTags(saved.id, listOf(it)) }
            }
            saved
        }!!

        eventPublisher.publishEvent(
            NewLeadCreatedEvent(
                source = this@CreateLeadHandler,
                studioId = command.studioId,
                leadId = LeadId(entity.id),
                leadSource = command.source,
                contactIdentifier = entity.contactIdentifier,
                customerName = entity.customerName,
                estimatedValue = entity.estimatedValue,
                createdAt = entity.createdAt
            )
        )
        log.info("[LEADS] Created lead {} ({} / {})", entity.id, command.source, entity.contactIdentifier)
        CreateLeadResult(LeadId(entity.id))
    }
}

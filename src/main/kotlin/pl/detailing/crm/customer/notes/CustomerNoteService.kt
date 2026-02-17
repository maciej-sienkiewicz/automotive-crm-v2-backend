package pl.detailing.crm.customer.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

@Service
class CustomerNoteService(
    private val customerNoteRepository: CustomerNoteRepository,
    private val customerRepository: CustomerRepository,
    private val auditService: AuditService
) {

    @Transactional(readOnly = true)
    suspend fun listNotes(customerId: UUID, studioId: UUID): List<CustomerNoteItem> =
        withContext(Dispatchers.IO) {
            customerNoteRepository
                .findByCustomerIdAndStudioIdOrderByCreatedAtDesc(customerId, studioId)
                .map { it.toItem() }
        }

    @Transactional
    suspend fun addNote(
        customerId: UUID,
        studioId: UUID,
        content: String,
        createdBy: UUID,
        createdByName: String
    ): CustomerNoteItem = withContext(Dispatchers.IO) {
        customerRepository.findByIdAndStudioId(customerId, studioId)
            ?: throw EntityNotFoundException("Customer not found")

        val now = Instant.now()
        val entity = CustomerNoteEntity(
            id = UUID.randomUUID(),
            studioId = studioId,
            customerId = customerId,
            content = content.trim(),
            createdBy = createdBy,
            createdByName = createdByName,
            createdAt = now,
            updatedAt = now
        )

        val saved = customerNoteRepository.save(entity)

        auditService.log(LogAuditCommand(
            studioId = StudioId(studioId),
            userId = UserId(createdBy),
            userDisplayName = createdByName,
            module = AuditModule.CUSTOMER,
            entityId = customerId.toString(),
            action = AuditAction.NOTE_ADDED,
            changes = listOf(FieldChange("content", null, content.trim())),
            metadata = mapOf("noteId" to saved.id.toString())
        ))

        saved.toItem()
    }

    @Transactional
    suspend fun updateNote(
        noteId: UUID,
        studioId: UUID,
        content: String,
        updatedBy: UUID? = null,
        updatedByName: String? = null
    ): CustomerNoteItem = withContext(Dispatchers.IO) {
        val entity = customerNoteRepository.findByIdAndStudioId(noteId, studioId)
            ?: throw EntityNotFoundException("Note not found")

        val oldContent = entity.content
        entity.content = content.trim()
        entity.updatedAt = Instant.now()

        val saved = customerNoteRepository.save(entity)

        if (updatedBy != null) {
            auditService.log(LogAuditCommand(
                studioId = StudioId(studioId),
                userId = UserId(updatedBy),
                userDisplayName = updatedByName ?: "",
                module = AuditModule.CUSTOMER,
                entityId = entity.customerId.toString(),
                action = AuditAction.NOTE_UPDATED,
                changes = listOf(FieldChange("content", oldContent, content.trim())),
                metadata = mapOf("noteId" to noteId.toString())
            ))
        }

        saved.toItem()
    }

    @Transactional
    suspend fun deleteNote(
        noteId: UUID,
        studioId: UUID,
        deletedBy: UUID? = null,
        deletedByName: String? = null
    ): Unit = withContext(Dispatchers.IO) {
        val entity = customerNoteRepository.findByIdAndStudioId(noteId, studioId)
            ?: throw EntityNotFoundException("Note not found")

        customerNoteRepository.delete(entity)

        if (deletedBy != null) {
            auditService.log(LogAuditCommand(
                studioId = StudioId(studioId),
                userId = UserId(deletedBy),
                userDisplayName = deletedByName ?: "",
                module = AuditModule.CUSTOMER,
                entityId = entity.customerId.toString(),
                action = AuditAction.NOTE_DELETED,
                metadata = mapOf("noteId" to noteId.toString())
            ))
        }
    }

    private fun CustomerNoteEntity.toItem() = CustomerNoteItem(
        id = id.toString(),
        content = content,
        createdBy = createdBy.toString(),
        createdByName = createdByName,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

data class CustomerNoteItem(
    val id: String,
    val content: String,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

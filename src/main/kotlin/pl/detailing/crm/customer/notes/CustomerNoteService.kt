package pl.detailing.crm.customer.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.EntityNotFoundException
import java.time.Instant
import java.util.UUID

@Service
class CustomerNoteService(
    private val customerNoteRepository: CustomerNoteRepository,
    private val customerRepository: CustomerRepository
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

        val entity = CustomerNoteEntity(
            id = UUID.randomUUID(),
            studioId = studioId,
            customerId = customerId,
            content = content.trim(),
            createdBy = createdBy,
            createdByName = createdByName,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        customerNoteRepository.save(entity).toItem()
    }

    @Transactional
    suspend fun updateNote(
        noteId: UUID,
        studioId: UUID,
        content: String
    ): CustomerNoteItem = withContext(Dispatchers.IO) {
        val entity = customerNoteRepository.findByIdAndStudioId(noteId, studioId)
            ?: throw EntityNotFoundException("Note not found")

        entity.content = content.trim()
        entity.updatedAt = Instant.now()

        customerNoteRepository.save(entity).toItem()
    }

    @Transactional
    suspend fun deleteNote(noteId: UUID, studioId: UUID): Unit = withContext(Dispatchers.IO) {
        val entity = customerNoteRepository.findByIdAndStudioId(noteId, studioId)
            ?: throw EntityNotFoundException("Note not found")

        customerNoteRepository.delete(entity)
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

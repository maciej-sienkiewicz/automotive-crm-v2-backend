package pl.detailing.crm.leads.quotereply

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

private const val MAX_EXAMPLES = 10

data class SaveQuoteReplyExampleCommand(
    val studioId: StudioId,
    val title: String,
    val content: String
)

data class UpdateQuoteReplyExampleCommand(
    val id: UUID,
    val studioId: StudioId,
    val title: String,
    val content: String
)

data class QuoteReplyExampleDto(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

@Service
class QuoteReplyExampleHandler(
    private val repository: QuoteReplyExampleRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun save(command: SaveQuoteReplyExampleCommand): QuoteReplyExampleDto =
        withContext(Dispatchers.IO) {
            val count = repository.countByStudioId(command.studioId.value)
            if (count >= MAX_EXAMPLES) {
                throw ValidationException("Przekroczono limit $MAX_EXAMPLES przykładów. Usuń starszy przykład przed dodaniem nowego.")
            }

            val entity = QuoteReplyExampleEntity(
                studioId = command.studioId.value,
                title = command.title.trim(),
                content = command.content.trim()
            )
            val saved = repository.save(entity)
            log.info("[QUOTE_REPLY] Saved example: id={}, studioId={}", saved.id, saved.studioId)
            saved.toDto()
        }

    @Transactional(readOnly = true)
    suspend fun list(studioId: StudioId): List<QuoteReplyExampleDto> =
        withContext(Dispatchers.IO) {
            repository.findByStudioIdOrderByCreatedAtDesc(studioId.value).map { it.toDto() }
        }

    @Transactional
    suspend fun update(command: UpdateQuoteReplyExampleCommand): QuoteReplyExampleDto =
        withContext(Dispatchers.IO) {
            val entity = repository.findByIdAndStudioId(command.id, command.studioId.value)
                ?: throw EntityNotFoundException("Przykład nie został znaleziony")
            entity.title = command.title.trim()
            entity.content = command.content.trim()
            entity.updatedAt = Instant.now()
            repository.save(entity).toDto()
        }

    @Transactional
    suspend fun delete(id: UUID, studioId: StudioId) =
        withContext(Dispatchers.IO) {
            val entity = repository.findByIdAndStudioId(id, studioId.value)
                ?: throw EntityNotFoundException("Przykład nie został znaleziony")
            repository.delete(entity)
            log.info("[QUOTE_REPLY] Deleted example: id={}, studioId={}", id, studioId.value)
        }

    private fun QuoteReplyExampleEntity.toDto() = QuoteReplyExampleDto(
        id = id.toString(),
        title = title,
        content = content,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

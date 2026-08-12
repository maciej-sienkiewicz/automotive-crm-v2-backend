package pl.detailing.crm.visit.technicalnote

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Service
class GetTechnicalNoteHistoryHandler(
    private val visitRepository: VisitRepository,
    private val historyRepository: VisitTechnicalNoteHistoryRepository
) {

    suspend fun handle(command: GetTechnicalNoteHistoryCommand): TechnicalNoteHistoryResult {
        visitRepository.findByIdAndStudioId(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        val entries = historyRepository.findByVisitIdAndStudioIdOrderByChangedAtDesc(
            visitId = command.visitId.value,
            studioId = command.studioId.value
        )

        return TechnicalNoteHistoryResult(
            entries = entries.map { entry ->
                TechnicalNoteHistoryEntry(
                    id = entry.id.toString(),
                    content = entry.content,
                    action = entry.action,
                    changedById = entry.changedById.toString(),
                    changedByName = entry.changedByName,
                    changedAt = entry.changedAt
                )
            }
        )
    }
}

data class GetTechnicalNoteHistoryCommand(
    val visitId: VisitId,
    val studioId: StudioId
)

data class TechnicalNoteHistoryResult(
    val entries: List<TechnicalNoteHistoryEntry>
)

data class TechnicalNoteHistoryEntry(
    val id: String,
    val content: String?,
    val action: String,
    val changedById: String,
    val changedByName: String,
    val changedAt: Instant
)

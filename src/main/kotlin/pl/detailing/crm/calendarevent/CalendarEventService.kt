package pl.detailing.crm.calendarevent

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class CalendarEventService(
    private val repository: CalendarEventRepository
) {
    fun list(studioId: StudioId, from: LocalDate, to: LocalDate): List<CalendarEventEntity> {
        if (to.isBefore(from)) throw ValidationException("Koniec zakresu jest wcześniejszy niż początek")
        return repository.findInRange(studioId.value, from, to)
    }

    @Transactional
    fun create(command: CreateCalendarEventCommand): CalendarEventEntity {
        val title = command.title.trim()
        validate(title, command.startDate, command.endDate)

        return repository.save(
            CalendarEventEntity(
                id = UUID.randomUUID(),
                studioId = command.studioId.value,
                title = title,
                description = command.description?.trim()?.takeIf { it.isNotEmpty() },
                startDate = command.startDate,
                endDate = command.endDate,
                createdBy = command.userId,
                createdByName = command.userName
            )
        )
    }

    @Transactional
    fun update(command: UpdateCalendarEventCommand): CalendarEventEntity {
        val event = repository.findByIdAndStudioId(command.eventId, command.studioId.value)
            ?: throw NotFoundException("Wydarzenie nie istnieje")

        val title = command.title.trim()
        validate(title, command.startDate, command.endDate)

        event.title = title
        event.description = command.description?.trim()?.takeIf { it.isNotEmpty() }
        event.startDate = command.startDate
        event.endDate = command.endDate
        event.updatedAt = Instant.now()
        return repository.save(event)
    }

    @Transactional
    fun delete(studioId: StudioId, eventId: UUID) {
        val event = repository.findByIdAndStudioId(eventId, studioId.value)
            ?: throw NotFoundException("Wydarzenie nie istnieje")
        repository.delete(event)
    }

    private fun validate(title: String, startDate: LocalDate, endDate: LocalDate) {
        if (title.isEmpty()) throw ValidationException("Tytuł wydarzenia jest wymagany")
        if (title.length > MAX_TITLE_LENGTH) {
            throw ValidationException("Tytuł nie może przekraczać $MAX_TITLE_LENGTH znaków")
        }
        if (endDate.isBefore(startDate)) {
            throw ValidationException("Koniec wydarzenia nie może być wcześniejszy niż początek")
        }
    }

    companion object {
        const val MAX_TITLE_LENGTH = 200
    }
}

data class CreateCalendarEventCommand(
    val studioId: StudioId,
    val userId: UUID,
    val userName: String,
    val title: String,
    val description: String?,
    val startDate: LocalDate,
    val endDate: LocalDate
)

data class UpdateCalendarEventCommand(
    val studioId: StudioId,
    val eventId: UUID,
    val title: String,
    val description: String?,
    val startDate: LocalDate,
    val endDate: LocalDate
)

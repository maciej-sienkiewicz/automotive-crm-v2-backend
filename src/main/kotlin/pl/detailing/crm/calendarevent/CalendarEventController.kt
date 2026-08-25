package pl.detailing.crm.calendarevent

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import java.time.LocalDate
import java.util.UUID

/**
 * Wydarzenia w kalendarzu studia — wpisy, które nie są wizytą ani rezerwacją.
 *
 * GET    /api/v1/calendar/events-custom?from=&to=  → wydarzenia w zakresie
 * POST   /api/v1/calendar/events-custom            → nowe wydarzenie
 * PUT    /api/v1/calendar/events-custom/{id}       → edycja
 * DELETE /api/v1/calendar/events-custom/{id}       → usunięcie
 *
 * Podgląd ma każdy, kto widzi kalendarz; zakładanie i zmiana wymaga prawa do
 * planowania wizyt — to ta sama decyzja, co zajęcie terminu w grafiku.
 */
@RestController
@RequestMapping("/api/v1/calendar/events-custom")
@RequiresPermission(Permission.VISITS_VIEW)
class CalendarEventController(
    private val service: CalendarEventService
) {
    @GetMapping
    fun list(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate
    ): ResponseEntity<List<CalendarEventResponse>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(service.list(principal.studioId, from, to).map { it.toResponse() })
    }

    @PostMapping
    @RequiresPermission(Permission.VISITS_CREATE)
    fun create(@RequestBody request: CalendarEventRequest): ResponseEntity<CalendarEventResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val event = service.create(
            CreateCalendarEventCommand(
                studioId = principal.studioId,
                userId = principal.userId.value,
                userName = principal.fullName,
                title = request.title,
                description = request.description,
                startDate = LocalDate.parse(request.startDate),
                endDate = LocalDate.parse(request.endDate)
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(event.toResponse())
    }

    @PutMapping("/{eventId}")
    @RequiresPermission(Permission.VISITS_CREATE)
    fun update(
        @PathVariable eventId: UUID,
        @RequestBody request: CalendarEventRequest
    ): ResponseEntity<CalendarEventResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val event = service.update(
            UpdateCalendarEventCommand(
                studioId = principal.studioId,
                eventId = eventId,
                title = request.title,
                description = request.description,
                startDate = LocalDate.parse(request.startDate),
                endDate = LocalDate.parse(request.endDate)
            )
        )
        return ResponseEntity.ok(event.toResponse())
    }

    @DeleteMapping("/{eventId}")
    @RequiresPermission(Permission.VISITS_CREATE)
    fun delete(@PathVariable eventId: UUID): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        service.delete(principal.studioId, eventId)
        return ResponseEntity.noContent().build()
    }
}

data class CalendarEventRequest(
    val title: String,
    val description: String?,
    /** ISO-8601, np. 2026-08-25. */
    val startDate: String,
    /** ISO-8601, ostatni dzień trwania wydarzenia (domknięty). */
    val endDate: String
)

data class CalendarEventResponse(
    val id: String,
    val title: String,
    val description: String?,
    val startDate: String,
    val endDate: String,
    val createdByName: String,
    val createdAt: String,
    val updatedAt: String
)

private fun CalendarEventEntity.toResponse() = CalendarEventResponse(
    id = id.toString(),
    title = title,
    description = description,
    startDate = startDate.toString(),
    endDate = endDate.toString(),
    createdByName = createdByName,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)

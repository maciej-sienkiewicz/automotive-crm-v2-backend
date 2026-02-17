package pl.detailing.crm.vehicle.notes

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/vehicles/{vehicleId}/notes")
class VehicleNoteController(
    private val vehicleNoteService: VehicleNoteService
) {

    @GetMapping
    fun listNotes(@PathVariable vehicleId: String): ResponseEntity<List<VehicleNoteResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val notes = vehicleNoteService.listNotes(
            vehicleId = UUID.fromString(vehicleId),
            studioId = principal.studioId.value
        )

        ResponseEntity.ok(notes.map { it.toResponse() })
    }

    @PostMapping
    fun addNote(
        @PathVariable vehicleId: String,
        @RequestBody request: AddNoteRequest
    ): ResponseEntity<VehicleNoteResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val note = vehicleNoteService.addNote(
            vehicleId = UUID.fromString(vehicleId),
            studioId = principal.studioId.value,
            content = request.content,
            createdBy = principal.userId.value,
            createdByName = principal.fullName
        )

        ResponseEntity.status(HttpStatus.CREATED).body(note.toResponse())
    }

    @PatchMapping("/{noteId}")
    fun updateNote(
        @PathVariable vehicleId: String,
        @PathVariable noteId: String,
        @RequestBody request: UpdateNoteRequest
    ): ResponseEntity<VehicleNoteResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val note = vehicleNoteService.updateNote(
            noteId = UUID.fromString(noteId),
            studioId = principal.studioId.value,
            content = request.content
        )

        ResponseEntity.ok(note.toResponse())
    }

    @DeleteMapping("/{noteId}")
    fun deleteNote(
        @PathVariable vehicleId: String,
        @PathVariable noteId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        vehicleNoteService.deleteNote(
            noteId = UUID.fromString(noteId),
            studioId = principal.studioId.value
        )

        ResponseEntity.noContent().build()
    }
}

data class AddNoteRequest(val content: String)
data class UpdateNoteRequest(val content: String)

data class VehicleNoteResponse(
    val id: String,
    val content: String,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

private fun VehicleNoteItem.toResponse() = VehicleNoteResponse(
    id = id,
    content = content,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = createdAt,
    updatedAt = updatedAt
)

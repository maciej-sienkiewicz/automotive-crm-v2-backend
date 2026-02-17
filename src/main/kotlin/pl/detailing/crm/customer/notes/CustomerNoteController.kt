package pl.detailing.crm.customer.notes

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/customers/{customerId}/notes")
class CustomerNoteController(
    private val customerNoteService: CustomerNoteService
) {

    @GetMapping
    fun listNotes(@PathVariable customerId: String): ResponseEntity<List<CustomerNoteResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val notes = customerNoteService.listNotes(
            customerId = UUID.fromString(customerId),
            studioId = principal.studioId.value
        )

        ResponseEntity.ok(notes.map { it.toResponse() })
    }

    @PostMapping
    fun addNote(
        @PathVariable customerId: String,
        @RequestBody request: AddNoteRequest
    ): ResponseEntity<CustomerNoteResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val note = customerNoteService.addNote(
            customerId = UUID.fromString(customerId),
            studioId = principal.studioId.value,
            content = request.content,
            createdBy = principal.userId.value,
            createdByName = principal.fullName
        )

        ResponseEntity.status(HttpStatus.CREATED).body(note.toResponse())
    }

    @PatchMapping("/{noteId}")
    fun updateNote(
        @PathVariable customerId: String,
        @PathVariable noteId: String,
        @RequestBody request: UpdateNoteRequest
    ): ResponseEntity<CustomerNoteResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val note = customerNoteService.updateNote(
            noteId = UUID.fromString(noteId),
            studioId = principal.studioId.value,
            content = request.content
        )

        ResponseEntity.ok(note.toResponse())
    }

    @DeleteMapping("/{noteId}")
    fun deleteNote(
        @PathVariable customerId: String,
        @PathVariable noteId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        customerNoteService.deleteNote(
            noteId = UUID.fromString(noteId),
            studioId = principal.studioId.value
        )

        ResponseEntity.noContent().build()
    }
}

data class AddNoteRequest(val content: String)
data class UpdateNoteRequest(val content: String)

data class CustomerNoteResponse(
    val id: String,
    val content: String,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

private fun CustomerNoteItem.toResponse() = CustomerNoteResponse(
    id = id,
    content = content,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = createdAt,
    updatedAt = updatedAt
)

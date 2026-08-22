package pl.detailing.crm.leads.formmail

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.NotFoundException
import java.time.Instant
import java.util.UUID

data class FormMailSourceDto(
    val id: String,
    val senderEmail: String,
    val active: Boolean,
    val createdByName: String?,
    val createdAt: Instant,
    val leadCount: Long,
    val lastLeadAt: Instant?
)

data class MarkMailAsFormLeadResponse(
    val sourceId: String,
    val senderEmail: String,
    /** CREATED | REJECTED | FAILED | ALREADY_PROCESSED */
    val status: String,
    val leadId: String?,
    val reason: String?
)

/**
 * „Lead z formularza" w skrzynce: oznaczenie maila i zarządzanie oznaczonymi
 * nadawcami. Tworzy leady, więc to samo uprawnienie co reszta modułu leadów.
 */
@RestController
@RequestMapping("/api/v1/comms")
@RequiresPermission(Permission.LEADS_MANAGE)
class FormMailController(
    private val markHandler: MarkMailAsFormLeadHandler,
    private val sourceRepository: FormMailSourceRepository
) {

    /**
     * Oznaczenie maila: rejestruje nadawcę jako formularz i od razu przetwarza tę
     * wiadomość. Odczyt idzie do LLM-a, więc odpowiedź potrafi zająć parę sekund —
     * w zamian wraca gotowy lead, a nie obietnica.
     */
    @PostMapping("/messages/{id}/mark-form-lead")
    fun markMessage(@PathVariable id: String): ResponseEntity<MarkMailAsFormLeadResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val outcome = markHandler.handle(
            MarkMailAsFormLeadCommand(
                studioId = principal.studioId,
                messageId = UUID.fromString(id),
                userName = principal.fullName
            )
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(
            MarkMailAsFormLeadResponse(
                sourceId = outcome.sourceId.toString(),
                senderEmail = outcome.senderEmail,
                status = outcome.status,
                leadId = outcome.leadId?.toString(),
                reason = outcome.reason
            )
        )
    }

    /** Oznaczeni nadawcy — także wyłączeni, żeby dało się ich włączyć z powrotem. */
    @GetMapping("/form-sources")
    fun listSources(): ResponseEntity<List<FormMailSourceDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            sourceRepository.findByStudioIdOrderByCreatedAtDesc(principal.studioId.value).map { it.toDto() }
        )
    }

    /**
     * Wyłączenie automatu dla nadawcy. Wiersz zostaje — dziennik odczytów i historia
     * „skąd wziął się ten lead" mają przeżyć rozmyślenie się użytkownika, a ponowne
     * oznaczenie dowolnego maila z tego adresu włącza źródło z powrotem.
     */
    @DeleteMapping("/form-sources/{id}")
    fun deactivateSource(@PathVariable id: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        val source = sourceRepository.findByIdAndStudioId(UUID.fromString(id), principal.studioId.value)
            ?: throw NotFoundException("Nie znaleziono oznaczonego nadawcy")
        source.active = false
        sourceRepository.save(source)
        return ResponseEntity.noContent().build()
    }

    private fun FormMailSourceEntity.toDto() = FormMailSourceDto(
        id = id.toString(),
        senderEmail = senderEmail,
        active = active,
        createdByName = createdByName,
        createdAt = createdAt,
        leadCount = leadCount,
        lastLeadAt = lastLeadAt
    )
}

package pl.detailing.crm.finance.duplicates

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import java.time.Instant
import java.time.LocalDate

/**
 * Wgląd w automatycznie wyciszone duplikaty i jedyna ręczna furtka:
 * „to jednak dwie różne sprzedaże".
 *
 * Świadomie nie ma tu żadnej kolejki do zatwierdzania — wykrycie działa samo
 * i samo wycisza dokument. Ten kontroler służy do wyjaśnienia, dlaczego suma
 * wygląda tak, jak wygląda, i do cofnięcia pomyłki automatu.
 */
@RestController
@RequestMapping("/api/v1/finance/duplicates")
@RequiresPermission(Permission.FINANCE_INVOICES)
class DocumentDuplicatesController(
    private val linkRepository: DocumentDuplicateLinkRepository,
    private val detector: DocumentDuplicateDetector
) {

    /** Aktywne powiązania — dokumenty, które przestały liczyć się do sum. */
    @GetMapping
    fun list(): ResponseEntity<DuplicateLinksResponse> {
        val studioId = SecurityContextHelper.getCurrentUser().studioId.value
        val links = linkRepository.findByStudioIdAndDismissedAtIsNullOrderByDetectedAtDesc(studioId)
        return ResponseEntity.ok(DuplicateLinksResponse(links.map { it.toResponse() }))
    }

    /**
     * Odrzuca powiązanie: wyciszony dokument wraca do statystyk, a para trafia
     * na czarną listę i nie zostanie zaproponowana ponownie.
     */
    @PatchMapping("/{id}/dismiss")
    fun dismiss(@PathVariable id: java.util.UUID): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        val found = detector.dismiss(principal.studioId, id, principal.userId.value)
        return if (found) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    private fun DocumentDuplicateLinkEntity.toResponse() = DuplicateLinkResponse(
        id = id.toString(),
        winnerKind = winnerKind.name,
        winnerId = winnerId.toString(),
        loserKind = loserKind.name,
        loserId = loserId.toString(),
        totalGross = totalGross,
        issueDate = issueDate,
        detectedAt = detectedAt
    )
}

data class DuplicateLinksResponse(val links: List<DuplicateLinkResponse>)

/** Kwota w groszach — jak w całym rejestrze przychodowym. */
data class DuplicateLinkResponse(
    val id: String,
    val winnerKind: String,
    val winnerId: String,
    val loserKind: String,
    val loserId: String,
    val totalGross: Long,
    val issueDate: LocalDate,
    val detectedAt: Instant
)

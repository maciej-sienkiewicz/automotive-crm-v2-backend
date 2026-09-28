package pl.detailing.crm.comms.draft

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.rolepreview.SimulatedEffectChannel
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * [useSentStyle] = null: użytkownik nie odpowiedział jeszcze na pytanie o styl — ekran
 * pyta go przy pierwszym kliknięciu. [sentMessageCount] pozwala w tym pytaniu powiedzieć,
 * z czego ten styl miałby się wziąć (albo że nie ma z czego).
 */
data class ReplyDraftPreferencesDto(
    val useSentStyle: Boolean?,
    val sentMessageCount: Long
)

data class SaveReplyDraftPreferencesRequest(val useSentStyle: Boolean)

/**
 * [useSentStyle] null = użyj zapisanego wyboru. Jawna wartość wygrywa i NIE nadpisuje
 * zapisanego wyboru — „tym razem spróbuj propozycji" to nie zmiana ustawienia.
 */
data class DraftReplyRequest(
    val useSentStyle: Boolean? = null,
    val signatureAppended: Boolean = false,
    /** „Popraw": bieżąca treść z edytora. Null = nowy szkic. */
    val currentDraft: String? = null,
    /** Uwagi pracownika — co poprawić. Wymagane, gdy jest [currentDraft]. */
    val instructions: String? = null,
    /**
     * Oferta wybrana przy „Szkic AI": pozycje, które szkic ma przedstawić klientowi. Null =
     * bez oferty (szkic może wtedy użyć kwot z wyceny leada, ale jej nie wypisuje).
     * Front zapisuje te same pozycje na leadzie, zanim poprosi o szkic.
     */
    val offer: List<DraftOfferLineRequest>? = null
)

/**
 * Pozycja oferty w szkicu. Kwoty w groszach brutto, policzone na froncie jedną funkcją
 * cen (applyAdjustment) z dokładnym brutto — backend ich nie przelicza (CLAUDE.md §1).
 */
data class DraftOfferLineRequest(
    val name: String,
    val quantity: Int = 1,
    val priceGross: Long,
    /** Cena przed rabatem; null albo równa [priceGross] = bez rabatu. */
    val regularPriceGross: Long? = null,
    val note: String? = null
)

data class ReplyDraftExampleDto(
    val threadId: String,
    val subject: String?,
    val sentAt: Instant,
    val similarity: Double
)

data class ReplyDraftDto(
    val bodyText: String,
    val useSentStyle: Boolean,
    val styleApplied: Boolean,
    val examples: List<ReplyDraftExampleDto>,
    val placeholders: List<String>,
    val unverifiedAmounts: List<String>,
    val notice: String?,
    /** Szkic przedstawia ofertę (pozycje z okna oferty). */
    val offerIncluded: Boolean = false
)

@Service
class ReplyDraftPreferenceService(
    private val repository: CommReplyDraftPreferenceRepository,
    private val store: ReplyExampleStore
) {
    @Transactional(readOnly = true)
    fun get(studioId: UUID, userId: UUID): ReplyDraftPreferencesDto =
        ReplyDraftPreferencesDto(
            useSentStyle = repository.findByUserIdAndStudioId(userId, studioId)?.useSentStyle,
            sentMessageCount = store.countSentMessages(studioId)
        )

    fun savedChoice(studioId: UUID, userId: UUID): Boolean? =
        repository.findByUserIdAndStudioId(userId, studioId)?.useSentStyle

    @Transactional
    fun save(studioId: UUID, userId: UUID, useSentStyle: Boolean): ReplyDraftPreferencesDto {
        val existing = repository.findByUserIdAndStudioId(userId, studioId)
        if (existing != null) {
            existing.useSentStyle = useSentStyle
            existing.updatedAt = Instant.now()
            repository.save(existing)
        } else {
            repository.save(CommReplyDraftPreferenceEntity(userId, studioId, useSentStyle))
        }
        return get(studioId, userId)
    }
}

/** Szkice odpowiedzi na maile — generowane na kliknięcie, nigdy w tle. */
@RestController
@RequestMapping("/api/v1/comms")
@RequiresPermission(Permission.LEADS_MANAGE)
class ReplyDraftController(
    private val draftService: ReplyDraftService,
    private val preferenceService: ReplyDraftPreferenceService,
    private val rolePreviewGuard: RolePreviewOutboundGuard
) {

    @GetMapping("/reply-draft/preferences")
    fun preferences(): ResponseEntity<ReplyDraftPreferencesDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(preferenceService.get(principal.studioId.value, principal.userId.value))
    }

    @PutMapping("/reply-draft/preferences")
    fun savePreferences(@RequestBody request: SaveReplyDraftPreferencesRequest): ResponseEntity<ReplyDraftPreferencesDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            preferenceService.save(principal.studioId.value, principal.userId.value, request.useSentStyle)
        )
    }

    @PostMapping("/threads/{id}/reply-draft")
    fun draft(@PathVariable id: String, @RequestBody request: DraftReplyRequest): ResponseEntity<ReplyDraftDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        rolePreviewGuard.requireOutsideSandbox(
            principal.studioId.value, SimulatedEffectChannel.AI, "szkic odpowiedzi na maila przez asystenta AI"
        )
        val useSentStyle = request.useSentStyle
            ?: preferenceService.savedChoice(principal.studioId.value, principal.userId.value)
            ?: throw ValidationException("Wybierz, czy szkic ma być w Twoim stylu, czy propozycją asystenta")

        val result = draftService.draft(
            DraftReplyCommand(
                studioId = principal.studioId.value,
                threadId = UUID.fromString(id),
                senderFullName = principal.fullName,
                useSentStyle = useSentStyle,
                signatureAppended = request.signatureAppended,
                currentDraft = request.currentDraft,
                instructions = request.instructions,
                offer = request.offer?.let(::offerLines)
            )
        )
        ResponseEntity.ok(
            ReplyDraftDto(
                bodyText = result.bodyText,
                useSentStyle = result.useSentStyle,
                styleApplied = result.styleApplied,
                examples = result.examples.map {
                    ReplyDraftExampleDto(it.threadId.toString(), it.subject, it.sentAt, it.similarity)
                },
                placeholders = result.placeholders,
                unverifiedAmounts = result.unverifiedAmounts,
                notice = result.notice,
                offerIncluded = result.offerIncluded
            )
        )
    }

    private fun offerLines(lines: List<DraftOfferLineRequest>): List<DraftQuoteLine> {
        if (lines.isEmpty()) throw ValidationException("Oferta nie ma żadnej usługi")
        if (lines.size > MAX_OFFER_LINES) throw ValidationException("Oferta może mieć najwyżej $MAX_OFFER_LINES pozycji")
        return lines.map { line ->
            val name = line.name.trim().take(200)
            if (name.isEmpty()) throw ValidationException("Pozycja oferty nie ma nazwy")
            if (line.priceGross < 0) throw ValidationException("Pozycja „$name\": cena nie może być ujemna")
            if (line.quantity !in 1..99) throw ValidationException("Pozycja „$name\": nieprawidłowa ilość")
            DraftQuoteLine(
                name = name,
                quantity = line.quantity,
                unitGross = line.priceGross,
                note = line.note?.trim()?.take(500)?.ifBlank { null },
                // Cena „regularna" niższa albo równa cenie oferty to nie rabat - pomijamy ją.
                regularUnitGross = line.regularPriceGross?.takeIf { it > line.priceGross }
            )
        }
    }

    private companion object {
        const val MAX_OFFER_LINES = 50
    }
}

package pl.detailing.crm.voice

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.leads.create.CreateLeadCommand
import pl.detailing.crm.leads.create.CreateLeadHandler
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.isValidPolishPhone
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.task.create.CreateTaskCommand
import pl.detailing.crm.task.create.CreateTaskHandler

private const val MAX_TEXT_LENGTH = 5000
private const val MAX_TITLE_LENGTH = 255
private const val VOICE_INTAKE_IDENTIFIER = "voice-intake"

@RestController
@RequestMapping("/api/mobile/voice")
class MobileVoiceController(
    private val mobileTokenService: MobileTokenService,
    private val studioRepository: StudioRepository,
    private val createLeadHandler: CreateLeadHandler,
    private val createTaskHandler: CreateTaskHandler
) {

    @GetMapping("/context")
    fun getContext(@RequestParam token: String): ResponseEntity<Any> {
        val user = mobileTokenService.resolveToken(token)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(VoiceErrorResponse("Invalid or expired token"))

        val studioName = studioRepository.findByStudioId(user.studioId)?.name ?: ""

        return ResponseEntity.ok(VoiceContextResponse(
            firstName = user.firstName,
            studioName = studioName
        ))
    }

    @PostMapping("/lead")
    fun createLead(@RequestBody request: VoiceLeadRequest): ResponseEntity<Any> = runBlocking {
        val user = mobileTokenService.resolveToken(request.token)
            ?: return@runBlocking ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(VoiceErrorResponse("Invalid or expired token"))

        if (request.text.isBlank() || request.text.length > MAX_TEXT_LENGTH) {
            return@runBlocking ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(VoiceErrorResponse("Text must be non-empty and at most $MAX_TEXT_LENGTH characters"))
        }

        val (contactIdentifier, source) = resolveContactInfo(request.phoneNumber)

        val result = createLeadHandler.handle(CreateLeadCommand(
            studioId = StudioId(user.studioId),
            userId = UserId(user.id),
            source = source,
            contactIdentifier = contactIdentifier,
            customerName = null,
            initialMessage = request.text,
            estimatedValue = 0,
            userName = "${user.firstName} ${user.lastName}"
        ))

        ResponseEntity.status(HttpStatus.CREATED).body(VoiceResultResponse(
            id = result.leadId.value.toString(),
            message = "Lead utworzony"
        ))
    }

    @PostMapping("/note")
    fun createNote(@RequestBody request: VoiceNoteRequest): ResponseEntity<Any> = runBlocking {
        val user = mobileTokenService.resolveToken(request.token)
            ?: return@runBlocking ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(VoiceErrorResponse("Invalid or expired token"))

        if (request.text.isBlank() || request.text.length > MAX_TEXT_LENGTH) {
            return@runBlocking ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(VoiceErrorResponse("Text must be non-empty and at most $MAX_TEXT_LENGTH characters"))
        }

        val title = request.text.take(MAX_TITLE_LENGTH)
        val meta = if (request.text.length > MAX_TITLE_LENGTH) request.text else null

        val task = createTaskHandler.handle(CreateTaskCommand(
            studioId = StudioId(user.studioId),
            userId = UserId(user.id),
            userName = "${user.firstName} ${user.lastName}",
            title = title,
            meta = meta
        ))

        ResponseEntity.status(HttpStatus.CREATED).body(VoiceResultResponse(
            id = task.id.value.toString(),
            message = "Notatka zapisana"
        ))
    }

    private fun resolveContactInfo(phoneNumber: String?): Pair<String, LeadSource> {
        if (phoneNumber == null) {
            return VOICE_INTAKE_IDENTIFIER to LeadSource.MANUAL
        }
        val normalized = normalizePolishPhone(phoneNumber)
        return if (isValidPolishPhone(normalized)) {
            normalized to LeadSource.PHONE
        } else {
            // Use MANUAL source for invalid phones — CreateLeadHandler rejects PHONE source with invalid numbers
            phoneNumber to LeadSource.MANUAL
        }
    }
}

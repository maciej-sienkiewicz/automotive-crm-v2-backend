package pl.detailing.crm.visitcard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitRepository

/**
 * Employee-facing Visit Card endpoints: obtaining the shareable customer link
 * (to preview exactly what the customer sees) and sending it to the customer.
 */
@RestController
@RequestMapping("/api/visits")
class VisitCardController(
    private val visitRepository: VisitRepository,
    private val tokenService: VisitCardTokenService,
    private val sendVisitCardLinkHandler: SendVisitCardLinkHandler,
    private val properties: VisitCardProperties
) {

    /**
     * Return the stable public link of the visit's card, creating the token on first use.
     */
    @GetMapping("/{visitId}/card-link")
    @RequiresPermission(Permission.VISITS_VIEW)
    fun getCardLink(@PathVariable visitId: String): ResponseEntity<VisitCardLinkResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val id = VisitId.fromString(visitId)

        withContext(Dispatchers.IO) {
            val visitEntity = visitRepository.findByIdAndStudioId(id.value, principal.studioId.value)
                ?: throw EntityNotFoundException("Visit not found: $visitId")

            val token = tokenService.getOrCreateToken(
                principal.studioId, id, pl.detailing.crm.shared.AppointmentId(visitEntity.appointmentId)
            )
            ResponseEntity.ok(
                VisitCardLinkResponse(
                    token = token,
                    path = "/vc/$token",
                    url = "${properties.frontendBaseUrl.trimEnd('/')}/vc/$token"
                )
            )
        }
    }

    /**
     * Send the card link to the customer over the studio-configured channel (e-mail/SMS).
     */
    @PostMapping("/{visitId}/card-link/send")
    @RequiresPermission(Permission.COMMUNICATION_SEND)
    fun sendCardLink(@PathVariable visitId: String): ResponseEntity<VisitCardSendResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = sendVisitCardLinkHandler.handle(
            SendVisitCardLinkCommand(
                visitId = VisitId.fromString(visitId),
                studioId = principal.studioId
            )
        )
        ResponseEntity.ok(
            VisitCardSendResponse(
                emailSent = result.emailSent,
                smsSent = result.smsSent,
                message = result.message
            )
        )
    }
}

data class VisitCardLinkResponse(
    val token: String,
    val path: String,
    val url: String
)

data class VisitCardSendResponse(
    val emailSent: Boolean,
    val smsSent: Boolean,
    val message: String
)

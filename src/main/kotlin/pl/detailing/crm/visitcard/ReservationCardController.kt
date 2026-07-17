package pl.detailing.crm.visitcard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.EntityNotFoundException

/**
 * Employee-facing card endpoints for reservations (appointments), mirroring the
 * visit variants. The issued link stays valid after check-in — resolution finds
 * the visit created from the reservation.
 */
@RestController
@RequestMapping("/api/appointments")
class ReservationCardController(
    private val appointmentRepository: AppointmentRepository,
    private val tokenService: VisitCardTokenService,
    private val sendReservationCardLinkHandler: SendReservationCardLinkHandler,
    private val sendStatusService: VisitCardSendStatusService,
    private val properties: VisitCardProperties
) {

    @GetMapping("/{appointmentId}/card-link")
    @RequiresPermission(Permission.VISITS_VIEW)
    fun getCardLink(@PathVariable appointmentId: String): ResponseEntity<VisitCardLinkResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val id = AppointmentId.fromString(appointmentId)

        withContext(Dispatchers.IO) {
            appointmentRepository.findByIdAndStudioId(id.value, principal.studioId.value)
                ?: throw EntityNotFoundException("Appointment not found: $appointmentId")

            val token = tokenService.getOrCreateTokenForAppointment(principal.studioId, id)
            val sendStatus = sendStatusService.status(principal.studioId.value, null, id.value)
            ResponseEntity.ok(
                VisitCardLinkResponse(
                    token = token,
                    path = "/vc/$token",
                    url = "${properties.frontendBaseUrl.trimEnd('/')}/vc/$token",
                    lastEmailSentAt = sendStatus.lastEmailSentAt,
                    lastSmsSentAt = sendStatus.lastSmsSentAt
                )
            )
        }
    }

    @PostMapping("/{appointmentId}/card-link/send")
    @RequiresPermission(Permission.COMMUNICATION_SEND)
    fun sendCardLink(
        @PathVariable appointmentId: String,
        @RequestBody(required = false) request: SendCardLinkRequest?
    ): ResponseEntity<VisitCardSendResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = sendReservationCardLinkHandler.handle(
            SendReservationCardLinkCommand(
                appointmentId = AppointmentId.fromString(appointmentId),
                studioId = principal.studioId,
                channelOverride = request?.channel?.let { VisitCardDeliveryChannel.fromString(it) }
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

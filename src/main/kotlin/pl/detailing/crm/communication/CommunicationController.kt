package pl.detailing.crm.communication

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.VisitId
import java.time.Instant

// ---------------------------------------------------------------------------
// Response DTOs
// ---------------------------------------------------------------------------

/**
 * Single communication entry as returned by the API.
 *
 * [subject] is null for SMS messages.
 * [errorMessage] is null when status = "SENT".
 * [bodyContent] contains the full message text (email body or SMS text)
 * for content preview.
 */
data class CommunicationEntryResponse(
    val id: String,
    /** null only in the customer-level history endpoint where visitId may not be set */
    val visitId: String?,
    /** "EMAIL" or "SMS" */
    val channel: String,
    /** Internal message type code, e.g. "VISIT_WELCOME_EMAIL" */
    val messageType: String,
    /** Human-readable Polish label for display in the UI */
    val messageTypeLabel: String,
    /** Email address or phone number */
    val recipientAddress: String,
    /** Email subject line; null for SMS */
    val subject: String?,
    /** Full message body for content preview */
    val bodyContent: String,
    /** "SENT" or "FAILED" */
    val status: String,
    /** Provider error description; null when status = "SENT" */
    val errorMessage: String?,
    val sentAt: Instant
)

data class VisitCommunicationResponse(
    val visitId: String,
    val entries: List<CommunicationEntryResponse>
)

data class CustomerCommunicationResponse(
    val customerId: String,
    val entries: List<CommunicationEntryResponse>
)

// ---------------------------------------------------------------------------
// Controller
// ---------------------------------------------------------------------------

/**
 * REST endpoints for the communication history feature.
 *
 * Visit-scoped endpoint:
 *   GET /api/visits/{visitId}/communication
 *   Returns all outbound messages tied to the given visit, newest-first.
 *
 * Customer-scoped endpoint:
 *   GET /api/v1/customers/{customerId}/communication
 *   Returns the full communication timeline for a customer (all visits + any
 *   messages not tied to a specific visit), newest-first.
 */
@RestController
class CommunicationController(
    private val getVisitCommunicationHandler: GetVisitCommunicationHandler,
    private val getCustomerCommunicationHandler: GetCustomerCommunicationHandler
) {

    /**
     * GET /api/visits/{visitId}/communication
     *
     * Returns all outbound communications (emails and SMSes) sent in the context
     * of a specific visit. Includes delivery status and full message content.
     */
    @GetMapping("/api/visits/{visitId}/communication")
    fun getVisitCommunication(
        @PathVariable visitId: String
    ): ResponseEntity<VisitCommunicationResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = getVisitCommunicationHandler.handle(
            GetVisitCommunicationCommand(
                visitId = VisitId.fromString(visitId),
                studioId = principal.studioId
            )
        )

        return ResponseEntity.ok(
            VisitCommunicationResponse(
                visitId = result.visitId,
                entries = result.entries.map { it.toResponse(includeVisitId = false) }
            )
        )
    }

    /**
     * GET /api/v1/customers/{customerId}/communication
     *
     * Returns the complete communication history for a customer across all visits.
     * Each entry includes a [visitId] so the UI can link back to the relevant visit.
     * Entries not tied to any visit (e.g. pre-visit automation SMS before visit creation)
     * will have visitId = null.
     */
    @GetMapping("/api/v1/customers/{customerId}/communication")
    fun getCustomerCommunication(
        @PathVariable customerId: String
    ): ResponseEntity<CustomerCommunicationResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = getCustomerCommunicationHandler.handle(
            GetCustomerCommunicationCommand(
                customerId = CustomerId.fromString(customerId),
                studioId = principal.studioId
            )
        )

        return ResponseEntity.ok(
            CustomerCommunicationResponse(
                customerId = result.customerId,
                entries = result.entries.map { it.toResponse() }
            )
        )
    }
}

// ---------------------------------------------------------------------------
// Mapping helpers
// ---------------------------------------------------------------------------

private fun CommunicationLogItem.toResponse(includeVisitId: Boolean = false) =
    CommunicationEntryResponse(
        id = id,
        visitId = null,
        channel = channel.name,
        messageType = messageType.name,
        messageTypeLabel = messageTypeLabel,
        recipientAddress = recipientAddress,
        subject = subject,
        bodyContent = bodyContent,
        status = status.name,
        errorMessage = errorMessage,
        sentAt = sentAt
    )

private fun CustomerCommunicationLogItem.toResponse() =
    CommunicationEntryResponse(
        id = id,
        visitId = visitId,
        channel = channel.name,
        messageType = messageType.name,
        messageTypeLabel = messageTypeLabel,
        recipientAddress = recipientAddress,
        subject = subject,
        bodyContent = bodyContent,
        status = status.name,
        errorMessage = errorMessage,
        sentAt = sentAt
    )

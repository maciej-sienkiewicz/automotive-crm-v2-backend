package pl.detailing.crm.signing

import pl.detailing.crm.shared.SignatureRequestId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.signing.domain.SignatureChannel
import pl.detailing.crm.signing.domain.SignatureRequest
import pl.detailing.crm.signing.domain.SignatureRequestStatus
import pl.detailing.crm.signing.domain.SignatureSubject
import java.time.Instant

/** Żądanie podpisu do testów - domyślnie świeżo utworzone, czekające na tablet. */
fun signatureRequest(
    subject: SignatureSubject,
    studioId: StudioId = StudioId.random(),
    channel: SignatureChannel = SignatureChannel.TABLET,
    status: SignatureRequestStatus = SignatureRequestStatus.PENDING_DISPLAY,
    requestedBy: UserId = UserId.random(),
    requestedByName: String = "Jan Kowalski",
    now: Instant = Instant.parse("2026-09-23T10:00:00Z")
) = SignatureRequest(
    id = SignatureRequestId.random(),
    studioId = studioId,
    subject = subject,
    tabletId = null,
    channel = channel,
    signerPhone = if (channel == SignatureChannel.SMS_LINK) "+48512345678" else null,
    linkToken = if (channel == SignatureChannel.SMS_LINK) "token-${java.util.UUID.randomUUID()}" else null,
    status = status,
    documentS3Key = "${studioId.value}/document.pdf",
    documentSha256 = "a".repeat(64),
    documentName = "Dokument",
    signerName = requestedByName,
    declarationText = "Oświadczam…",
    requestedBy = requestedBy,
    requestedByName = requestedByName,
    createdAt = now,
    expiresAt = now.plusSeconds(900),
    displayedAt = null,
    declarationAcceptedAt = null,
    signedAt = null,
    completedAt = null,
    signerIpAddress = null,
    signerDevice = null,
    signedPdfS3Key = null,
    failureReason = null,
    updatedAt = now
)

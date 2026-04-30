package pl.detailing.crm.visit.transitions.confirm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.protocol.infrastructure.PdfProcessingService
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import pl.detailing.crm.visit.infrastructure.S3DamageMapStorageService
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.util.UUID

@Service
class SendVisitConfirmedEmailHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val s3ProtocolStorageService: S3ProtocolStorageService,
    private val pdfProcessingService: PdfProcessingService,
    private val photoSessionService: PhotoSessionService,
    private val s3DamageMapStorageService: S3DamageMapStorageService,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: SendVisitConfirmedEmailCommand): Unit = withContext(Dispatchers.IO) {
        val visitEntity = visitRepository.findByIdAndStudioIdWithPhotos(command.visitId.value, command.studioId.value)
        if (visitEntity == null) {
            logger.warn("SendVisitConfirmedEmail: visit not found [visitId={}]", command.visitId)
            return@withContext
        }

        val customerEntity = customerRepository.findByIdAndStudioId(visitEntity.customerId, visitEntity.studioId)
        if (customerEntity == null) {
            logger.warn("SendVisitConfirmedEmail: customer not found [customerId={}]", visitEntity.customerId)
            return@withContext
        }

        val recipientEmail = customerEntity.email
        if (recipientEmail.isNullOrBlank()) {
            logger.debug(
                "SendVisitConfirmedEmail: customer has no email [visitId={} customerId={}]",
                command.visitId, visitEntity.customerId
            )
            return@withContext
        }

        val opts = command.options
        val attachments = mutableListOf<EmailAttachment>()

        if (opts.attachProtocol) {
            val protocols = visitProtocolRepository.findAllByVisitIdAndStudioIdAndStage(
                command.visitId.value, command.studioId.value, ProtocolStage.CHECK_IN
            ).filter { it.filledPdfS3Key != null }

            protocols.forEachIndexed { index, protocol ->
                val s3Key = protocol.filledPdfS3Key ?: return@forEachIndexed
                runCatching {
                    val raw = s3ProtocolStorageService.downloadBytes(s3Key)
                    val flat = pdfProcessingService.flattenPdfBytes(raw)
                    val suffix = if (protocols.size > 1) "_${index + 1}" else ""
                    attachments += EmailAttachment(
                        fileName = "protokol_przyjecia_${visitEntity.visitNumber}$suffix.pdf",
                        content = flat,
                        contentType = "application/pdf"
                    )
                }.onFailure { ex ->
                    logger.warn(
                        "SendVisitConfirmedEmail: failed to prepare protocol PDF [s3Key={}]: {}",
                        s3Key, ex.message
                    )
                }
            }
        }

        if (opts.attachPhotos) {
            val photos = visitEntity.photos
            val selectedPhotos = if (opts.photoIds.isEmpty()) photos
            else photos.filter { opts.photoIds.contains(it.id) }

            selectedPhotos.forEachIndexed { index, photo ->
                runCatching {
                    val bytes = photoSessionService.downloadBytes(photo.fileId)
                    val ext = photo.fileName.substringAfterLast('.', "jpg")
                    val contentType = when (ext.lowercase()) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        else -> "image/jpeg"
                    }
                    attachments += EmailAttachment(
                        fileName = "zdjecie_${visitEntity.visitNumber}_${index + 1}.$ext",
                        content = bytes,
                        contentType = contentType
                    )
                }.onFailure { ex ->
                    logger.warn(
                        "SendVisitConfirmedEmail: failed to download photo [fileId={}]: {}",
                        photo.fileId, ex.message
                    )
                }
            }
        }

        if (opts.attachDamageMap) {
            val damageMapKey = visitEntity.damageMapFileId
            if (damageMapKey != null) {
                runCatching {
                    val bytes = s3DamageMapStorageService.downloadBytes(damageMapKey)
                    attachments += EmailAttachment(
                        fileName = "mapa_uszkodzen_${visitEntity.visitNumber}.jpg",
                        content = bytes,
                        contentType = "image/jpeg"
                    )
                }.onFailure { ex ->
                    logger.warn(
                        "SendVisitConfirmedEmail: failed to download damage map [key={}]: {}",
                        damageMapKey, ex.message
                    )
                }
            } else {
                logger.debug(
                    "SendVisitConfirmedEmail: attachDamageMap=true but no damage map on visit [visitId={}]",
                    command.visitId
                )
            }
        }

        val customerName = listOfNotNull(customerEntity.firstName, customerEntity.lastName)
            .joinToString(" ").ifBlank { "Kliencie" }
        val vehicleName = "${visitEntity.brandSnapshot} ${visitEntity.modelSnapshot}"
        val subject = "Potwierdzenie rozpoczęcia wizyty – $vehicleName (wizyta ${visitEntity.visitNumber})"
        val body = buildBody(customerName, vehicleName, visitEntity.visitNumber, visitEntity.licensePlateSnapshot)

        val result = communicationGateway.sendEmail(
            customerId = visitEntity.customerId,
            studioId = visitEntity.studioId,
            to = recipientEmail,
            subject = subject,
            bodyText = body,
            attachments = attachments,
            context = "SendVisitConfirmedEmail visit=${command.visitId}"
        )

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = command.studioId,
                customerId = CustomerId(visitEntity.customerId),
                visitId = command.visitId,
                channel = CommunicationChannel.EMAIL,
                messageType = CommunicationMessageType.VISIT_CONFIRMED_EMAIL,
                recipientAddress = recipientEmail,
                subject = subject,
                bodyContent = body,
                success = result.success,
                errorMessage = result.errorMessage
            )
        )

        if (result.success) {
            logger.info(
                "SendVisitConfirmedEmail: sent [to={} visitId={} attachments={}]",
                recipientEmail, command.visitId, attachments.size
            )
        } else {
            logger.warn(
                "SendVisitConfirmedEmail: failed [to={} visitId={} error={}]",
                recipientEmail, command.visitId, result.errorMessage
            )
        }
    }

    private fun buildBody(
        customerName: String,
        vehicleName: String,
        visitNumber: String,
        licensePlate: String?
    ): String {
        val plateInfo = if (!licensePlate.isNullOrBlank()) " (nr rej. $licensePlate)" else ""
        return """
            Szanowny/a $customerName,

            Informujemy, że prace nad Państwa pojazdem $vehicleName$plateInfo zostały oficjalnie rozpoczęte.

            Numer wizyty: $visitNumber

            O zakończeniu prac oraz gotowości pojazdu do odbioru powiadomimy Państwa osobną wiadomością.

            W razie pytań zapraszamy do kontaktu z naszym serwisem.

            Pozdrawiamy,
            Zespół DetailBoost
        """.trimIndent()
    }
}

data class SendVisitConfirmedEmailOptions(
    val attachProtocol: Boolean = true,
    val attachPhotos: Boolean = false,
    val photoIds: List<UUID> = emptyList(),
    val attachDamageMap: Boolean = false
)

data class SendVisitConfirmedEmailCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val options: SendVisitConfirmedEmailOptions = SendVisitConfirmedEmailOptions()
)

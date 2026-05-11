package pl.detailing.crm.email.visitwelcome

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.email.automation.GetEmailTemplateConfigHandler
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.email.template.EmailTemplateContext
import pl.detailing.crm.email.template.EmailTemplateProcessor
import pl.detailing.crm.protocol.infrastructure.PdfProcessingService
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import pl.detailing.crm.visit.infrastructure.S3DamageMapStorageService
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.util.UUID

@Service
class SendVisitWelcomeEmailHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val studioRepository: StudioRepository,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val s3StorageService: S3ProtocolStorageService,
    private val pdfProcessingService: PdfProcessingService,
    private val photoSessionService: PhotoSessionService,
    private val s3DamageMapStorageService: S3DamageMapStorageService,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService,
    private val emailTemplateConfigHandler: GetEmailTemplateConfigHandler,
    private val emailTemplateProcessor: EmailTemplateProcessor
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: SendVisitWelcomeEmailCommand): Unit = withContext(Dispatchers.IO) {
        val visitEntity = visitRepository.findByIdAndStudioIdWithPhotos(command.visitId.value, command.studioId.value)
        if (visitEntity == null) {
            logger.warn("SendVisitWelcomeEmail: visit not found [visitId={}]", command.visitId)
            return@withContext
        }

        val customerEntity = customerRepository.findByIdAndStudioId(visitEntity.customerId, visitEntity.studioId)
        if (customerEntity == null) {
            logger.warn("SendVisitWelcomeEmail: customer not found [customerId={}]", visitEntity.customerId)
            return@withContext
        }

        val recipientEmail = customerEntity.email
        if (recipientEmail.isNullOrBlank()) {
            logger.info(
                "SendVisitWelcomeEmail: customer has no email, skipping [visitId={} customerId={}]",
                command.visitId, visitEntity.customerId
            )
            return@withContext
        }

        val templateConfig = emailTemplateConfigHandler.handle(command.studioId)
        val rule = templateConfig.visitWelcome

        val studioName = studioRepository.findById(command.studioId.value).map { it.name }.orElse("")
        val firstName = customerEntity.firstName ?: "Kliencie"
        val lastName = customerEntity.lastName ?: ""
        val fullName = listOfNotNull(customerEntity.firstName, customerEntity.lastName)
            .joinToString(" ").ifBlank { "Kliencie" }
        val vehicleName = "${visitEntity.brandSnapshot} ${visitEntity.modelSnapshot}"

        val context = EmailTemplateContext(
            firstName = firstName,
            fullName = fullName,
            studioName = studioName,
            vehicleName = vehicleName,
            licensePlate = visitEntity.licensePlateSnapshot,
            visitNumber = visitEntity.visitNumber
        )

        val subject = emailTemplateProcessor.process(rule.subjectTemplate, context)
        val body = emailTemplateProcessor.process(rule.bodyTemplate, context)

        val opts = command.options
        val attachments = mutableListOf<EmailAttachment>()

        if (opts.attachProtocol) {
            val protocols = visitProtocolRepository.findAllByVisitIdAndStudioIdAndStage(
                command.visitId.value, command.studioId.value, ProtocolStage.CHECK_IN
            ).filter { it.templateId != null && it.filledPdfS3Key != null }

            protocols.forEachIndexed { index, protocol ->
                val s3Key = protocol.filledPdfS3Key ?: return@forEachIndexed
                runCatching {
                    val rawBytes = s3StorageService.downloadBytes(s3Key)
                    val flatBytes = pdfProcessingService.flattenPdfBytes(rawBytes)
                    val suffix = if (protocols.size > 1) "_${index + 1}" else ""
                    attachments += EmailAttachment(
                        fileName = "protokol_przyjecia_${visitEntity.visitNumber}$suffix.pdf",
                        content = flatBytes,
                        contentType = "application/pdf"
                    )
                }.onFailure { ex ->
                    logger.warn(
                        "SendVisitWelcomeEmail: failed to prepare protocol PDF [s3Key={}]: {}",
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
                        "SendVisitWelcomeEmail: failed to download photo [fileId={}]: {}",
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
                    val isPdf = damageMapKey.endsWith(".pdf")
                    attachments += EmailAttachment(
                        fileName = if (isPdf) "mapa_uszkodzen_${visitEntity.visitNumber}.pdf" else "mapa_uszkodzen_${visitEntity.visitNumber}.jpg",
                        content = bytes,
                        contentType = if (isPdf) "application/pdf" else "image/jpeg"
                    )
                }.onFailure { ex ->
                    logger.warn(
                        "SendVisitWelcomeEmail: failed to download damage map [key={}]: {}",
                        damageMapKey, ex.message
                    )
                }
            } else {
                logger.debug(
                    "SendVisitWelcomeEmail: attachDamageMap=true but no damage map on visit [visitId={}]",
                    command.visitId
                )
            }
        }

        val result = communicationGateway.sendEmail(
            customerId = visitEntity.customerId,
            studioId = visitEntity.studioId,
            to = recipientEmail,
            subject = subject,
            bodyText = body,
            attachments = attachments,
            context = "SendVisitWelcomeEmail visit=${command.visitId}"
        )

        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = command.studioId,
                customerId = CustomerId(visitEntity.customerId),
                visitId = command.visitId,
                channel = CommunicationChannel.EMAIL,
                messageType = CommunicationMessageType.VISIT_WELCOME_EMAIL,
                recipientAddress = recipientEmail,
                subject = subject,
                bodyContent = body,
                success = result.success,
                errorMessage = result.errorMessage
            )
        )

        if (result.success) {
            logger.info(
                "SendVisitWelcomeEmail: email sent [to={} visitId={} attachments={}]",
                recipientEmail, command.visitId, attachments.size
            )
        } else {
            logger.warn(
                "SendVisitWelcomeEmail: delivery failed [to={} visitId={} error={}]",
                recipientEmail, command.visitId, result.errorMessage
            )
        }
    }
}

data class WelcomeEmailOptions(
    val attachProtocol: Boolean = true,
    val attachPhotos: Boolean = false,
    val photoIds: List<UUID> = emptyList(),
    val attachDamageMap: Boolean = false
)

data class SendVisitWelcomeEmailCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val options: WelcomeEmailOptions = WelcomeEmailOptions()
)

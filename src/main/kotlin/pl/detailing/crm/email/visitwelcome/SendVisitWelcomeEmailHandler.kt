package pl.detailing.crm.email.visitwelcome

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
import pl.detailing.crm.visit.infrastructure.VisitRepository

/**
 * Sends a welcome email to the customer when a visit is opened (check-in).
 *
 * Consent enforcement is handled transparently by [OutboundCommunicationGateway].
 */
@Service
class SendVisitWelcomeEmailHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val s3StorageService: S3ProtocolStorageService,
    private val pdfProcessingService: PdfProcessingService,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun handle(command: SendVisitWelcomeEmailCommand): Unit = withContext(Dispatchers.IO) {
        val visitEntity = visitRepository.findById(command.visitId.value).orElse(null)
        if (visitEntity == null) {
            logger.warn("SendVisitWelcomeEmail: visit not found [visitId={}]", command.visitId)
            return@withContext
        }

        val customerEntity = customerRepository.findByIdAndStudioId(
            visitEntity.customerId,
            visitEntity.studioId
        )
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

        val protocols = visitProtocolRepository.findAllByVisitIdAndStudioIdAndStage(
            command.visitId.value,
            command.studioId.value,
            ProtocolStage.CHECK_IN
        ).filter { it.filledPdfS3Key != null }

        val attachments = protocols.mapIndexedNotNull { index, protocol ->
            val s3Key = protocol.filledPdfS3Key ?: return@mapIndexedNotNull null
            try {
                val rawBytes = s3StorageService.downloadBytes(s3Key)
                val flatBytes = pdfProcessingService.flattenPdfBytes(rawBytes)
                val suffix = if (protocols.size > 1) "_${index + 1}" else ""
                EmailAttachment(
                    fileName = "protokol_przyjecia_${visitEntity.visitNumber}$suffix.pdf",
                    content = flatBytes,
                    contentType = "application/pdf"
                )
            } catch (ex: Exception) {
                logger.warn(
                    "SendVisitWelcomeEmail: failed to prepare protocol PDF attachment [s3Key={}]: {}",
                    s3Key, ex.message
                )
                null
            }
        }

        val customerName = listOfNotNull(customerEntity.firstName, customerEntity.lastName)
            .joinToString(" ").ifBlank { "Kliencie" }
        val vehicleName = "${visitEntity.brandSnapshot} ${visitEntity.modelSnapshot}"
        val subject = buildSubject(vehicleName, visitEntity.visitNumber)
        val body = buildBody(customerName, vehicleName, visitEntity.visitNumber, visitEntity.licensePlateSnapshot)

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

    private fun buildSubject(vehicleName: String, visitNumber: String): String =
        "Potwierdzenie przyjęcia pojazdu – $vehicleName (wizyta $visitNumber)"

    private fun buildBody(
        customerName: String,
        vehicleName: String,
        visitNumber: String,
        licensePlate: String?
    ): String {
        val plateInfo = if (!licensePlate.isNullOrBlank()) " (nr rej. $licensePlate)" else ""
        return """
            Szanowny/a $customerName,

            Dziękujemy za powierzenie nam Państwa pojazdu. Niniejszym potwierdzamy przyjęcie pojazdu $vehicleName$plateInfo do naszego serwisu.

            Numer wizyty: $visitNumber

            W załączniku przesyłamy protokół przyjęcia pojazdu. Prosimy o zapoznanie się z jego treścią i zachowanie go dla celów ewidencyjnych.

            W razie pytań zapraszamy do kontaktu z naszym serwisem.

            Pozdrawiamy,
            Zespół DetailBoost
        """.trimIndent()
    }
}

data class SendVisitWelcomeEmailCommand(
    val visitId: VisitId,
    val studioId: StudioId
)

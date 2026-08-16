package pl.detailing.crm.protocol.template

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.protocol.domain.ProtocolTemplate
import pl.detailing.crm.protocol.domain.ProtocolTemplateVerificationStatus
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateEntity
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Downloads a freshly uploaded template file from S3, verifies that it contains
 * every field required by the visit pipeline and persists the verdict on the
 * template. The frontend calls this right after the S3 upload completes; a
 * template that is not VERIFIED should not be attached to protocol rules.
 */
@Service
class VerifyProtocolTemplateHandler(
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val s3StorageService: S3ProtocolStorageService,
    private val verificationService: ProtocolTemplateVerificationService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: VerifyProtocolTemplateCommand): VerifyProtocolTemplateResult =
        withContext(Dispatchers.IO) {
            val entity = protocolTemplateRepository.findByIdAndStudioId(
                command.templateId, command.studioId.value
            ) ?: throw NotFoundException("Szablon protokołu nie został znaleziony")

            val template = entity.toDomain()

            val fileBytes = try {
                s3StorageService.downloadBytes(template.s3Key)
            } catch (e: Exception) {
                logger.warn(
                    "Template verification: file not found in S3 for template {} (key={}): {}",
                    template.id, template.s3Key, e.message
                )
                throw ValidationException(
                    "Plik szablonu nie został jeszcze przesłany. Wgraj plik i spróbuj ponownie."
                )
            }

            val result = verificationService.verify(fileBytes, template.fileFormat)

            val newStatus = if (result.isValid) {
                ProtocolTemplateVerificationStatus.VERIFIED
            } else {
                ProtocolTemplateVerificationStatus.REJECTED
            }

            val updated = template.copy(
                verificationStatus = newStatus,
                updatedBy = command.userId,
                updatedAt = Instant.now()
            )
            protocolTemplateRepository.save(ProtocolTemplateEntity.fromDomain(updated))

            logger.info(
                "Template verification: template={} format={} status={} missing={} problems={}",
                template.id, template.fileFormat, newStatus, result.missingFields, result.problems
            )

            VerifyProtocolTemplateResult(template = updated, verification = result)
        }
}

data class VerifyProtocolTemplateCommand(
    val studioId: StudioId,
    val userId: UserId,
    val templateId: UUID
)

data class VerifyProtocolTemplateResult(
    val template: ProtocolTemplate,
    val verification: ProtocolTemplateVerificationService.VerificationResult
)

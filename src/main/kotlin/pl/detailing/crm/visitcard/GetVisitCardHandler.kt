package pl.detailing.crm.visitcard

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.PendingOperation
import pl.detailing.crm.shared.VisitProtocolStatus
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import pl.detailing.crm.visit.infrastructure.PhotoSessionService
import pl.detailing.crm.visit.infrastructure.S3DamageMapStorageService
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.util.UUID

/**
 * Assembles the public Visit Card for a card token.
 *
 * Content is disclosed progressively based on visit status:
 *  - always: reservation, vehicle, visit dates, company contact, services with final pricing
 *  - from IN_PROGRESS: admission date, signed consents, photos, damage map
 *  - from READY_FOR_PICKUP: completion dates, extra documents, payment status
 */
@Service
class GetVisitCardHandler(
    private val tokenService: VisitCardTokenService,
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val studioRepository: StudioRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val financialDocumentRepository: FinancialDocumentRepository,
    private val documentService: DocumentService,
    private val documentStorageService: DocumentStorageService,
    private val photoSessionService: PhotoSessionService,
    private val s3DamageMapStorageService: S3DamageMapStorageService,
    private val s3ProtocolStorageService: S3ProtocolStorageService
) {

    @Transactional(readOnly = true)
    fun handle(token: String): VisitCardResponse {
        val tokenEntity = tokenService.findByToken(token)
            ?: throw EntityNotFoundException("Karta wizyty nie została znaleziona")

        val visitEntity = visitRepository.findByIdAndStudioIdWithPhotos(tokenEntity.visitId, tokenEntity.studioId)
            ?: throw EntityNotFoundException("Karta wizyty nie została znaleziona")
        visitEntity.serviceItems.size // force-load within transaction

        val visit = visitEntity.toDomain()
        val studioId = tokenEntity.studioId

        val customer = customerRepository.findByIdAndStudioId(visit.customerId.value, studioId)

        val started = visit.status != VisitStatus.DRAFT
        val finished = visit.status in setOf(VisitStatus.READY_FOR_PICKUP, VisitStatus.COMPLETED, VisitStatus.ARCHIVED)

        return VisitCardResponse(
            visitNumber = visit.visitNumber,
            title = visit.title,
            status = visit.status.name,
            reservation = VisitCardReservation(
                scheduledDate = visit.scheduledDate,
                estimatedCompletionDate = visit.estimatedCompletionDate
            ),
            vehicle = VisitCardVehicle(
                brand = visit.brandSnapshot,
                model = visit.modelSnapshot,
                licensePlate = visit.licensePlateSnapshot,
                yearOfProduction = visit.yearOfProductionSnapshot,
                color = visit.colorSnapshot
            ),
            customer = VisitCardCustomer(
                firstName = customer?.firstName,
                lastName = customer?.lastName
            ),
            company = buildCompanySection(studioId),
            services = buildServiceLines(visit),
            totals = VisitCardTotals(
                totalNet = visit.calculateTotalNet().amountInCents,
                totalGross = visit.calculateTotalGross().amountInCents,
                currency = "PLN"
            ),
            inProgress = if (started) buildInProgressSection(visit, studioId) else null,
            completion = if (finished) buildCompletionSection(visit, studioId) else null
        )
    }

    private fun buildCompanySection(studioId: UUID): VisitCardCompany {
        val settings = studioSettingsRepository.findById(studioId).orElse(null)
        val studioName = settings?.name?.takeIf { it.isNotBlank() }
            ?: studioRepository.findByStudioId(studioId)?.name
            ?: ""
        val logoUrl = settings?.logoS3Key?.let {
            runCatching { documentStorageService.generateDownloadUrl(it) }.getOrNull()
        }
        return VisitCardCompany(
            name = studioName,
            street = settings?.street,
            postalCode = settings?.postalCode,
            city = settings?.city,
            phone = settings?.phone,
            email = settings?.email,
            website = settings?.website,
            logoUrl = logoUrl
        )
    }

    /**
     * Customer-facing service list mirrors the "effective confirmed state" pricing used by
     * Visit.calculateTotalNet/Gross, so line items always sum up to the displayed totals:
     * pending edits show the previously confirmed price, pending additions are hidden
     * until approved, rejected items are omitted.
     */
    private fun buildServiceLines(visit: Visit): List<VisitCardServiceLine> {
        return visit.serviceItems.mapNotNull { item ->
            val (net, gross) = when {
                item.status == VisitServiceStatus.CONFIRMED || item.status == VisitServiceStatus.APPROVED ->
                    item.finalPriceNet to item.finalPriceGross
                item.status == VisitServiceStatus.PENDING && item.pendingOperation == PendingOperation.EDIT -> {
                    val snapshot = item.confirmedSnapshot ?: return@mapNotNull null
                    snapshot.finalPriceNet to snapshot.finalPriceGross
                }
                item.status == VisitServiceStatus.PENDING && item.pendingOperation == PendingOperation.DELETE ->
                    item.finalPriceNet to item.finalPriceGross
                else -> return@mapNotNull null
            }
            VisitCardServiceLine(
                name = item.serviceName,
                note = item.customNote,
                priceGross = gross.amountInCents,
                priceNet = net.amountInCents
            )
        }
    }

    private fun buildInProgressSection(visit: Visit, studioId: UUID): VisitCardInProgress {
        val signedProtocols = visitProtocolRepository.findAllByVisitIdAndStudioIdAndStatus(
            visit.id.value, studioId, VisitProtocolStatus.SIGNED
        )

        val signedConsents = signedProtocols.mapNotNull { protocol ->
            val signedAt = protocol.signedAt ?: return@mapNotNull null
            val name = protocol.consentDefinitionId
                ?.let { consentDefinitionRepository.findByIdAndStudioId(it, studioId)?.name }
                ?: protocol.templateId?.let {
                    protocolTemplateRepository.findByIdAndStudioId(it, studioId)?.name
                }
                ?: "Podpisany dokument"
            VisitCardSignedDocument(
                name = name,
                signedAt = signedAt,
                downloadUrl = protocol.signedPdfS3Key?.let { key ->
                    runCatching { s3ProtocolStorageService.generateDownloadUrl(key) }.getOrNull()
                }
            )
        }

        val photos = visit.photos.map { photo ->
            VisitCardPhoto(
                url = photoSessionService.generateDownloadUrl(photo.fileId),
                description = photo.description,
                uploadedAt = photo.uploadedAt
            )
        }

        val damageMapUrl = visit.damageMapFileId?.let { key ->
            runCatching { s3DamageMapStorageService.generateDownloadUrl(key) }.getOrNull()
        }

        return VisitCardInProgress(
            // The visit record is created at vehicle check-in, so its creation time is the admission time
            admissionDate = visit.createdAt,
            signedConsents = signedConsents,
            photos = photos,
            damageMapUrl = damageMapUrl
        )
    }

    private fun buildCompletionSection(visit: Visit, studioId: UUID): VisitCardCompletion {
        val documents = runCatching { documentService.getDocumentsByVisit(visit.id.value, studioId) }
            .getOrDefault(emptyList())
            .map { doc ->
                VisitCardDocument(
                    name = doc.name,
                    fileName = doc.fileName,
                    downloadUrl = doc.fileUrl,
                    uploadedAt = doc.uploadedAt
                )
            }

        val incomeDocs = financialDocumentRepository
            .findAllByVisitIdAndStudioIdAndDeletedAtIsNull(visit.id.value, studioId)
            .filter { it.direction == DocumentDirection.INCOME }

        val paymentStatus = when {
            incomeDocs.isEmpty() -> null
            incomeDocs.any { it.status == DocumentStatus.OVERDUE } -> DocumentStatus.OVERDUE.name
            incomeDocs.any { it.status == DocumentStatus.PENDING } -> DocumentStatus.PENDING.name
            else -> DocumentStatus.PAID.name
        }

        return VisitCardCompletion(
            readyForPickupDate = visit.actualCompletionDate,
            pickupDate = visit.pickupDate,
            documents = documents,
            paymentStatus = paymentStatus
        )
    }
}

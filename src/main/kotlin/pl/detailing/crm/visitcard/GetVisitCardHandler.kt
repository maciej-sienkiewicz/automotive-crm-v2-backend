package pl.detailing.crm.visitcard

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.PendingOperation
import pl.detailing.crm.shared.ProtocolStage
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
import pl.detailing.crm.service.infrastructure.ServicePackageItemRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.VatRate
import pl.detailing.crm.visitcard.upsell.UpsellPackageItemDto
import pl.detailing.crm.visitcard.upsell.VisitCardUpsellSuggestion
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionEntity
import pl.detailing.crm.visitcard.upsell.infrastructure.VisitUpsellSuggestionRepository
import pl.detailing.crm.visitcard.upsell.toPublicDto
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
    private val s3ProtocolStorageService: S3ProtocolStorageService,
    private val upsellSuggestionRepository: VisitUpsellSuggestionRepository,
    private val appointmentRepository: AppointmentRepository,
    private val vehicleRepository: VehicleRepository,
    private val serviceRepository: ServiceRepository,
    private val servicePackageItemRepository: ServicePackageItemRepository
) {

    /**
     * Resolves a card token to its content:
     *  - visit token → visit card,
     *  - appointment token → the visit created from that reservation (after
     *    check-in the same link keeps working), or the reservation card itself
     *    while no visit exists yet.
     */
    @Transactional(readOnly = true)
    fun handle(token: String): VisitCardResponse {
        val tokenEntity = tokenService.findByToken(token)
            ?: throw EntityNotFoundException("Karta wizyty nie została znaleziona")
        val studioId = tokenEntity.studioId

        val visitEntity = when {
            tokenEntity.visitId != null ->
                visitRepository.findByIdAndStudioIdWithPhotos(tokenEntity.visitId, studioId)
                    ?: throw EntityNotFoundException("Karta wizyty nie została znaleziona")
            else ->
                visitRepository.findByAppointmentIdAndStudioId(tokenEntity.appointmentId!!, studioId)
                    ?.let { visitRepository.findByIdAndStudioIdWithPhotos(it.id, studioId) }
        }

        if (visitEntity == null) {
            return buildReservationCard(tokenEntity.appointmentId!!, studioId)
        }
        return buildVisitCard(visitEntity, studioId)
    }

    private fun buildVisitCard(
        visitEntity: pl.detailing.crm.visit.infrastructure.VisitEntity,
        studioId: UUID
    ): VisitCardResponse {
        visitEntity.serviceItems.size // force-load within transaction

        val visit = visitEntity.toDomain()

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
                totalDiscountGross = calculateVisitDiscountGross(visit),
                currency = "PLN"
            ),
            inProgress = if (started) buildInProgressSection(visit, studioId) else null,
            completion = if (finished) buildCompletionSection(visit, studioId) else null,
            // Include suggestions attached to the visit AND to the reservation it came from,
            // so suggestions assigned at booking time survive the check-in transition.
            upsellSuggestions = buildUpsellSuggestions(
                upsellSuggestionRepository.findAllForVisitCard(visit.id.value, visit.appointmentId.value, studioId),
                studioId
            )
        )
    }

    /** Card for a reservation that has not been checked in yet — booking info only. */
    private fun buildReservationCard(appointmentId: UUID, studioId: UUID): VisitCardResponse {
        val appointment: AppointmentEntity = appointmentRepository.findByIdAndStudioId(appointmentId, studioId)
            ?: throw EntityNotFoundException("Karta wizyty nie została znaleziona")

        val customer = customerRepository.findByIdAndStudioId(appointment.customerId, studioId)

        val vehicle = appointment.vehicleId
            ?.let { vehicleRepository.findByIdAndStudioId(it, studioId) }
            ?.let { entity ->
                val domain = entity.toDomain()
                VisitCardVehicle(
                    brand = domain.brand,
                    model = domain.model,
                    licensePlate = domain.licensePlate,
                    yearOfProduction = domain.yearOfProduction,
                    color = null
                )
            }

        val status = when (appointment.status) {
            AppointmentStatus.CANCELLED, AppointmentStatus.ABANDONED -> "REJECTED"
            else -> "RESERVATION"
        }

        val services = appointment.lineItems.map { item ->
            VisitCardServiceLine(
                name = item.serviceName,
                note = item.customNote,
                priceGross = item.finalPriceGross,
                priceNet = item.finalPriceNet
            )
        }

        return VisitCardResponse(
            visitNumber = "",
            title = null,
            status = status,
            reservation = VisitCardReservation(
                scheduledDate = appointment.startDateTime,
                estimatedCompletionDate = appointment.endDateTime
            ),
            vehicle = vehicle,
            customer = VisitCardCustomer(
                firstName = customer?.firstName,
                lastName = customer?.lastName
            ),
            company = buildCompanySection(studioId),
            services = services,
            totals = VisitCardTotals(
                totalNet = appointment.lineItems.sumOf { it.finalPriceNet },
                totalGross = appointment.lineItems.sumOf { it.finalPriceGross },
                totalDiscountGross = appointment.lineItems.sumOf { item ->
                    val originalGross = VatRate.fromInt(item.vatRate)
                        .calculateGrossAmount(Money.fromCents(item.basePriceNet))
                        .amountInCents
                    maxOf(0L, originalGross - item.finalPriceGross)
                },
                currency = "PLN"
            ),
            inProgress = null,
            completion = null,
            upsellSuggestions = buildUpsellSuggestions(
                upsellSuggestionRepository.findAllByAppointmentIdAndStudioId(appointmentId, studioId),
                studioId
            )
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
        // Include all CHECK_IN protocols that have at least a filled PDF — covers both
        // READY_FOR_SIGNATURE (filled, not yet digitally signed) and SIGNED states.
        val checkInProtocols = visitProtocolRepository.findAllByVisitIdAndStudioIdAndStage(
            visit.id.value, studioId, ProtocolStage.CHECK_IN
        ).filter { it.filledPdfS3Key != null || it.signedPdfS3Key != null }

        val signedConsents = checkInProtocols.map { protocol ->
            val name = protocol.consentDefinitionId
                ?.let { consentDefinitionRepository.findByIdAndStudioId(it, studioId)?.name }
                ?: protocol.templateId?.let {
                    protocolTemplateRepository.findByIdAndStudioId(it, studioId)?.name
                }
                ?: "Protokół odbioru pojazdu"
            val pdfKey = protocol.signedPdfS3Key ?: protocol.filledPdfS3Key
            VisitCardSignedDocument(
                name = name,
                signedAt = protocol.signedAt,
                downloadUrl = pdfKey?.let { key ->
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

    /**
     * Computes the total discount shown in the summary (original gross minus final gross for all
     * effective service items). The per-line breakdown is intentionally not exposed to the customer
     * — only this aggregate figure appears on the card.
     */
    private fun calculateVisitDiscountGross(visit: Visit): Long {
        return visit.serviceItems.sumOf { item ->
            when {
                item.status == VisitServiceStatus.CONFIRMED || item.status == VisitServiceStatus.APPROVED -> {
                    val originalGross = item.vatRate.calculateGrossAmount(item.basePriceNet).amountInCents
                    maxOf(0L, originalGross - item.finalPriceGross.amountInCents)
                }
                item.status == VisitServiceStatus.PENDING && item.pendingOperation == PendingOperation.EDIT -> {
                    val snapshot = item.confirmedSnapshot ?: return@sumOf 0L
                    val originalGross = snapshot.vatRate.calculateGrossAmount(snapshot.basePriceNet).amountInCents
                    maxOf(0L, originalGross - snapshot.finalPriceGross.amountInCents)
                }
                item.status == VisitServiceStatus.PENDING && item.pendingOperation == PendingOperation.DELETE -> {
                    val originalGross = item.vatRate.calculateGrossAmount(item.basePriceNet).amountInCents
                    maxOf(0L, originalGross - item.finalPriceGross.amountInCents)
                }
                else -> 0L
            }
        }
    }

    /**
     * Maps a list of upsell suggestion entities to their customer-facing DTOs, enriching
     * package suggestions with the list of their component services.
     */
    private fun buildUpsellSuggestions(
        suggestions: List<VisitUpsellSuggestionEntity>,
        studioId: UUID
    ): List<VisitCardUpsellSuggestion> {
        if (suggestions.isEmpty()) return emptyList()

        val serviceIds = suggestions.map { it.serviceId }.distinct()
        val serviceMap = serviceRepository.findAllByIdInAndStudioId(serviceIds, studioId)
            .associateBy { it.id }

        val packageServiceIds = serviceIds.filter { serviceMap[it]?.isPackage == true }
        val packageItemsByPackageId = if (packageServiceIds.isNotEmpty()) {
            servicePackageItemRepository.findByPackageIdIn(packageServiceIds)
                .groupBy { it.packageId }
        } else {
            emptyMap()
        }

        return suggestions.map { entity ->
            val isPackage = serviceMap[entity.serviceId]?.isPackage == true
            val items = if (isPackage) {
                packageItemsByPackageId[entity.serviceId]
                    ?.sortedBy { it.position }
                    ?.map { UpsellPackageItemDto(name = it.serviceName) }
            } else null
            entity.toPublicDto(isPackage = isPackage, packageItems = items)
        }
    }
}

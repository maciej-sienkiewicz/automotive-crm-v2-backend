package pl.detailing.crm.checkin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.Appointment
import pl.detailing.crm.appointment.domain.AppointmentLineItem
import pl.detailing.crm.appointment.domain.AppointmentSchedule
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.infrastructure.AppointmentEntity
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.domain.Customer
import pl.detailing.crm.customer.domain.CompanyData
import pl.detailing.crm.customer.domain.CompanyAddress
import pl.detailing.crm.customer.domain.HomeAddress
import pl.detailing.crm.customer.infrastructure.CustomerEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.domain.Vehicle
import pl.detailing.crm.vehicle.domain.VehicleOwner
import pl.detailing.crm.vehicle.infrastructure.VehicleEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerEntity
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.convert.VisitNumberGenerator
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.DamageMapReportService
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.S3DamageMapStorageService
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.checkin.qr.CheckinPhotoService
import pl.detailing.crm.checkin.qr.UploadContextTokenService
import pl.detailing.crm.communication.AppointmentCommunicationLinker
import pl.detailing.crm.visit.domain.VisitPhoto
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.doortodoor.domain.DoorToDoor
import pl.detailing.crm.doortodoor.domain.DoorToDoorAddress
import pl.detailing.crm.doortodoor.domain.DoorToDoorStatus
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorEntity
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorRepository
import pl.detailing.crm.shared.DoorToDoorId
import java.time.Instant

@Service
class CreateVisitFromReservationHandler(
    private val visitNumberGenerator: VisitNumberGenerator,
    private val visitRepository: VisitRepository,
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val damageMapReportService: DamageMapReportService,
    private val s3DamageMapStorageService: S3DamageMapStorageService,
    private val documentService: DocumentService,
    private val serviceRepository: pl.detailing.crm.service.infrastructure.ServiceRepository,
    private val photoSessionService: pl.detailing.crm.visit.infrastructure.PhotoSessionService,
    private val checkinPhotoService: CheckinPhotoService,
    private val uploadContextTokenService: UploadContextTokenService,
    private val auditService: AuditService,
    private val appointmentCommunicationLinker: AppointmentCommunicationLinker,
    private val doorToDoorRepository: DoorToDoorRepository
) {
    @Transactional
    suspend fun handle(command: ReservationToVisitCommand): ReservationToVisitResult =
        withContext(Dispatchers.IO) {
            // Step 1: Load appointment
            val appointment = appointmentRepository.findByIdAndStudioId(
                command.reservationId.value,
                command.studioId.value
            )?.toDomain() ?: throw EntityNotFoundException("Rezerwacja nie została znaleziona")

            // Step 2: Handle customer (create new or use existing)
            val customerId = when (val customerData = command.customer) {
                null -> {
                    // Customer alias only - we'll need to handle this case differently
                    // For now, throw exception as we need full customer data
                    throw ValidationException("Dane klienta są wymagane podczas przyjęcia pojazdu")
                }
                is CustomerData.New -> {
                    // Create new customer
                    createCustomer(customerData, command.studioId, command.userId)
                }
                is CustomerData.Existing -> {
                    // Verify the referenced customer belongs to the caller's studio.
                    // Without this check an attacker could pass a customerId from another
                    // studio and attach a foreign customer to a visit in their own studio.
                    requireCustomerInStudio(customerData.id, command.studioId)
                }
                is CustomerData.Update -> {
                    // Use existing customer and update if data changed
                    updateCustomerIfNeeded(
                        customerData.id,
                        customerData,
                        command.studioId,
                        command.userId
                    )
                    customerData.id
                }
            }

            // Step 3: Handle vehicle (create new or use existing)
            val vehicleId = when (val vehicleData = command.vehicle) {
                is VehicleData.New -> {
                    // Create new vehicle
                    createVehicle(vehicleData, customerId, command.studioId, command.userId)
                }
                is VehicleData.Existing -> {
                    vehicleData.id
                }
                is VehicleData.Update -> {
                    updateVehicleIfNeeded(
                        vehicleData.id,
                        vehicleData,
                        command.studioId,
                        command.userId
                    )
                    vehicleData.id
                }
            }

            // Step 4: Load vehicle for snapshots
            val vehicle = vehicleRepository.findByIdAndStudioId(
                vehicleId.value,
                command.studioId.value
            )?.toDomain() ?: throw EntityNotFoundException("Pojazd nie został znaleziony")

            // Step 5: Generate visit number
            val visitNumber = visitNumberGenerator.generateVisitNumber(command.studioId)

            // Step 6: Map services to visit service items
            val serviceItems = command.services.map { serviceReq ->
                val adjustmentType = AdjustmentType.valueOf(serviceReq.adjustment.type)

                // Convert adjustment value based on type:
                // - For PERCENT: validate non-negative and convert using semantic convention
                //   (0–100 = discount, >100 = markup) to basis points
                // - For others: round to Long (cents)
                val adjustmentValue = when (adjustmentType) {
                    AdjustmentType.PERCENT -> AdjustmentType.convertPercentValueToBasisPoints(serviceReq.adjustment.value)
                    else -> serviceReq.adjustment.value.toLong() // Keep in cents
                }

                // Calculate prices using AppointmentLineItem logic
                val lineItem = AppointmentLineItem.create(
                    serviceId = serviceReq.serviceId?.let { ServiceId.fromString(it) },
                    serviceName = serviceReq.serviceName,
                    basePriceNet = Money.fromCents(serviceReq.basePriceNet),
                    vatRate = VatRate.fromInt(serviceReq.vatRate),
                    adjustmentType = adjustmentType,
                    adjustmentValue = adjustmentValue,
                    customNote = serviceReq.note,
                    basePriceGross = serviceReq.basePriceGross?.let { Money.fromCents(it) }
                )

                VisitServiceItem(
                    id = VisitServiceItemId.random(),
                    serviceId = lineItem.serviceId,
                    serviceName = lineItem.serviceName,
                    basePriceNet = lineItem.basePriceNet,
                    vatRate = lineItem.vatRate,
                    adjustmentType = lineItem.adjustmentType,
                    adjustmentValue = lineItem.adjustmentValue,
                    finalPriceNet = lineItem.finalPriceNet,
                    finalPriceGross = lineItem.finalPriceGross,
                    status = VisitServiceStatus.CONFIRMED,
                    pendingOperation = null,
                    confirmedSnapshot = null,
                    customNote = lineItem.customNote,
                    createdAt = Instant.now(),
                    confirmedAt = Instant.now(),
                    pendingAt = null
                )
            }

            // Step 7: Create Visit domain object
            val visitId = VisitId.random()

            // Step 7.5: Process photos from upload session
            val visitPhotos = if (command.photoIds.isNotEmpty()) {
                val photoUUIDs = command.photoIds.mapNotNull {
                    try {
                        java.util.UUID.fromString(it)
                    } catch (e: Exception) {
                        null
                    }
                }

                if (photoUUIDs.isNotEmpty()) {
                    val claimedPhotos = photoSessionService.claimPhotosForVisit(
                        photoIds = photoUUIDs,
                        visitId = visitId,
                        studioId = command.studioId
                    )

                    claimedPhotos.map { claimed ->
                        pl.detailing.crm.visit.domain.VisitPhoto(
                            id = VisitPhotoId(claimed.id),
                            fileId = claimed.fileId,
                            fileName = claimed.fileName,
                            description = null,
                            uploadedAt = Instant.now()
                        )
                    }
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }

            // Step 7.6: Finalize QR-uploaded photos (moved from temp/uploads/ to final visit location)
            val qrCheckinTenantId = command.studioId.value.toString()
            val qrCheckinId = command.reservationId.value.toString()
            val qrPhotos = try {
                checkinPhotoService.finalizePhotos(
                    tenantId = qrCheckinTenantId,
                    checkinId = qrCheckinId,
                    visitId = visitId
                ).map { finalized ->
                    VisitPhoto(
                        id = VisitPhotoId(finalized.photoId),
                        fileId = finalized.fileId,
                        fileName = finalized.fileName,
                        description = null,
                        uploadedAt = Instant.now()
                    )
                }
            } catch (e: Exception) {
                // Do not abort visit creation if QR photo finalization fails
                println("Warning: Failed to finalize QR photos for checkin ${command.reservationId.value}: ${e.message}")
                emptyList()
            }

            // Revoke mobile upload token so the phone shows the "visit created" screen
            try {
                uploadContextTokenService.markVisitCreated(qrCheckinTenantId, qrCheckinId)
            } catch (e: Exception) {
                println("Warning: Failed to revoke mobile upload token for checkin $qrCheckinId: ${e.message}")
            }

            val allPhotos = visitPhotos + qrPhotos

            var visit = Visit(
                id = visitId,
                studioId = command.studioId,
                visitNumber = visitNumber,
                customerId = customerId,
                vehicleId = vehicleId,
                appointmentId = appointment.id,
                appointmentColorId = command.appointmentColorId,
                title = command.title ?: appointment.appointmentTitle,
                // Immutable vehicle snapshots
                brandSnapshot = vehicle.brand,
                modelSnapshot = vehicle.model,
                licensePlateSnapshot = vehicle.licensePlate,
                vinSnapshot = null,
                yearOfProductionSnapshot = vehicle.yearOfProduction,
                colorSnapshot = vehicle.color,
                // Visit details
                status = VisitStatus.DRAFT,  // Start in DRAFT - will be confirmed after document signing
                scheduledDate = appointment.schedule.startDateTime,
                estimatedCompletionDate = appointment.schedule.endDateTime,
                actualCompletionDate = null,
                pickupDate = null,
                mileageAtArrival = command.technicalState.mileage,
                keysHandedOver = command.technicalState.deposit.keys,
                documentsHandedOver = command.technicalState.deposit.registrationDocument,
                inspectionNotes = command.technicalState.inspectionNotes.ifBlank { null },
                technicalNotes = command.technicalState.protocolNotes.ifBlank { null },
                vehicleHandoff = command.vehicleHandoff,
                serviceItems = serviceItems,
                photos = allPhotos,  // session-based photos + QR-uploaded photos
                damageMapFileId = null, // Will be set after generating damage map
                smsReminderSuppressed = false,
                // Audit
                createdBy = command.userId,
                updatedBy = command.userId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            // Step 8: Generate and upload damage map PDF if damage points are provided
            if (command.damagePoints.isNotEmpty()) {
                try {
                    val damageMapBytes = damageMapReportService.generateReport(command.damagePoints)

                    if (damageMapBytes != null) {
                        val damageMapFileId = s3DamageMapStorageService.uploadDamageMap(
                            studioId = command.studioId.value,
                            visitId = visitId.value,
                            pdfBytes = damageMapBytes
                        )
                        visit = visit.withDamageMap(damageMapFileId, command.userId)
                    }
                } catch (e: Exception) {
                    println("Warning: Failed to generate damage map: ${e.message}")
                }
            }

            // Step 9: Persist Visit entity (must be done before registering documents)
            visit = persistVisitRetryingOnDuplicateNumber(visit, command.studioId)

            // Step 9.1: Retroactively link pre-visit SMS (booking confirmation, reminder) to this visit.
            // Those entries were recorded with visitId = null because the visit did not exist yet.
            appointmentCommunicationLinker.linkToVisit(appointment.id, visitId, command.studioId)

            // Step 9.3: Log visit creation on the vehicle's audit trail
            val vehicleDisplayName = "${vehicle.brand} ${vehicle.model}" +
                (vehicle.licensePlate?.let { " ($it)" } ?: "")
            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName,
                module = AuditModule.VEHICLE,
                entityId = vehicleId.value.toString(),
                entityDisplayName = vehicleDisplayName,
                action = AuditAction.VISIT_ADDED,
                metadata = mapOf(
                    "visitId" to visitId.value.toString(),
                    "visitNumber" to visit.visitNumber,
                    "appointmentId" to appointment.id.value.toString()
                )
            ))

            // Step 9.4: Register damage map as a document if it was generated
            if (command.damagePoints.isNotEmpty() && visit.damageMapFileId != null) {
                try {
                    documentService.registerDocument(
                        visitId = visitId.value,
                        customerId = customerId.value,
                        documentType = DocumentType.DAMAGE_MAP,
                        name = "Damage Map - ${visit.visitNumber}",
                        s3Key = visit.damageMapFileId!!,
                        fileName = "damage-map.pdf",
                        createdBy = command.userId.value,
                        createdByName = command.userName,
                        category = "damage"
                    )
                } catch (e: Exception) {
                    println("Warning: Failed to register damage map document: ${e.message}")
                }
            }

            // Step 10: DO NOT update appointment status yet
            // Appointment will be marked as CONVERTED only when visit is confirmed (after documents are signed)
            // This allows users to cancel the draft visit and return to reservation

            // Step 11: Persist Door to Door if provided
            command.doorToDoor?.let { d2d ->
                persistDoorToDoor(visitId, command.studioId, command.userId, d2d)
            }

            // Step 12: Return result
            ReservationToVisitResult(visitId = visitId)
        }

    @Transactional
    suspend fun handleWalkIn(command: WalkInVisitCommand): ReservationToVisitResult =
        withContext(Dispatchers.IO) {
            // Step 1: Handle customer
            val customerId = when (val customerData = command.customer) {
                null -> throw ValidationException("Dane klienta są wymagane podczas przyjęcia pojazdu")
                is CustomerData.New -> createCustomer(customerData, command.studioId, command.userId)
                is CustomerData.Existing -> requireCustomerInStudio(customerData.id, command.studioId)
                is CustomerData.Update -> {
                    updateCustomerIfNeeded(customerData.id, customerData, command.studioId, command.userId)
                    customerData.id
                }
            }

            // Step 2: Handle vehicle
            val vehicleId = when (val vehicleData = command.vehicle) {
                is VehicleData.New -> createVehicle(vehicleData, customerId, command.studioId, command.userId)
                is VehicleData.Existing -> vehicleData.id
                is VehicleData.Update -> {
                    updateVehicleIfNeeded(vehicleData.id, vehicleData, command.studioId, command.userId)
                    vehicleData.id
                }
            }

            // Step 3: Create walk-in appointment (shadow appointment without prior reservation)
            val appointment = createWalkInAppointment(command, customerId, vehicleId)

            // Step 4: Load vehicle for snapshots
            val vehicle = vehicleRepository.findByIdAndStudioId(
                vehicleId.value,
                command.studioId.value
            )?.toDomain() ?: throw EntityNotFoundException("Pojazd nie został znaleziony")

            // Step 5: Generate visit number
            val visitNumber = visitNumberGenerator.generateVisitNumber(command.studioId)

            // Step 6: Map services to visit service items
            val serviceItems = command.services.map { serviceReq ->
                val adjustmentType = AdjustmentType.valueOf(serviceReq.adjustment.type)
                val adjustmentValue = when (adjustmentType) {
                    AdjustmentType.PERCENT -> AdjustmentType.convertPercentValueToBasisPoints(serviceReq.adjustment.value)
                    else -> serviceReq.adjustment.value.toLong()
                }
                val lineItem = AppointmentLineItem.create(
                    serviceId = serviceReq.serviceId?.let { ServiceId.fromString(it) },
                    serviceName = serviceReq.serviceName,
                    basePriceNet = Money.fromCents(serviceReq.basePriceNet),
                    vatRate = VatRate.fromInt(serviceReq.vatRate),
                    adjustmentType = adjustmentType,
                    adjustmentValue = adjustmentValue,
                    customNote = serviceReq.note,
                    basePriceGross = serviceReq.basePriceGross?.let { Money.fromCents(it) }
                )
                VisitServiceItem(
                    id = VisitServiceItemId.random(),
                    serviceId = lineItem.serviceId,
                    serviceName = lineItem.serviceName,
                    basePriceNet = lineItem.basePriceNet,
                    vatRate = lineItem.vatRate,
                    adjustmentType = lineItem.adjustmentType,
                    adjustmentValue = lineItem.adjustmentValue,
                    finalPriceNet = lineItem.finalPriceNet,
                    finalPriceGross = lineItem.finalPriceGross,
                    status = VisitServiceStatus.CONFIRMED,
                    pendingOperation = null,
                    confirmedSnapshot = null,
                    customNote = lineItem.customNote,
                    createdAt = Instant.now(),
                    confirmedAt = Instant.now(),
                    pendingAt = null
                )
            }

            // Step 7: Process session-based photos
            val visitId = VisitId.random()
            val visitPhotos = if (command.photoIds.isNotEmpty()) {
                val photoUUIDs = command.photoIds.mapNotNull {
                    try { java.util.UUID.fromString(it) } catch (e: Exception) { null }
                }
                if (photoUUIDs.isNotEmpty()) {
                    photoSessionService.claimPhotosForVisit(
                        photoIds = photoUUIDs,
                        visitId = visitId,
                        studioId = command.studioId
                    ).map { claimed ->
                        pl.detailing.crm.visit.domain.VisitPhoto(
                            id = VisitPhotoId(claimed.id),
                            fileId = claimed.fileId,
                            fileName = claimed.fileName,
                            description = null,
                            uploadedAt = Instant.now()
                        )
                    }
                } else emptyList()
            } else emptyList()

            // Step 7.5: Finalize QR-uploaded photos for walk-in (moved from temp/uploads/ to final visit location)
            val walkInTenantId = command.studioId.value.toString()
            val qrPhotos = command.qrCheckinId?.let { qrId ->
                val photos = try {
                    checkinPhotoService.finalizePhotos(
                        tenantId = walkInTenantId,
                        checkinId = qrId,
                        visitId = visitId
                    ).map { finalized ->
                        VisitPhoto(
                            id = VisitPhotoId(finalized.photoId),
                            fileId = finalized.fileId,
                            fileName = finalized.fileName,
                            description = null,
                            uploadedAt = Instant.now()
                        )
                    }
                } catch (e: Exception) {
                    println("Warning: Failed to finalize QR photos for walk-in checkin $qrId: ${e.message}")
                    emptyList()
                }
                // Revoke mobile upload token so the phone shows the "visit created" screen
                try {
                    uploadContextTokenService.markVisitCreated(walkInTenantId, qrId)
                } catch (e: Exception) {
                    println("Warning: Failed to revoke mobile upload token for walk-in checkin $qrId: ${e.message}")
                }
                photos
            } ?: emptyList()

            // Step 8: Create Visit domain object
            var visit = Visit(
                id = visitId,
                studioId = command.studioId,
                visitNumber = visitNumber,
                customerId = customerId,
                vehicleId = vehicleId,
                appointmentId = appointment.id,
                appointmentColorId = command.appointmentColorId,
                title = command.title,
                brandSnapshot = vehicle.brand,
                modelSnapshot = vehicle.model,
                licensePlateSnapshot = vehicle.licensePlate,
                vinSnapshot = null,
                yearOfProductionSnapshot = vehicle.yearOfProduction,
                colorSnapshot = vehicle.color,
                status = VisitStatus.DRAFT,
                scheduledDate = command.startDateTime,
                estimatedCompletionDate = command.endDateTime,
                actualCompletionDate = null,
                pickupDate = null,
                mileageAtArrival = command.technicalState.mileage,
                keysHandedOver = command.technicalState.deposit.keys,
                documentsHandedOver = command.technicalState.deposit.registrationDocument,
                inspectionNotes = command.technicalState.inspectionNotes.ifBlank { null },
                technicalNotes = command.technicalState.protocolNotes.ifBlank { null },
                vehicleHandoff = command.vehicleHandoff,
                serviceItems = serviceItems,
                photos = visitPhotos + qrPhotos,
                damageMapFileId = null,
                smsReminderSuppressed = false,
                createdBy = command.userId,
                updatedBy = command.userId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )

            // Step 9: Generate damage map PDF if damage points are provided
            if (command.damagePoints.isNotEmpty()) {
                try {
                    val damageMapBytes = damageMapReportService.generateReport(command.damagePoints)
                    if (damageMapBytes != null) {
                        val damageMapFileId = s3DamageMapStorageService.uploadDamageMap(
                            studioId = command.studioId.value,
                            visitId = visitId.value,
                            pdfBytes = damageMapBytes
                        )
                        visit = visit.withDamageMap(damageMapFileId, command.userId)
                    }
                } catch (e: Exception) {
                    println("Warning: Failed to generate damage map for walk-in: ${e.message}")
                }
            }

            // Step 10: Persist visit
            visit = persistVisitRetryingOnDuplicateNumber(visit, command.studioId)

            // Step 10.1: Audit log
            val vehicleDisplayName = "${vehicle.brand} ${vehicle.model}" +
                (vehicle.licensePlate?.let { " ($it)" } ?: "")
            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName,
                module = AuditModule.VEHICLE,
                entityId = vehicleId.value.toString(),
                entityDisplayName = vehicleDisplayName,
                action = AuditAction.VISIT_ADDED,
                metadata = mapOf(
                    "visitId" to visitId.value.toString(),
                    "visitNumber" to visit.visitNumber,
                    "appointmentId" to appointment.id.value.toString(),
                    "walkIn" to "true"
                )
            ))

            // Step 10.2: Register damage map document if generated
            if (command.damagePoints.isNotEmpty() && visit.damageMapFileId != null) {
                try {
                    documentService.registerDocument(
                        visitId = visitId.value,
                        customerId = customerId.value,
                        documentType = DocumentType.DAMAGE_MAP,
                        name = "Damage Map - ${visit.visitNumber}",
                        s3Key = visit.damageMapFileId!!,
                        fileName = "damage-map.pdf",
                        createdBy = command.userId.value,
                        createdByName = command.userName,
                        category = "damage"
                    )
                } catch (e: Exception) {
                    println("Warning: Failed to register damage map document for walk-in: ${e.message}")
                }
            }

            // Step 11: Persist Door to Door if provided
            command.doorToDoor?.let { d2d ->
                persistDoorToDoor(visitId, command.studioId, command.userId, d2d)
            }

            ReservationToVisitResult(visitId = visitId)
        }

    /**
     * Persists the visit, retrying with a freshly generated visit number when the
     * unique (studio_id, visit_number) index is violated. The number generator uses
     * read-max+1 without any lock, so two concurrent check-ins in the same studio can
     * draw the same number — without the retry the second check-in fails with a
     * PSQLException on idx_visits_visit_number.
     */
    private fun persistVisitRetryingOnDuplicateNumber(visit: Visit, studioId: StudioId): Visit {
        var current = visit
        var attempts = 0
        while (true) {
            try {
                visitRepository.saveAndFlush(VisitEntity.fromDomain(current))
                return current
            } catch (e: DataIntegrityViolationException) {
                attempts++
                if (attempts >= 3) throw e
                current = current.copy(
                    visitNumber = visitNumberGenerator.generateVisitNumber(studioId)
                )
            }
        }
    }

    private fun createWalkInAppointment(
        command: WalkInVisitCommand,
        customerId: CustomerId,
        vehicleId: VehicleId
    ): Appointment {
        val lineItems = command.services.map { serviceReq ->
            val adjustmentType = AdjustmentType.valueOf(serviceReq.adjustment.type)
            val adjustmentValue = when (adjustmentType) {
                AdjustmentType.PERCENT -> AdjustmentType.convertPercentValueToBasisPoints(serviceReq.adjustment.value)
                else -> serviceReq.adjustment.value.toLong()
            }
            AppointmentLineItem.create(
                serviceId = serviceReq.serviceId?.let { ServiceId.fromString(it) },
                serviceName = serviceReq.serviceName,
                basePriceNet = Money.fromCents(serviceReq.basePriceNet),
                vatRate = VatRate.fromInt(serviceReq.vatRate),
                adjustmentType = adjustmentType,
                adjustmentValue = adjustmentValue,
                customNote = serviceReq.note,
                basePriceGross = serviceReq.basePriceGross?.let { Money.fromCents(it) }
            )
        }

        val colorId = command.appointmentColorId
            ?: throw ValidationException("appointmentColorId jest wymagany dla wizyty walk-in")

        val appointment = Appointment(
            id = AppointmentId.random(),
            studioId = command.studioId,
            customerId = customerId,
            vehicleId = vehicleId,
            appointmentTitle = command.title,
            appointmentColorId = colorId,
            lineItems = lineItems,
            schedule = AppointmentSchedule(
                isAllDay = false,
                startDateTime = command.startDateTime,
                endDateTime = command.endDateTime
            ),
            status = AppointmentStatus.CREATED,
            note = null,
            createdBy = command.userId,
            updatedBy = command.userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        appointmentRepository.save(AppointmentEntity.fromDomain(appointment))
        return appointment
    }

    /**
     * Resolves an existing customer's ID only after confirming it belongs to [studioId].
     * Throws [EntityNotFoundException] (mapped to 404) otherwise, so a cross-tenant ID is
     * indistinguishable from a non-existent one and never gets attached to a visit.
     */
    private fun requireCustomerInStudio(customerId: CustomerId, studioId: StudioId): CustomerId {
        customerRepository.findByIdAndStudioId(customerId.value, studioId.value)
            ?: throw EntityNotFoundException("Klient nie został znaleziony")
        return customerId
    }

    private fun createCustomer(
        customerData: CustomerData,
        studioId: StudioId,
        userId: UserId
    ): CustomerId {
        val firstName: String
        val lastName: String
        val email: String?
        val phone: String?
        val homeAddress: HomeAddressRequest?
        val company: CompanyRequest?

        when (customerData) {
            is CustomerData.New -> {
                firstName = customerData.firstName
                lastName = customerData.lastName
                email = customerData.email
                phone = customerData.phone
                homeAddress = customerData.homeAddress
                company = customerData.company
            }
            is CustomerData.Update -> {
                firstName = customerData.firstName
                lastName = customerData.lastName
                email = customerData.email
                phone = customerData.phone
                homeAddress = customerData.homeAddress
                company = customerData.company
            }
            else -> throw IllegalArgumentException("Cannot create customer from Existing state")
        }

        val customer = Customer(
            id = CustomerId.random(),
            studioId = studioId,
            firstName = firstName.trim(),
            lastName = lastName.trim(),
            email = email?.trim()?.lowercase()?.ifBlank { null },
            phone = phone?.trim()?.ifBlank { null },
            homeAddress = homeAddress?.let {
                HomeAddress(
                    street = it.street,
                    city = it.city,
                    postalCode = it.postalCode,
                    country = it.country
                )
            },
            companyData = company?.let {
                CompanyData(
                    name = it.name,
                    nip = it.nip,
                    regon = it.regon,
                    address = CompanyAddress(
                        street = it.address.street,
                        city = it.address.city,
                        postalCode = it.address.postalCode,
                        country = it.address.country
                    )
                )
            },
            isActive = true,
            createdBy = userId,
            updatedBy = userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val entity = CustomerEntity.fromDomain(customer)
        customerRepository.save(entity)

        return customer.id
    }

    private fun createVehicle(
        vehicleData: VehicleData.New,
        customerId: CustomerId,
        studioId: StudioId,
        userId: UserId
    ): VehicleId {
        val vehicle = Vehicle(
            id = VehicleId.random(),
            studioId = studioId,
            licensePlate = vehicleData.licensePlate?.trim()?.uppercase(),
            brand = vehicleData.brand.trim(),
            model = vehicleData.model.trim(),
            yearOfProduction = vehicleData.yearOfProduction,
            color = vehicleData.color?.trim(),
            paintType = vehicleData.paintType?.trim(),
            currentMileage = 0, // Will be set from mileageAtArrival
            status = VehicleStatus.ACTIVE,
            createdBy = userId,
            updatedBy = userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val vehicleEntity = VehicleEntity.fromDomain(vehicle)
        vehicleRepository.save(vehicleEntity)

        // Link vehicle to customer
        val vehicleOwner = VehicleOwner(
            vehicleId = vehicle.id,
            customerId = customerId,
            ownershipRole = OwnershipRole.PRIMARY,
            assignedAt = Instant.now()
        )

        val vehicleOwnerEntity = VehicleOwnerEntity.fromDomain(vehicleOwner)
        vehicleOwnerRepository.save(vehicleOwnerEntity)

        return vehicle.id
    }

    private fun updateCustomerIfNeeded(
        customerId: CustomerId,
        customerData: CustomerData.Update,
        studioId: StudioId,
        userId: UserId
    ) {
        val existingEntity = customerRepository.findByIdAndStudioId(customerId.value, studioId.value)
            ?: throw EntityNotFoundException("Klient nie został znaleziony")

        // Check if any data has changed
        val newFirstName = customerData.firstName.trim()
        val newLastName = customerData.lastName.trim()
        val newEmail = customerData.email?.trim()?.lowercase()?.ifBlank { null }
        val newPhone = customerData.phone?.trim()?.ifBlank { null }

        val newHomeAddress = customerData.homeAddress
        val newCompany = customerData.company

        val hasBasicDataChanged = existingEntity.firstName != newFirstName ||
            existingEntity.lastName != newLastName ||
            existingEntity.email != newEmail ||
            existingEntity.phone != newPhone

        val hasHomeAddressChanged = when {
            newHomeAddress == null && existingEntity.homeAddressStreet == null -> false
            newHomeAddress == null -> true // Was set, now null
            existingEntity.homeAddressStreet == null -> true // Was null, now set
            else -> existingEntity.homeAddressStreet != newHomeAddress.street ||
                existingEntity.homeAddressCity != newHomeAddress.city ||
                existingEntity.homeAddressPostalCode != newHomeAddress.postalCode ||
                existingEntity.homeAddressCountry != newHomeAddress.country
        }

        val hasCompanyDataChanged = when {
            newCompany == null && existingEntity.companyName == null -> false
            newCompany == null -> true // Was set, now null
            existingEntity.companyName == null -> true // Was null, now set
            else -> existingEntity.companyName != newCompany.name ||
                existingEntity.companyNip != newCompany.nip ||
                existingEntity.companyRegon != newCompany.regon ||
                existingEntity.companyAddressStreet != newCompany.address.street ||
                existingEntity.companyAddressCity != newCompany.address.city ||
                existingEntity.companyAddressPostalCode != newCompany.address.postalCode ||
                existingEntity.companyAddressCountry != newCompany.address.country
        }

        if (hasBasicDataChanged || hasHomeAddressChanged || hasCompanyDataChanged) {
            // Update the entity
            existingEntity.firstName = newFirstName
            existingEntity.lastName = newLastName
            existingEntity.email = newEmail
            existingEntity.phone = newPhone

            // Update home address
            if (newHomeAddress != null) {
                existingEntity.homeAddressStreet = newHomeAddress.street
                existingEntity.homeAddressCity = newHomeAddress.city
                existingEntity.homeAddressPostalCode = newHomeAddress.postalCode
                existingEntity.homeAddressCountry = newHomeAddress.country
            } else {
                existingEntity.homeAddressStreet = null
                existingEntity.homeAddressCity = null
                existingEntity.homeAddressPostalCode = null
                existingEntity.homeAddressCountry = null
            }

            // Update company data
            if (newCompany != null) {
                existingEntity.companyName = newCompany.name
                existingEntity.companyNip = newCompany.nip
                existingEntity.companyRegon = newCompany.regon
                existingEntity.companyAddressStreet = newCompany.address.street
                existingEntity.companyAddressCity = newCompany.address.city
                existingEntity.companyAddressPostalCode = newCompany.address.postalCode
                existingEntity.companyAddressCountry = newCompany.address.country
            } else {
                existingEntity.companyName = null
                existingEntity.companyNip = null
                existingEntity.companyRegon = null
                existingEntity.companyAddressStreet = null
                existingEntity.companyAddressCity = null
                existingEntity.companyAddressPostalCode = null
                existingEntity.companyAddressCountry = null
            }

            existingEntity.updatedBy = userId.value
            existingEntity.updatedAt = Instant.now()

            customerRepository.save(existingEntity)
        }
    }

    private fun updateVehicleIfNeeded(
        vehicleId: VehicleId,
        vehicleData: VehicleData.Update,
        studioId: StudioId,
        userId: UserId
    ) {
        val existingEntity = vehicleRepository.findByIdAndStudioId(vehicleId.value, studioId.value)
            ?: throw EntityNotFoundException("Pojazd nie został znaleziony")

        val hasChanged = existingEntity.brand != vehicleData.brand.trim() ||
            existingEntity.model != vehicleData.model.trim() ||
            existingEntity.yearOfProduction != vehicleData.yearOfProduction ||
            existingEntity.licensePlate != vehicleData.licensePlate?.trim()?.uppercase() ||
            existingEntity.color != vehicleData.color?.trim() ||
            existingEntity.paintType != vehicleData.paintType?.trim()

        if (hasChanged) {
            existingEntity.brand = vehicleData.brand.trim()
            existingEntity.model = vehicleData.model.trim()
            existingEntity.yearOfProduction = vehicleData.yearOfProduction
            existingEntity.licensePlate = vehicleData.licensePlate?.trim()?.uppercase()
            existingEntity.color = vehicleData.color?.trim()
            existingEntity.paintType = vehicleData.paintType?.trim()
            existingEntity.updatedBy = userId.value
            existingEntity.updatedAt = Instant.now()
            vehicleRepository.save(existingEntity)
        }
    }

    private fun persistDoorToDoor(
        visitId: VisitId,
        studioId: StudioId,
        userId: UserId,
        d2d: DoorToDoorCheckinRequest
    ) {
        val now = Instant.now()
        val entity = DoorToDoorEntity.fromDomain(
            DoorToDoor(
                id = DoorToDoorId.random(),
                studioId = studioId,
                visitId = visitId,
                pickupAddress = DoorToDoorAddress(city = d2d.pickupCity, street = d2d.pickupStreet),
                deliveryAddress = DoorToDoorAddress(city = d2d.deliveryCity, street = d2d.deliveryStreet),
                notes = d2d.notes,
                status = DoorToDoorStatus.SCHEDULED,
                createdBy = userId,
                updatedBy = userId,
                createdAt = now,
                updatedAt = now
            )
        )
        doorToDoorRepository.save(entity)
    }
}

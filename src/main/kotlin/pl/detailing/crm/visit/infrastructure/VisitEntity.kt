package pl.detailing.crm.visit.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitPhoto
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.domain.VisitJournalEntry
import pl.detailing.crm.visit.domain.VisitDocument
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "visits",
    indexes = [
        Index(name = "idx_visits_studio_id", columnList = "studio_id"),
        Index(name = "idx_visits_studio_customer", columnList = "studio_id, customer_id"),
        Index(name = "idx_visits_studio_vehicle", columnList = "studio_id, vehicle_id"),
        Index(name = "idx_visits_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_visits_studio_scheduled", columnList = "studio_id, scheduled_date"),
        Index(name = "idx_visits_appointment_id", columnList = "appointment_id"),
        Index(name = "idx_visits_visit_number", columnList = "studio_id, visit_number", unique = true),
        Index(name = "idx_visits_created_by", columnList = "created_by"),
        Index(name = "idx_visits_updated_by", columnList = "updated_by")
    ]
)
class VisitEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "visit_number", nullable = false, length = 50)
    val visitNumber: String,

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    val customerId: UUID,

    @Column(name = "vehicle_id", nullable = false, columnDefinition = "uuid")
    val vehicleId: UUID,

    @Column(name = "appointment_id", nullable = false, columnDefinition = "uuid")
    val appointmentId: UUID,

    @Column(name = "appointment_color_id", columnDefinition = "uuid")
    val appointmentColorId: UUID?,

    // Immutable vehicle snapshots - frozen at visit creation
    @Column(name = "brand_snapshot", nullable = false, length = 100)
    val brandSnapshot: String,

    @Column(name = "model_snapshot", nullable = false, length = 100)
    val modelSnapshot: String,

    @Column(name = "license_plate_snapshot", nullable = true, length = 20)
    val licensePlateSnapshot: String?,

    @Column(name = "vin_snapshot", length = 17)
    val vinSnapshot: String?,

    @Column(name = "year_of_production_snapshot", nullable = true)
    val yearOfProductionSnapshot: Int?,

    @Column(name = "color_snapshot", length = 50)
    val colorSnapshot: String?,

    // Visit status and dates
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    var status: VisitStatus,

    @Column(name = "scheduled_date", nullable = false, columnDefinition = "timestamp with time zone")
    val scheduledDate: Instant,

    @Column(name = "estimated_completion_date", columnDefinition = "timestamp with time zone")
    var estimatedCompletionDate: Instant?,

    @Column(name = "actual_completion_date", columnDefinition = "timestamp with time zone")
    var actualCompletionDate: Instant?,

    @Column(name = "pickup_date", columnDefinition = "timestamp with time zone")
    var pickupDate: Instant?,

    // Arrival details
    @Column(name = "mileage_at_arrival")
    var mileageAtArrival: Long?,

    @Column(name = "keys_handed_over", nullable = false)
    var keysHandedOver: Boolean,

    @Column(name = "documents_handed_over", nullable = false)
    var documentsHandedOver: Boolean,

    @Column(name = "inspection_notes", columnDefinition = "TEXT")
    var inspectionNotes: String?,

    @Column(name = "technical_notes", columnDefinition = "TEXT")
    var technicalNotes: String?,

    // Service items (one-to-many)
    @OneToMany(mappedBy = "visit", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var serviceItems: MutableList<VisitServiceItemEntity> = mutableListOf(),

    // Photos (one-to-many)
    @OneToMany(mappedBy = "visit", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var photos: MutableList<VisitPhotoEntity> = mutableListOf(),

    // Damage map (S3 file ID for the generated damage map image)
    @Column(name = "damage_map_file_id", length = 500)
    var damageMapFileId: String?,

    // Audit fields
    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): Visit = Visit(
        id = VisitId(id),
        studioId = StudioId(studioId),
        visitNumber = visitNumber,
        customerId = CustomerId(customerId),
        vehicleId = VehicleId(vehicleId),
        appointmentId = AppointmentId(appointmentId),
        appointmentColorId = appointmentColorId?.let { AppointmentColorId(it) },
        brandSnapshot = brandSnapshot,
        modelSnapshot = modelSnapshot,
        licensePlateSnapshot = licensePlateSnapshot,
        vinSnapshot = vinSnapshot,
        yearOfProductionSnapshot = yearOfProductionSnapshot,
        colorSnapshot = colorSnapshot,
        status = status,
        scheduledDate = scheduledDate,
        estimatedCompletionDate = estimatedCompletionDate,
        actualCompletionDate = actualCompletionDate,
        pickupDate = pickupDate,
        mileageAtArrival = mileageAtArrival,
        keysHandedOver = keysHandedOver,
        documentsHandedOver = documentsHandedOver,
        inspectionNotes = inspectionNotes,
        technicalNotes = technicalNotes,
        serviceItems = serviceItems.map { it.toDomain() },
        photos = photos.map { it.toDomain() },
        damageMapFileId = damageMapFileId,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(visit: Visit): VisitEntity {
            val entity = VisitEntity(
                id = visit.id.value,
                studioId = visit.studioId.value,
                visitNumber = visit.visitNumber,
                customerId = visit.customerId.value,
                vehicleId = visit.vehicleId.value,
                appointmentId = visit.appointmentId.value,
                appointmentColorId = visit.appointmentColorId?.value,
                brandSnapshot = visit.brandSnapshot,
                modelSnapshot = visit.modelSnapshot,
                licensePlateSnapshot = visit.licensePlateSnapshot,
                vinSnapshot = visit.vinSnapshot,
                yearOfProductionSnapshot = visit.yearOfProductionSnapshot,
                colorSnapshot = visit.colorSnapshot,
                status = visit.status,
                scheduledDate = visit.scheduledDate,
                estimatedCompletionDate = visit.estimatedCompletionDate,
                actualCompletionDate = visit.actualCompletionDate,
                pickupDate = visit.pickupDate,
                mileageAtArrival = visit.mileageAtArrival,
                keysHandedOver = visit.keysHandedOver,
                documentsHandedOver = visit.documentsHandedOver,
                inspectionNotes = visit.inspectionNotes,
                technicalNotes = visit.technicalNotes,
                damageMapFileId = visit.damageMapFileId,
                createdBy = visit.createdBy.value,
                updatedBy = visit.updatedBy.value,
                createdAt = visit.createdAt,
                updatedAt = visit.updatedAt
            )

            // Map service items with bidirectional relationship
            entity.serviceItems = visit.serviceItems.map { serviceItem ->
                VisitServiceItemEntity.fromDomain(serviceItem, entity)
            }.toMutableList()

            // Map photos with bidirectional relationship
            entity.photos = visit.photos.map { photo ->
                VisitPhotoEntity.fromDomain(photo, entity)
            }.toMutableList()

            return entity
        }
    }
}

@Entity
@Table(
    name = "visit_service_items",
    indexes = [
        Index(name = "idx_visit_service_items_visit_id", columnList = "visit_id"),
        Index(name = "idx_visit_service_items_service_id", columnList = "service_id"),
        Index(name = "idx_visit_service_items_status", columnList = "visit_id, status")
    ]
)
class VisitServiceItemEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    var visit: VisitEntity,

    @Column(name = "service_id", nullable = false, columnDefinition = "uuid")
    val serviceId: UUID,

    @Column(name = "service_name", nullable = false, length = 255)
    val serviceName: String,

    @Column(name = "base_price_net", nullable = false)
    val basePriceNet: Long,

    @Column(name = "vat_rate", nullable = false)
    val vatRate: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 50)
    val adjustmentType: AdjustmentType,

    @Column(name = "adjustment_value", nullable = false)
    val adjustmentValue: Long,

    @Column(name = "final_price_net", nullable = false)
    val finalPriceNet: Long,

    @Column(name = "final_price_gross", nullable = false)
    val finalPriceGross: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    var status: VisitServiceStatus,

    @Column(name = "custom_note", columnDefinition = "TEXT")
    var customNote: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(): VisitServiceItem = VisitServiceItem(
        id = VisitServiceItemId(id),
        serviceId = ServiceId(serviceId),
        serviceName = serviceName,
        basePriceNet = Money(basePriceNet),
        vatRate = VatRate.fromInt(vatRate),
        adjustmentType = adjustmentType,
        adjustmentValue = adjustmentValue,
        finalPriceNet = Money(finalPriceNet),
        finalPriceGross = Money(finalPriceGross),
        status = status,
        customNote = customNote,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(serviceItem: VisitServiceItem, visit: VisitEntity): VisitServiceItemEntity =
            VisitServiceItemEntity(
                id = serviceItem.id.value,
                visit = visit,
                serviceId = serviceItem.serviceId.value,
                serviceName = serviceItem.serviceName,
                basePriceNet = serviceItem.basePriceNet.amountInCents,
                vatRate = serviceItem.vatRate.rate,
                adjustmentType = serviceItem.adjustmentType,
                adjustmentValue = serviceItem.adjustmentValue,
                finalPriceNet = serviceItem.finalPriceNet.amountInCents,
                finalPriceGross = serviceItem.finalPriceGross.amountInCents,
                status = serviceItem.status,
                customNote = serviceItem.customNote,
                createdAt = serviceItem.createdAt
            )
    }
}

@Entity
@Table(
    name = "visit_photos",
    indexes = [
        Index(name = "idx_visit_photos_visit_id", columnList = "visit_id"),
        Index(name = "idx_visit_photos_photo_type", columnList = "photo_type")
    ]
)
class VisitPhotoEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    var visit: VisitEntity,

    @Enumerated(EnumType.STRING)
    @Column(name = "photo_type", nullable = false, length = 50)
    val photoType: PhotoType,

    @Column(name = "file_id", nullable = false, length = 255)
    val fileId: String,

    @Column(name = "file_name", nullable = false, length = 255)
    val fileName: String,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String?,

    @Column(name = "uploaded_at", nullable = false, columnDefinition = "timestamp with time zone")
    val uploadedAt: Instant
) {
    fun toDomain(): VisitPhoto = VisitPhoto(
        id = VisitPhotoId(id),
        photoType = photoType,
        fileId = fileId,
        fileName = fileName,
        description = description,
        uploadedAt = uploadedAt
    )

    companion object {
        fun fromDomain(photo: VisitPhoto, visit: VisitEntity): VisitPhotoEntity =
            VisitPhotoEntity(
                id = photo.id.value,
                visit = visit,
                photoType = photo.photoType,
                fileId = photo.fileId,
                fileName = photo.fileName,
                description = photo.description,
                uploadedAt = photo.uploadedAt
            )
    }
}

@Entity
@Table(
    name = "visit_journal_entries",
    indexes = [
        Index(name = "idx_visit_journal_entries_visit_id", columnList = "visit_id"),
        Index(name = "idx_visit_journal_entries_created_at", columnList = "visit_id, created_at")
    ]
)
class VisitJournalEntryEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    var visit: VisitEntity,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    val type: JournalEntryType,

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    val content: String,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_by_name", nullable = false, length = 200)
    val createdByName: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant,

    @Column(name = "is_deleted", nullable = false)
    var isDeleted: Boolean = false
) {
    fun toDomain(): VisitJournalEntry = VisitJournalEntry(
        id = VisitJournalEntryId(id),
        type = type,
        content = content,
        createdBy = UserId(createdBy),
        createdByName = createdByName,
        createdAt = createdAt,
        isDeleted = isDeleted
    )

    companion object {
        fun fromDomain(entry: VisitJournalEntry, visit: VisitEntity): VisitJournalEntryEntity =
            VisitJournalEntryEntity(
                id = entry.id.value,
                visit = visit,
                type = entry.type,
                content = entry.content,
                createdBy = entry.createdBy.value,
                createdByName = entry.createdByName,
                createdAt = entry.createdAt,
                isDeleted = entry.isDeleted
            )
    }
}

@Entity
@Table(
    name = "visit_documents",
    indexes = [
        Index(name = "idx_visit_documents_visit_id", columnList = "visit_id"),
        Index(name = "idx_visit_documents_uploaded_at", columnList = "visit_id, uploaded_at"),
        Index(name = "idx_visit_documents_customer_id", columnList = "customer_id, uploaded_at")
    ]
)
class VisitDocumentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    var visit: VisitEntity,

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    val customerId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    val type: DocumentType,

    @Column(name = "name", nullable = false, length = 255)
    val name: String,

    @Column(name = "file_name", nullable = false, length = 255)
    val fileName: String,

    @Column(name = "file_id", nullable = false, length = 255)
    val fileId: String,

    @Column(name = "file_url", nullable = false, length = 500)
    val fileUrl: String,

    @Column(name = "uploaded_at", nullable = false, columnDefinition = "timestamp with time zone")
    val uploadedAt: Instant,

    @Column(name = "uploaded_by", nullable = false, columnDefinition = "uuid")
    val uploadedBy: UUID,

    @Column(name = "uploaded_by_name", nullable = false, length = 200)
    val uploadedByName: String,

    @Column(name = "category", length = 100)
    val category: String?
) {
    fun toDomain(): VisitDocument = VisitDocument(
        id = VisitDocumentId(id),
        customerId = CustomerId(customerId),
        type = type,
        name = name,
        fileName = fileName,
        fileId = fileId,
        fileUrl = fileUrl,
        uploadedAt = uploadedAt,
        uploadedBy = UserId(uploadedBy),
        uploadedByName = uploadedByName,
        category = category
    )

    companion object {
        fun fromDomain(document: VisitDocument, visit: VisitEntity): VisitDocumentEntity =
            VisitDocumentEntity(
                id = document.id.value,
                visit = visit,
                customerId = document.customerId.value,
                type = document.type,
                name = document.name,
                fileName = document.fileName,
                fileId = document.fileId,
                fileUrl = document.fileUrl,
                uploadedAt = document.uploadedAt,
                uploadedBy = document.uploadedBy.value,
                uploadedByName = document.uploadedByName,
                category = document.category
            )
    }
}

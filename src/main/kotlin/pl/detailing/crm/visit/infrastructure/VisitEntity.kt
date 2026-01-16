package pl.detailing.crm.visit.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.domain.VisitServiceItem
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

    // Immutable vehicle snapshots - frozen at visit creation
    @Column(name = "brand_snapshot", nullable = false, length = 100)
    val brandSnapshot: String,

    @Column(name = "model_snapshot", nullable = false, length = 100)
    val modelSnapshot: String,

    @Column(name = "license_plate_snapshot", nullable = false, length = 20)
    val licensePlateSnapshot: String,

    @Column(name = "vin_snapshot", length = 17)
    val vinSnapshot: String?,

    @Column(name = "year_of_production_snapshot", nullable = false)
    val yearOfProductionSnapshot: Int,

    @Column(name = "color_snapshot", length = 50)
    val colorSnapshot: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "engine_type_snapshot", nullable = false, length = 20)
    val engineTypeSnapshot: EngineType,

    // Visit status and dates
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    var status: VisitStatus,

    @Column(name = "scheduled_date", nullable = false, columnDefinition = "timestamp with time zone")
    val scheduledDate: Instant,

    @Column(name = "completed_date", columnDefinition = "timestamp with time zone")
    var completedDate: Instant?,

    // Arrival details
    @Column(name = "mileage_at_arrival")
    var mileageAtArrival: Long?,

    @Column(name = "keys_handed_over", nullable = false)
    var keysHandedOver: Boolean,

    @Column(name = "documents_handed_over", nullable = false)
    var documentsHandedOver: Boolean,

    @Column(name = "technical_notes", columnDefinition = "TEXT")
    var technicalNotes: String?,

    // Service items (one-to-many)
    @OneToMany(mappedBy = "visit", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var serviceItems: MutableList<VisitServiceItemEntity> = mutableListOf(),

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
        brandSnapshot = brandSnapshot,
        modelSnapshot = modelSnapshot,
        licensePlateSnapshot = licensePlateSnapshot,
        vinSnapshot = vinSnapshot,
        yearOfProductionSnapshot = yearOfProductionSnapshot,
        colorSnapshot = colorSnapshot,
        engineTypeSnapshot = engineTypeSnapshot,
        status = status,
        scheduledDate = scheduledDate,
        completedDate = completedDate,
        mileageAtArrival = mileageAtArrival,
        keysHandedOver = keysHandedOver,
        documentsHandedOver = documentsHandedOver,
        technicalNotes = technicalNotes,
        serviceItems = serviceItems.map { it.toDomain() },
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
                brandSnapshot = visit.brandSnapshot,
                modelSnapshot = visit.modelSnapshot,
                licensePlateSnapshot = visit.licensePlateSnapshot,
                vinSnapshot = visit.vinSnapshot,
                yearOfProductionSnapshot = visit.yearOfProductionSnapshot,
                colorSnapshot = visit.colorSnapshot,
                engineTypeSnapshot = visit.engineTypeSnapshot,
                status = visit.status,
                scheduledDate = visit.scheduledDate,
                completedDate = visit.completedDate,
                mileageAtArrival = visit.mileageAtArrival,
                keysHandedOver = visit.keysHandedOver,
                documentsHandedOver = visit.documentsHandedOver,
                technicalNotes = visit.technicalNotes,
                createdBy = visit.createdBy.value,
                updatedBy = visit.updatedBy.value,
                createdAt = visit.createdAt,
                updatedAt = visit.updatedAt
            )

            // Map service items with bidirectional relationship
            entity.serviceItems = visit.serviceItems.map { serviceItem ->
                VisitServiceItemEntity.fromDomain(serviceItem, entity)
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

package pl.detailing.crm.appointment.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.appointment.domain.*
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "appointments",
    indexes = [
        Index(name = "idx_appointments_studio_id", columnList = "studio_id"),
        Index(name = "idx_appointments_studio_customer", columnList = "studio_id, customer_id"),
        Index(name = "idx_appointments_studio_vehicle", columnList = "studio_id, vehicle_id"),
        Index(name = "idx_appointments_studio_schedule", columnList = "studio_id, start_date_time, end_date_time"),
        Index(name = "idx_appointments_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_appointments_created_by", columnList = "created_by"),
        Index(name = "idx_appointments_updated_by", columnList = "updated_by")
    ]
)
class AppointmentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    var customerId: UUID,

    @Column(name = "vehicle_id", nullable = true, columnDefinition = "uuid")
    var vehicleId: UUID?,

    @Column(name = "appointment_title", length = 255)
    var appointmentTitle: String?,

    @Column(name = "appointment_color_id", nullable = false, columnDefinition = "uuid")
    var appointmentColorId: UUID,

    @Column(name = "is_all_day", nullable = false)
    var isAllDay: Boolean,

    @Column(name = "start_date_time", nullable = false, columnDefinition = "timestamp with time zone")
    var startDateTime: Instant,

    @Column(name = "end_date_time", nullable = false, columnDefinition = "timestamp with time zone")
    var endDateTime: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    var status: AppointmentStatus,

    @OneToMany(mappedBy = "appointment", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.EAGER)
    var lineItems: MutableList<AppointmentLineItemEntity> = mutableListOf(),

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): Appointment = Appointment(
        id = AppointmentId(id),
        studioId = StudioId(studioId),
        customerId = CustomerId(customerId),
        vehicleId = vehicleId?.let { VehicleId(it) },
        appointmentTitle = appointmentTitle,
        appointmentColorId = AppointmentColorId(appointmentColorId),
        lineItems = lineItems.map { it.toDomain() },
        schedule = AppointmentSchedule(
            isAllDay = isAllDay,
            startDateTime = startDateTime,
            endDateTime = endDateTime
        ),
        status = status,
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(appointment: Appointment): AppointmentEntity {
            val entity = AppointmentEntity(
                id = appointment.id.value,
                studioId = appointment.studioId.value,
                customerId = appointment.customerId.value,
                vehicleId = appointment.vehicleId?.value,
                appointmentTitle = appointment.appointmentTitle,
                appointmentColorId = appointment.appointmentColorId.value,
                isAllDay = appointment.schedule.isAllDay,
                startDateTime = appointment.schedule.startDateTime,
                endDateTime = appointment.schedule.endDateTime,
                status = appointment.status,
                createdBy = appointment.createdBy.value,
                updatedBy = appointment.updatedBy.value,
                createdAt = appointment.createdAt,
                updatedAt = appointment.updatedAt
            )

            // Map line items with bidirectional relationship
            entity.lineItems = appointment.lineItems.map { lineItem ->
                AppointmentLineItemEntity.fromDomain(lineItem, entity)
            }.toMutableList()

            return entity
        }
    }
}

@Entity
@Table(
    name = "appointment_line_items",
    indexes = [
        Index(name = "idx_line_items_appointment_id", columnList = "appointment_id"),
        Index(name = "idx_line_items_service_id", columnList = "service_id")
    ]
)
class AppointmentLineItemEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false)
    var appointment: AppointmentEntity,

    @Column(name = "service_id", nullable = false, columnDefinition = "uuid")
    var serviceId: UUID,

    @Column(name = "service_name", nullable = false, length = 255)
    var serviceName: String,

    @Column(name = "base_price_net", nullable = false)
    var basePriceNet: Long,

    @Column(name = "vat_rate", nullable = false)
    var vatRate: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 50)
    var adjustmentType: AdjustmentType,

    @Column(name = "adjustment_value", nullable = false)
    var adjustmentValue: Long,

    @Column(name = "final_price_net", nullable = false)
    var finalPriceNet: Long,

    @Column(name = "final_price_gross", nullable = false)
    var finalPriceGross: Long,

    @Column(name = "custom_note", columnDefinition = "TEXT")
    var customNote: String?
) {
    fun toDomain(): AppointmentLineItem = AppointmentLineItem(
        serviceId = ServiceId(serviceId),
        serviceName = serviceName,
        basePriceNet = Money(basePriceNet),
        vatRate = VatRate.fromInt(vatRate),
        adjustmentType = adjustmentType,
        adjustmentValue = adjustmentValue,
        finalPriceNet = Money(finalPriceNet),
        finalPriceGross = Money(finalPriceGross),
        customNote = customNote
    )

    companion object {
        fun fromDomain(lineItem: AppointmentLineItem, appointment: AppointmentEntity): AppointmentLineItemEntity =
            AppointmentLineItemEntity(
                appointment = appointment,
                serviceId = lineItem.serviceId.value,
                serviceName = lineItem.serviceName,
                basePriceNet = lineItem.basePriceNet.amountInCents,
                vatRate = lineItem.vatRate.rate,
                adjustmentType = lineItem.adjustmentType,
                adjustmentValue = lineItem.adjustmentValue,
                finalPriceNet = lineItem.finalPriceNet.amountInCents,
                finalPriceGross = lineItem.finalPriceGross.amountInCents,
                customNote = lineItem.customNote
            )
    }
}

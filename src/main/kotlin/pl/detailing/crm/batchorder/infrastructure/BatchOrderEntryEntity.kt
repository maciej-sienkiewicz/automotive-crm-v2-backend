package pl.detailing.crm.batchorder.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.batchorder.domain.BatchOrderEntry
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.BatchOrderEntryId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "batch_order_entries",
    indexes = [
        Index(name = "idx_batch_order_entries_studio_id", columnList = "studio_id"),
        Index(name = "idx_batch_order_entries_contractor", columnList = "studio_id, contractor_id"),
        Index(name = "idx_batch_order_entries_service_date", columnList = "studio_id, service_date")
    ]
)
class BatchOrderEntryEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "contractor_id", nullable = false, columnDefinition = "uuid")
    val contractorId: UUID,

    @Column(name = "service_date", nullable = false)
    var serviceDate: LocalDate,

    @Column(name = "vehicle_make", length = 100)
    var vehicleMake: String?,

    @Column(name = "vehicle_model", length = 100)
    var vehicleModel: String?,

    @Column(name = "vehicle_license_plate", length = 20)
    var vehicleLicensePlate: String?,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "batch_order_entry_services",
        joinColumns = [JoinColumn(name = "entry_id")]
    )
    @Column(name = "service_name", nullable = false)
    @OrderColumn(name = "sort_order")
    var services: MutableList<String> = mutableListOf(),

    @Column(name = "net_amount_cents", nullable = false)
    var netAmountCents: Long = 0,

    @Column(name = "gross_amount_cents", nullable = false)
    var grossAmountCents: Long = 0,

    @Column(name = "vat_rate", nullable = false)
    var vatRate: Int = 23,

    @Column(name = "notes", columnDefinition = "TEXT")
    var notes: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): BatchOrderEntry = BatchOrderEntry(
        id = BatchOrderEntryId(id),
        studioId = StudioId(studioId),
        contractorId = BatchContractorId(contractorId),
        serviceDate = serviceDate,
        vehicleMake = vehicleMake,
        vehicleModel = vehicleModel,
        vehicleLicensePlate = vehicleLicensePlate,
        services = services.toList(),
        netAmountCents = netAmountCents,
        grossAmountCents = grossAmountCents,
        vatRate = vatRate,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(entry: BatchOrderEntry): BatchOrderEntryEntity {
            val entity = BatchOrderEntryEntity(
                id = entry.id.value,
                studioId = entry.studioId.value,
                contractorId = entry.contractorId.value,
                serviceDate = entry.serviceDate,
                vehicleMake = entry.vehicleMake,
                vehicleModel = entry.vehicleModel,
                vehicleLicensePlate = entry.vehicleLicensePlate,
                netAmountCents = entry.netAmountCents,
                grossAmountCents = entry.grossAmountCents,
                vatRate = entry.vatRate,
                notes = entry.notes,
                createdAt = entry.createdAt,
                updatedAt = entry.updatedAt
            )
            entity.services = entry.services.toMutableList()
            return entity
        }
    }
}

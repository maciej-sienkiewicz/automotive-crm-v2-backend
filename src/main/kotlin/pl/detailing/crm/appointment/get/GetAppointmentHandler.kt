package pl.detailing.crm.appointment.get

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.appointment.list.*

import pl.detailing.crm.service.list.PackageItemDto
import java.util.UUID
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.service.infrastructure.ServicePackageItemRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository

@Service
class GetAppointmentHandler(
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val appointmentColorRepository: AppointmentColorRepository,
    private val smsLogRepository: SmsLogJpaRepository,
    private val serviceRepository: ServiceRepository,
    private val packageItemRepository: ServicePackageItemRepository
) {
    suspend fun handle(appointmentId: AppointmentId, studioId: StudioId): AppointmentListItem =
        withContext(Dispatchers.IO) {
            val appointment = appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value)
                ?: throw NotFoundException("Rezerwacja nie została znaleziona")

            val customer = customerRepository.findById(appointment.customerId).orElse(null)
            val vehicle = appointment.vehicleId?.let { vehicleRepository.findById(it).orElse(null) }
            val color = appointmentColorRepository.findById(appointment.appointmentColorId).orElse(null)
            val smsLogs = smsLogRepository.findAllByAppointmentIdIn(listOf(appointment.id))

            val domain = appointment.toDomain()
            val totalNet = domain.calculateTotalNet()
            val totalGross = domain.calculateTotalGross()
            val totalVat = domain.calculateTotalVat()

            AppointmentListItem(
                id = appointment.id.toString(),
                customerId = appointment.customerId.toString(),
                vehicleId = appointment.vehicleId?.toString(),
                customer = CustomerInfo(
                    firstName = customer?.firstName ?: "Unknown",
                    lastName = customer?.lastName ?: "Customer",
                    phone = customer?.phone ?: "",
                    email = customer?.email ?: ""
                ),
                vehicle = vehicle?.let {
                    VehicleInfo(
                        brand = it.brand,
                        model = it.model,
                        year = it.yearOfProduction,
                        licensePlate = it.licensePlate
                    )
                },
                services = buildServiceLineItems(appointment.lineItems),
                schedule = ScheduleInfo(
                    isAllDay = appointment.isAllDay,
                    startDateTime = appointment.startDateTime,
                    endDateTime = appointment.endDateTime
                ),
                appointmentTitle = appointment.appointmentTitle,
                appointmentColor = AppointmentColorInfo(
                    id = appointment.appointmentColorId.toString(),
                    name = color?.name ?: "Unknown",
                    hexColor = color?.hexColor ?: "#808080"
                ),
                status = appointment.status,
                note = appointment.note,
                totalNet = totalNet.amountInCents,
                totalGross = totalGross.amountInCents,
                totalVat = totalVat.amountInCents,
                createdAt = appointment.createdAt,
                updatedAt = appointment.updatedAt,
                smsInfo = buildSmsInfo(
                    smsLogs = smsLogs,
                    sendReminderSms = appointment.sendReminderSms
                ),
                recurrenceInfo = appointment.recurrenceSeriesId?.let { sid ->
                    val count = appointmentRepository.countBySeriesId(sid)
                    RecurrenceInfo(
                        seriesId = sid.toString(),
                        recurrenceIndex = appointment.recurrenceIndex ?: 0,
                        totalInSeries = count,
                        isDetached = appointment.isDetached
                    )
                },
                doorToDoor = if (appointment.d2dPickupCity != null && appointment.d2dDeliveryCity != null) {
                    DoorToDoorAppointmentInfo(
                        pickupCity = appointment.d2dPickupCity!!,
                        pickupStreet = appointment.d2dPickupStreet ?: "",
                        deliveryCity = appointment.d2dDeliveryCity!!,
                        deliveryStreet = appointment.d2dDeliveryStreet ?: "",
                        notes = appointment.d2dNotes
                    )
                } else null
            )
        }

    private fun buildServiceLineItems(
        lineItems: List<pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity>
    ): List<ServiceLineItemInfo> {
        val serviceIds = lineItems.mapNotNull { it.serviceId }.distinct()
        if (serviceIds.isEmpty()) return lineItems.map { mapLineItem(it, false, null) }

        val packageServiceIds = serviceRepository.findAllById(serviceIds)
            .filter { it.isPackage }
            .map { it.id }
            .toSet()

        val packageItemsByServiceId: Map<UUID, List<PackageItemDto>> = if (packageServiceIds.isNotEmpty()) {
            packageItemRepository.findByPackageIdIn(packageServiceIds.toList())
                .groupBy({ it.packageId }, { PackageItemDto(it.serviceId.toString(), it.serviceName, it.position) })
        } else emptyMap()

        return lineItems.map { lineItem ->
            val sid = lineItem.serviceId
            val isPkg = sid != null && sid in packageServiceIds
            val items = if (isPkg && sid != null) packageItemsByServiceId[sid]?.sortedBy { it.position } else null
            mapLineItem(lineItem, isPkg, items)
        }
    }

    private fun mapLineItem(
        lineItem: pl.detailing.crm.appointment.infrastructure.AppointmentLineItemEntity,
        isPackage: Boolean,
        packageItems: List<PackageItemDto>?
    ): ServiceLineItemInfo {
        val adjustmentValue = when (lineItem.adjustmentType) {
            AdjustmentType.PERCENT -> lineItem.adjustmentValue / 100.0
            else -> lineItem.adjustmentValue.toDouble()
        }
        return ServiceLineItemInfo(
            id = lineItem.id.toString(),
            serviceId = lineItem.serviceId?.toString(),
            serviceName = lineItem.serviceName,
            basePriceNet = lineItem.basePriceNet,
            vatRate = lineItem.vatRate,
            adjustment = PriceAdjustmentInfo(
                type = lineItem.adjustmentType,
                value = adjustmentValue
            ),
            note = lineItem.customNote,
            finalPriceNet = lineItem.finalPriceNet,
            finalPriceGross = lineItem.finalPriceGross,
            isPackage = isPackage,
            packageItems = packageItems
        )
    }
}

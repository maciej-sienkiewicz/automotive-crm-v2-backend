package pl.detailing.crm.protocol.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.CrmDataKey
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Service for resolving CRM data values for PDF form filling.
 *
 * This service extracts data from various domain entities (Visit, Customer, Vehicle, Studio)
 * and formats it according to the CrmDataKey mappings.
 */
@Service
class CrmDataResolver(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val studioRepository: StudioRepository
) {

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }

    /**
     * Resolve all CRM data values for a given visit.
     *
     * @return Map of CrmDataKey to formatted string value
     */
    suspend fun resolveVisitData(visitId: VisitId, studioId: StudioId): Map<CrmDataKey, String> =
        withContext(Dispatchers.IO) {
            // Fetch with photos eagerly loaded to avoid LazyInitializationException
            val visit = visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value)
                ?: throw IllegalArgumentException("Visit not found: $visitId")

            val customer = customerRepository.findByIdAndStudioId(visit.customerId, studioId.value)
            val vehicle = vehicleRepository.findByIdAndStudioId(visit.vehicleId, studioId.value)
            val studio = studioRepository.findById(studioId.value).orElse(null)

            val visitDomain = visit.toDomain()

            buildMap {
                // Vehicle data (from visit snapshot)
                put(CrmDataKey.VEHICLE_VIN, visit.vinSnapshot ?: "")
                put(CrmDataKey.VEHICLE_PLATE, visit.licensePlateSnapshot)
                put(CrmDataKey.VEHICLE_BRAND_MODEL, "${visit.brandSnapshot} ${visit.modelSnapshot}")
                put(CrmDataKey.VEHICLE_COLOR, visit.colorSnapshot ?: "")
                put(CrmDataKey.VEHICLE_YEAR, visit.yearOfProductionSnapshot.toString())
                put(CrmDataKey.VEHICLE_ENGINE_TYPE, visit.engineTypeSnapshot.name)

                // Customer data
                customer?.let {
                    put(CrmDataKey.CUSTOMER_FULL_NAME, "${it.firstName} ${it.lastName}")
                    put(CrmDataKey.CUSTOMER_PHONE, it.phone ?: "")
                    put(CrmDataKey.CUSTOMER_EMAIL, it.email ?: "")
                }

                // Visit context
                put(CrmDataKey.VISIT_MILEAGE, visit.mileageAtArrival?.toString() ?: "")
                put(CrmDataKey.VISIT_NUMBER, visit.visitNumber)
                put(CrmDataKey.VISIT_DATE, formatDate(visit.scheduledDate))
                put(CrmDataKey.VISIT_COMPLETED_DATE, visit.completedDate?.let { formatDate(it) } ?: "")

                // Financial data
                val totalNet = visitDomain.calculateTotalNet()
                val totalGross = visitDomain.calculateTotalGross()
                val totalVat = visitDomain.calculateTotalVat()

                put(CrmDataKey.TOTAL_NET_AMOUNT, formatMoney(totalNet.amountInCents))
                put(CrmDataKey.TOTAL_GROSS_AMOUNT, formatMoney(totalGross.amountInCents))
                put(CrmDataKey.TOTAL_VAT_AMOUNT, formatMoney(totalVat.amountInCents))

                // Services list
                val servicesList = visitDomain.serviceItems
                    .filter { it.status == pl.detailing.crm.shared.VisitServiceStatus.CONFIRMED ||
                             it.status == pl.detailing.crm.shared.VisitServiceStatus.APPROVED }
                    .joinToString("\n") { "• ${it.serviceName}" }
                put(CrmDataKey.SERVICES_LIST, servicesList)

                // Studio/Company data
                studio?.let {
                    put(CrmDataKey.STUDIO_NAME, it.name)
                }

                // Current date/time
                val now = Instant.now()
                put(CrmDataKey.CURRENT_DATE, formatDate(now))
                put(CrmDataKey.CURRENT_DATETIME, formatDateTime(now))
            }
        }

    private fun formatDate(instant: Instant): String {
        return instant.atZone(ZoneId.systemDefault()).format(DATE_FORMATTER)
    }

    private fun formatDateTime(instant: Instant): String {
        return instant.atZone(ZoneId.systemDefault()).format(DATETIME_FORMATTER)
    }

    private fun formatMoney(amountInCents: Long): String {
        val amount = amountInCents / 100.0
        return String.format("%.2f zł", amount)
    }
}

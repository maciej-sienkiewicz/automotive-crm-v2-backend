package pl.detailing.crm.protocol.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
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
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd hh:mm")
    }

    /**
     * Resolve all CRM data values for a given visit.
     *
     * @return Map of CrmDataKey to formatted string value
     */
    suspend fun resolveVisitData(visitId: VisitId, studioId: StudioId): Map<CrmDataKey, String> =
        withContext(Dispatchers.IO) {
            val dbStart = System.currentTimeMillis()

            // Fetch with photos eagerly loaded to avoid LazyInitializationException
            val visitStart = System.currentTimeMillis()
            val visit = visitRepository.findByIdAndStudioIdWithPhotos(visitId.value, studioId.value)
                ?: throw IllegalArgumentException("Visit not found: $visitId")
            logger.info("[PERF]     - Query visit: ${System.currentTimeMillis() - visitStart}ms")

            val customerStart = System.currentTimeMillis()
            val customer = customerRepository.findByIdAndStudioId(visit.customerId, studioId.value)
            logger.info("[PERF]     - Query customer: ${System.currentTimeMillis() - customerStart}ms")

            val vehicleStart = System.currentTimeMillis()
            val vehicle = vehicleRepository.findByIdAndStudioId(visit.vehicleId, studioId.value)
            logger.info("[PERF]     - Query vehicle: ${System.currentTimeMillis() - vehicleStart}ms")

            val studioStart = System.currentTimeMillis()
            val studio = studioRepository.findById(studioId.value).orElse(null)
            logger.info("[PERF]     - Query studio: ${System.currentTimeMillis() - studioStart}ms")

            logger.info("[PERF]     - Total DB queries: ${System.currentTimeMillis() - dbStart}ms")

            val visitDomain = visit.toDomain()

            buildMap {
                // Vehicle data (from visit snapshot)
                put(CrmDataKey.VEHICLE_PLATE, visit.licensePlateSnapshot ?: "")
                put(CrmDataKey.VEHICLE_BRAND, visit.brandSnapshot)
                put(CrmDataKey.VEHICLE_MODEL, visit.modelSnapshot)
                put(CrmDataKey.VEHICLE_BRAND_MODEL, "${visit.brandSnapshot} ${visit.modelSnapshot}")
                put(CrmDataKey.VEHICLE_COLOR, visit.colorSnapshot ?: "")
                put(CrmDataKey.VEHICLE_YEAR, visit.yearOfProductionSnapshot?.toString() ?: "")

                // Customer data
                customer?.let {
                    val customerFullName = if(visit.isHandedOffByOtherPerson == true) "${visit.contactPersonFirstName} ${visit.contactPersonLastName}" else "${it.firstName} ${it.lastName}"
                    val customerPhone = (if(visit.isHandedOffByOtherPerson == true) visit.contactPersonPhone else it.phone) ?: ""
                    val customerEmail = (if(visit.isHandedOffByOtherPerson == true) visit.contactPersonEmail else it.email) ?: ""
                    put(CrmDataKey.CUSTOMER_FULL_NAME, customerFullName)
                    put(CrmDataKey.CUSTOMER_PHONE, customerPhone)
                    put(CrmDataKey.CUSTOMER_EMAIL, customerEmail)
                    put(CrmDataKey.CUSTOMER_COMPANY_NIP, it.companyNip ?: "")
                    put(CrmDataKey.STUDIO_NAME, it.companyName ?: "")
                }

                // Visit context
                put(CrmDataKey.VISIT_MILEAGE, visit.mileageAtArrival?.toString() ?: "")
                put(CrmDataKey.VISIT_NUMBER, visit.visitNumber)
                put(CrmDataKey.VISIT_DATE, formatDate(visit.scheduledDate))
                put(CrmDataKey.VISIT_COMPLETED_DATE, visit.pickupDate?.let { formatDate(it) } ?: "")

                // Financial data
                val totalNet = visitDomain.calculateTotalNet()
                val totalGross = visitDomain.calculateTotalGross()
                val totalVat = visitDomain.calculateTotalVat()

                put(CrmDataKey.TOTAL_NET_AMOUNT, formatMoney(totalNet.amountInCents))
                put(CrmDataKey.TOTAL_GROSS_AMOUNT, formatMoney(totalGross.amountInCents))
                put(CrmDataKey.TOTAL_VAT_AMOUNT, formatMoney(totalVat.amountInCents))

                // Services list - comma-separated with notes in parentheses
                val servicesList = visitDomain.serviceItems
                    .filter { it.status == pl.detailing.crm.shared.VisitServiceStatus.CONFIRMED ||
                             it.status == pl.detailing.crm.shared.VisitServiceStatus.APPROVED }
                    .joinToString(", ") { service ->
                        if (service.customNote.isNullOrBlank()) {
                            service.serviceName
                        } else {
                            "${service.serviceName} (${service.customNote})"
                        }
                    }
                put(CrmDataKey.SERVICES_LIST, servicesList)
                put(CrmDataKey.NOTES, visitDomain.technicalNotes ?: "")

                // Current date/time
                val now = Instant.now()
                put(CrmDataKey.CURRENT_DATE, formatDate(now))
                put(CrmDataKey.CURRENT_DATETIME, formatDateTime(now))

                // Checkin/Checkout checkboxes
                // For PDFBox checkboxes: use "Yes" for checked, "Off" for unchecked
                put(CrmDataKey.VEHICLE_KEYS_RECEIVED, if (visit.keysHandedOver) "Yes" else "Off")
                put(CrmDataKey.VEHICLE_DOCUMENTS_RECEIVED, if (visit.documentsHandedOver) "Yes" else "Off")
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
        return String.format("%.2f PLN (brutto)", amount)
    }
}

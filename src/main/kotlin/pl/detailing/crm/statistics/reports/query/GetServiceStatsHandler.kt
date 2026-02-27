package pl.detailing.crm.statistics.reports.query

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.statistics.reports.domain.Granularity
import pl.detailing.crm.statistics.reports.domain.StatsDataPoint
import pl.detailing.crm.statistics.reports.infrastructure.StatsRepository
import java.time.Instant
import java.util.UUID

data class ServiceStatsResult(
    val serviceId: String,
    val serviceName: String,
    val isActive: Boolean,
    val granularity: Granularity,
    val startDate: Instant,
    val endDate: Instant,
    val data: List<StatsDataPoint>,
    val totalOrderCount: Long,
    val totalRevenueGross: Long
)

@Service
class GetServiceStatsHandler(
    private val serviceRepository: ServiceRepository,
    private val statsRepository: StatsRepository
) {
    /**
     * Returns time-series stats for a service lineage (all versions).
     *
     * Regardless of which version the caller passes, the handler resolves the ROOT
     * ancestor so the recursive CTE covers the full lineage from day one.
     */
    suspend fun handle(
        serviceId: ServiceId,
        studioId: StudioId,
        granularity: Granularity,
        startDate: Instant,
        endDate: Instant
    ): ServiceStatsResult = withContext(Dispatchers.IO) {
        if (!startDate.isBefore(endDate)) {
            throw ValidationException("startDate must be before endDate")
        }

        val service = serviceRepository.findByIdAndStudioId(serviceId.value, studioId.value)
            ?: throw EntityNotFoundException("Service $serviceId not found")

        // Resolve root for the full-lineage CTE
        val rootId = resolveRootServiceId(serviceId.value, studioId.value)

        val data = statsRepository.getServiceStats(
            studioId = studioId.value,
            rootServiceId = rootId,
            granularity = granularity,
            startDate = startDate,
            endDate = endDate
        )

        ServiceStatsResult(
            serviceId = service.id.toString(),
            serviceName = service.name,
            isActive = service.isActive,
            granularity = granularity,
            startDate = startDate,
            endDate = endDate,
            data = data,
            totalOrderCount = data.sumOf { it.orderCount },
            totalRevenueGross = data.sumOf { it.totalRevenueGross }
        )
    }

    private fun resolveRootServiceId(serviceId: UUID, studioId: UUID): UUID {
        var currentId = serviceId
        while (true) {
            val svc = serviceRepository.findByIdAndStudioId(currentId, studioId)
                ?: throw EntityNotFoundException("Service $currentId not found in studio")
            if (svc.replacesServiceId == null) return currentId
            currentId = svc.replacesServiceId!!
        }
    }
}

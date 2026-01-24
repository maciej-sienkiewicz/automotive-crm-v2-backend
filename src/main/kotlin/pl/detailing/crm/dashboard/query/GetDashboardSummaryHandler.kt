package pl.detailing.crm.dashboard.query

import kotlinx.coroutines.*
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.dashboard.domain.*
import pl.detailing.crm.inbound.infrastructure.CallLogRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.*

@Service
class GetDashboardSummaryHandler(
    private val visitRepository: VisitRepository,
    private val appointmentRepository: AppointmentRepository,
    private val callLogRepository: CallLogRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository
) {
    suspend fun handle(command: GetDashboardSummaryCommand): DashboardSummary = coroutineScope {
        // Calculate date ranges for week-over-week comparison
        val now = Instant.now()
        val today = LocalDate.now()
        val currentWeekStart = now.atZone(ZoneId.systemDefault())
            .with(DayOfWeek.MONDAY)
            .toLocalDate()
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
        val currentWeekEnd = now
        val previousWeekStart = currentWeekStart.minus(Duration.ofDays(7))
        val previousWeekEnd = currentWeekStart

        // Parallelize all data fetching operations
        val inProgressVisitsDeferred = async(Dispatchers.IO) {
            visitRepository.findByStudioIdAndStatus(
                command.studioId.value,
                VisitStatus.IN_PROGRESS
            )
        }

        val readyForPickupVisitsDeferred = async(Dispatchers.IO) {
            visitRepository.findByStudioIdAndStatus(
                command.studioId.value,
                VisitStatus.READY_FOR_PICKUP
            )
        }

        val incomingTodayAppointmentsDeferred = async(Dispatchers.IO) {
            appointmentRepository.findByStudioIdAndStatusAndDate(
                command.studioId.value,
                AppointmentStatus.CREATED,
                today
            )
        }

        val currentWeekRevenueDeferred = async(Dispatchers.IO) {
            visitRepository.sumRevenueByStudioIdAndDateRange(
                command.studioId.value,
                currentWeekStart,
                currentWeekEnd
            )
        }

        val previousWeekRevenueDeferred = async(Dispatchers.IO) {
            visitRepository.sumRevenueByStudioIdAndDateRange(
                command.studioId.value,
                previousWeekStart,
                previousWeekEnd
            )
        }

        val currentWeekCallsDeferred = async(Dispatchers.IO) {
            callLogRepository.countByStudioIdAndDateRange(
                command.studioId.value,
                currentWeekStart,
                currentWeekEnd
            )
        }

        val previousWeekCallsDeferred = async(Dispatchers.IO) {
            callLogRepository.countByStudioIdAndDateRange(
                command.studioId.value,
                previousWeekStart,
                previousWeekEnd
            )
        }

        val recentCallsDeferred = async(Dispatchers.IO) {
            callLogRepository.findPendingByStudioId(command.studioId.value).take(10)
        }

        // Await all results
        val inProgressVisits = inProgressVisitsDeferred.await()
        val readyForPickupVisits = readyForPickupVisitsDeferred.await()
        val incomingTodayAppointments = incomingTodayAppointmentsDeferred.await()
        val currentWeekRevenue = currentWeekRevenueDeferred.await()
        val previousWeekRevenue = previousWeekRevenueDeferred.await()
        val currentWeekCalls = currentWeekCallsDeferred.await()
        val previousWeekCalls = previousWeekCallsDeferred.await()
        val recentCalls = recentCallsDeferred.await()

        // Build operational stats with details
        val inProgressDetails = buildVisitDetails(inProgressVisits, command.studioId)
        val readyForPickupDetails = buildVisitDetails(readyForPickupVisits, command.studioId)
        val incomingTodayDetails = buildAppointmentDetails(incomingTodayAppointments, command.studioId)

        val stats = OperationalStats(
            inProgress = inProgressVisits.size,
            readyForPickup = readyForPickupVisits.size,
            incomingToday = incomingTodayAppointments.size,
            inProgressDetails = inProgressDetails,
            readyForPickupDetails = readyForPickupDetails,
            incomingTodayDetails = incomingTodayDetails
        )

        // Calculate revenue metric
        val revenue = BusinessMetric(
            currentValue = currentWeekRevenue,
            previousValue = previousWeekRevenue,
            deltaPercentage = calculatePercentageChange(currentWeekRevenue, previousWeekRevenue),
            unit = "PLN"
        )

        // Calculate call activity metric
        val callActivity = BusinessMetric(
            currentValue = currentWeekCalls,
            previousValue = previousWeekCalls,
            deltaPercentage = calculatePercentageChange(currentWeekCalls, previousWeekCalls),
            unit = "calls"
        )

        // Map recent calls
        val recentCallsSummary = recentCalls.map { callLog ->
            IncomingCallSummary(
                id = callLog.id.toString(),
                phoneNumber = callLog.phoneNumber,
                contactName = callLog.callerName,
                timestamp = callLog.receivedAt.toString(),
                note = callLog.note
            )
        }

        DashboardSummary(
            stats = stats,
            revenue = revenue,
            callActivity = callActivity,
            recentCalls = recentCallsSummary
        )
    }

    private fun buildVisitDetails(visits: List<pl.detailing.crm.visit.infrastructure.VisitEntity>, studioId: StudioId): List<VisitDetail> {
        return visits.map { visit ->
            // Fetch customer for phone number
            val customer = customerRepository.findByIdAndStudioId(visit.customerId, studioId.value)

            // Calculate total amount from service items
            val totalAmount = visit.serviceItems.sumOf { it.finalPriceGross }

            VisitDetail(
                id = VisitId(visit.id),
                brand = visit.brandSnapshot,
                model = visit.modelSnapshot,
                amount = Money.fromCents(totalAmount),
                customerFirstName = customer?.firstName ?: "Unknown",
                customerLastName = customer?.lastName ?: "Unknown",
                phoneNumber = customer?.phone
            )
        }
    }

    private fun buildAppointmentDetails(appointments: List<pl.detailing.crm.appointment.infrastructure.AppointmentEntity>, studioId: StudioId): List<VisitDetail> {
        return appointments.map { appointment ->
            // Fetch customer
            val customer = customerRepository.findByIdAndStudioId(appointment.customerId, studioId.value)

            // Fetch vehicle if vehicleId is not null
            val vehicle = appointment.vehicleId?.let { vehicleId ->
                vehicleRepository.findByIdAndStudioId(vehicleId, studioId.value)
            }

            // Calculate total amount from line items
            val totalAmount = appointment.lineItems.sumOf { it.finalPriceGross }

            VisitDetail(
                id = VisitId(appointment.id), // Use appointment ID as temporary visit ID
                brand = vehicle?.brand ?: "Unknown",
                model = vehicle?.model ?: "Unknown",
                amount = Money.fromCents(totalAmount),
                customerFirstName = customer?.firstName ?: "Unknown",
                customerLastName = customer?.lastName ?: "Unknown",
                phoneNumber = customer?.phone
            )
        }
    }

    private fun calculatePercentageChange(current: Long, previous: Long): Double {
        if (previous == 0L) {
            return if (current > 0) 100.0 else 0.0
        }
        return ((current - previous).toDouble() / previous.toDouble()) * 100.0
    }
}

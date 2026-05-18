package pl.detailing.crm.employee.leave

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.employee.domain.LeaveBalance
import pl.detailing.crm.employee.infrastructure.LeaveBalanceEntity
import pl.detailing.crm.employee.infrastructure.LeaveBalanceRepository
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate

data class InitLeaveBalanceCommand(
    val studioId: StudioId,
    val userId: UserId,
    val employeeId: EmployeeId,
    val year: Int,
    val totalDays: Int,
    val carriedOverDays: Int = 0,
    val adjustmentDays: Int = 0,
    val notes: String?
)

data class AdjustLeaveBalanceCommand(
    val studioId: StudioId,
    val userId: UserId,
    val employeeId: EmployeeId,
    val year: Int,
    val adjustmentDays: Int,
    val notes: String?
)

@Service
class LeaveBalanceHandler(
    private val leaveBalanceRepository: LeaveBalanceRepository
) {
    suspend fun getBalance(employeeId: EmployeeId, studioId: StudioId, year: Int): LeaveBalance? =
        withContext(Dispatchers.IO) {
            leaveBalanceRepository.findByEmployeeIdAndYear(employeeId.value, studioId.value, year)?.toDomain()
        }

    suspend fun getAllBalances(employeeId: EmployeeId, studioId: StudioId): List<LeaveBalance> =
        withContext(Dispatchers.IO) {
            leaveBalanceRepository.findByEmployeeIdAndStudioId(employeeId.value, studioId.value)
                .map { it.toDomain() }
        }

    @Transactional
    suspend fun initBalance(command: InitLeaveBalanceCommand): LeaveBalance = withContext(Dispatchers.IO) {
        val existing = leaveBalanceRepository.findByEmployeeIdAndYear(
            command.employeeId.value, command.studioId.value, command.year
        )
        if (existing != null) {
            throw ConflictException("Saldo urlopowe za rok ${command.year} już istnieje dla tego pracownika")
        }

        val balance = LeaveBalance(
            id = LeaveBalanceId.random(),
            studioId = command.studioId,
            employeeId = command.employeeId,
            year = command.year,
            totalDays = command.totalDays,
            usedDays = 0,
            pendingDays = 0,
            carriedOverDays = command.carriedOverDays,
            adjustmentDays = command.adjustmentDays,
            notes = command.notes,
            updatedAt = Instant.now()
        )
        leaveBalanceRepository.save(LeaveBalanceEntity.fromDomain(balance))
        balance
    }

    @Transactional
    suspend fun adjustBalance(command: AdjustLeaveBalanceCommand): LeaveBalance = withContext(Dispatchers.IO) {
        val entity = leaveBalanceRepository.findByEmployeeIdAndYear(
            command.employeeId.value, command.studioId.value, command.year
        ) ?: throw EntityNotFoundException("Saldo urlopowe za rok ${command.year} nie zostało znalezione")

        entity.adjustmentDays += command.adjustmentDays
        if (command.notes != null) {
            entity.notes = command.notes
        }
        entity.updatedAt = Instant.now()
        leaveBalanceRepository.save(entity)
        entity.toDomain()
    }
}

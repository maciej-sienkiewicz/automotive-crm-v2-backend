package pl.detailing.crm.employee.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.*
import org.slf4j.LoggerFactory
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService

@Service
class DeleteEmployeeHandler(
    private val employeeRepository: EmployeeRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository,
    private val leaveRequestRepository: LeaveRequestRepository,
    private val userRepository: UserRepository,
    private val auditService: AuditService,
    private val transactionTemplate: TransactionTemplate,
    private val storageService: DocumentStorageService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(
        studioId: StudioId,
        employeeId: EmployeeId,
        requestedBy: UserId,
        requestedByName: String?
    ) = withContext(Dispatchers.IO) {
        val employeeEntity = employeeRepository.findByIdAndStudioId(employeeId.value, studioId.value)
            ?: throw EntityNotFoundException("Pracownik nie istnieje")

        // One real transaction (TransactionTemplate — the body of a `@Transactional
        // suspend` function running on Dispatchers.IO escapes the interceptor-managed
        // transaction; see AuditLogWriter). Without it the login account could be
        // destroyed while the employee row survived — someone who can no longer sign in
        // but still occupies the roster and the team calendar.
        val fullName = "${employeeEntity.firstName} ${employeeEntity.lastName}"

        // Klucze plików wniosków urlopowych zbieramy przed usunięciem wierszy — po commicie
        // nie byłoby już skąd ich wziąć, a pliki są dokumentami kadrowymi tej osoby.
        val leaveRequestFiles = leaveRequestRepository.findAllOfEmployee(studioId.value, employeeId.value)
            .flatMap { listOfNotNull(it.documentS3Key, it.employeeSignedPdfS3Key, it.finalPdfS3Key) }

        transactionTemplate.execute {
            // Remove linked user account if present
            employeeEntity.userId?.let { userId ->
                userRepository.findByIdAndStudioId(userId, studioId.value)?.let { userRepository.delete(it) }
            }

            // Urlopy pracownika nie mogą pozostać osierocone — zasilają kalendarz zespołu
            employeeLeaveRepository.deleteByStudioIdAndEmployeeId(studioId.value, employeeId.value)
            // …tak samo jego wnioski urlopowe: wniosek osoby, której nie ma w zespole, wisiałby
            // w kolejce rozpatrujących bez nazwiska i bez możliwości decyzji.
            leaveRequestRepository.deleteAllOfEmployee(studioId.value, employeeId.value)
            employeeRepository.delete(employeeEntity)
        }

        // Najpierw wiersze, potem pliki: osierocony plik w S3 nikomu nie szkodzi, wiersz
        // wskazujący na skasowany plik byłby martwym linkiem.
        leaveRequestFiles.forEach { key ->
            runCatching { storageService.deleteDocument(key) }
                .onFailure { logger.warn("Could not delete leave request file {} [employeeId={}]", key, employeeId, it) }
        }

        auditService.log(LogAuditCommand(
            studioId = studioId,
            userId = requestedBy,
            userDisplayName = requestedByName ?: "",
            module = AuditModule.EMPLOYEE,
            entityId = employeeId.value.toString(),
            entityDisplayName = fullName,
            action = AuditAction.DELETE,
            changes = emptyList()
        ))
    }
}

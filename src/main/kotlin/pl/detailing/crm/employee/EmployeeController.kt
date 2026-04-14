package pl.detailing.crm.employee

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.employee.compensation.GetCompensationHandler
import pl.detailing.crm.employee.compensation.SetCompensationCommand
import pl.detailing.crm.employee.compensation.SetCompensationHandler
import pl.detailing.crm.employee.contract.*
import pl.detailing.crm.employee.create.CreateEmployeeCommand
import pl.detailing.crm.employee.create.CreateEmployeeHandler
import pl.detailing.crm.employee.create.CreateEmployeeRequest
import pl.detailing.crm.employee.domain.*
import pl.detailing.crm.employee.get.GetEmployeeHandler
import pl.detailing.crm.employee.leave.*
import pl.detailing.crm.employee.list.ListEmployeesHandler
import pl.detailing.crm.employee.payroll.*
import pl.detailing.crm.employee.terminate.TerminateEmployeeCommand
import pl.detailing.crm.employee.terminate.TerminateEmployeeHandler
import pl.detailing.crm.employee.update.UpdateEmployeeCommand
import pl.detailing.crm.employee.update.UpdateEmployeeHandler
import pl.detailing.crm.employee.update.UpdateEmployeeRequest
import pl.detailing.crm.employee.worktime.*
import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

@RestController
@RequestMapping("/api/v1/employees")
class EmployeeController(
    private val createEmployeeHandler: CreateEmployeeHandler,
    private val updateEmployeeHandler: UpdateEmployeeHandler,
    private val terminateEmployeeHandler: TerminateEmployeeHandler,
    private val getEmployeeHandler: GetEmployeeHandler,
    private val listEmployeesHandler: ListEmployeesHandler,
    private val createContractHandler: CreateContractHandler,
    private val endContractHandler: EndContractHandler,
    private val listContractsHandler: ListContractsHandler,
    private val createAmendmentHandler: CreateAmendmentHandler,
    private val listAmendmentsHandler: ListAmendmentsHandler,
    private val setCompensationHandler: SetCompensationHandler,
    private val getCompensationHandler: GetCompensationHandler,
    private val logWorkTimeHandler: LogWorkTimeHandler,
    private val approveWorkTimeHandler: ApproveWorkTimeHandler,
    private val listWorkTimeHandler: ListWorkTimeHandler,
    private val workTimeSummaryHandler: WorkTimeSummaryHandler,
    private val getWorkTimePeriodsHandler: GetWorkTimePeriodsHandler,
    private val saveWorkTimePeriodHandler: SaveWorkTimePeriodHandler,
    private val deleteWorkTimeEntryHandler: DeleteWorkTimeEntryHandler,
    private val approvePeriodWorkTimeHandler: ApprovePeriodWorkTimeHandler,
    private val requestLeaveHandler: RequestLeaveHandler,
    private val reviewLeaveHandler: ReviewLeaveHandler,
    private val listLeavesHandler: ListLeavesHandler,
    private val leaveBalanceHandler: LeaveBalanceHandler,
    private val generatePayrollHandler: GeneratePayrollHandler,
    private val confirmPayrollHandler: ConfirmPayrollHandler,
    private val listPayrollHandler: ListPayrollHandler,
    private val addBonusHandler: AddBonusHandler,
    private val listBonusesHandler: ListBonusesHandler,
    private val deleteBonusHandler: DeleteBonusHandler
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Employee CRUD
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping
    fun listEmployees(
        @RequestParam(required = false, defaultValue = "") search: String,
        @RequestParam(required = false, defaultValue = "false") includeTerminated: Boolean,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "50") limit: Int
    ): ResponseEntity<EmployeeListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        var employees = listEmployeesHandler.handle(principal.studioId, includeTerminated)

        if (search.isNotBlank()) {
            employees = employees.filter {
                it.fullName().contains(search, ignoreCase = true) ||
                    it.email?.contains(search, ignoreCase = true) == true ||
                    it.position.contains(search, ignoreCase = true)
            }
        }

        val totalItems = employees.size
        val start = (page - 1) * limit
        val paginatedItems = if (start < totalItems) {
            employees.subList(start, minOf(start + limit, totalItems))
        } else emptyList()

        ResponseEntity.ok(EmployeeListResponse(
            items = paginatedItems.map { it.toListItem() },
            pagination = EmployeePaginationInfo(
                currentPage = page,
                totalPages = if (limit > 0) (totalItems + limit - 1) / limit else 1,
                totalItems = totalItems,
                itemsPerPage = limit
            )
        ))
    }

    @GetMapping("/{employeeId}")
    fun getEmployee(@PathVariable employeeId: String): ResponseEntity<EmployeeDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val employee = getEmployeeHandler.handle(EmployeeId.fromString(employeeId), principal.studioId)
        ResponseEntity.ok(employee.toDetailResponse())
    }

    @PostMapping
    fun createEmployee(@RequestBody request: CreateEmployeeRequest): ResponseEntity<EmployeeDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create employees")
        }

        val command = CreateEmployeeCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            linkedUserId = request.linkedUserId?.let { UserId.fromString(it) },
            firstName = request.firstName,
            lastName = request.lastName,
            phone = request.phone,
            email = request.email,
            personalEmail = request.personalEmail,
            pesel = request.pesel,
            nip = request.nip,
            addressStreet = request.addressStreet,
            addressCity = request.addressCity,
            addressPostalCode = request.addressPostalCode,
            position = request.position,
            hireDate = request.hireDate,
            notes = request.notes
        )

        val result = createEmployeeHandler.handle(command)
        val employee = getEmployeeHandler.handle(result.employeeId, principal.studioId)
        ResponseEntity.status(HttpStatus.CREATED).body(employee.toDetailResponse())
    }

    @PutMapping("/{employeeId}")
    fun updateEmployee(
        @PathVariable employeeId: String,
        @RequestBody request: UpdateEmployeeRequest
    ): ResponseEntity<EmployeeDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update employees")
        }

        updateEmployeeHandler.handle(UpdateEmployeeCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            linkedUserId = request.linkedUserId?.let { UserId.fromString(it) },
            firstName = request.firstName,
            lastName = request.lastName,
            phone = request.phone,
            email = request.email,
            personalEmail = request.personalEmail,
            pesel = request.pesel,
            nip = request.nip,
            addressStreet = request.addressStreet,
            addressCity = request.addressCity,
            addressPostalCode = request.addressPostalCode,
            position = request.position,
            hireDate = request.hireDate,
            notes = request.notes
        ))

        val employee = getEmployeeHandler.handle(EmployeeId.fromString(employeeId), principal.studioId)
        ResponseEntity.ok(employee.toDetailResponse())
    }

    @PostMapping("/{employeeId}/terminate")
    fun terminateEmployee(
        @PathVariable employeeId: String,
        @RequestBody request: TerminateEmployeeRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER) {
            throw ForbiddenException("Only OWNER can terminate employees")
        }

        terminateEmployeeHandler.handle(TerminateEmployeeCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            terminationDate = request.terminationDate,
            reason = request.reason
        ))
        ResponseEntity.noContent().build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Contracts
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{employeeId}/contracts")
    fun listContracts(@PathVariable employeeId: String): ResponseEntity<List<ContractResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val contracts = listContractsHandler.handle(EmployeeId.fromString(employeeId), principal.studioId)
        ResponseEntity.ok(contracts.map { contract ->
            val salaryBasis = getCompensationHandler.handleByContractId(contract.id, principal.studioId)
            contract.toResponse(salaryBasis)
        })
    }

    @PostMapping("/{employeeId}/contracts")
    fun createContract(
        @PathVariable employeeId: String,
        @RequestBody request: CreateContractRequest
    ): ResponseEntity<Map<String, String>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create contracts")
        }

        val contractId = createContractHandler.handle(CreateContractCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            contractType = request.contractType,
            startDate = request.startDate,
            endDate = request.endDate,
            documentFileId = request.documentFileId,
            initialCompensation = request.initialCompensation.toDomain()
        ))
        ResponseEntity.status(HttpStatus.CREATED).body(mapOf("contractId" to contractId.toString()))
    }

    @PostMapping("/{employeeId}/contracts/{contractId}/end")
    fun endContract(
        @PathVariable employeeId: String,
        @PathVariable contractId: String,
        @RequestBody request: EndContractRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can end contracts")
        }

        endContractHandler.handle(EndContractCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            contractId = EmploymentContractId.fromString(contractId),
            terminationDate = request.terminationDate,
            terminationReason = request.terminationReason
        ))
        ResponseEntity.noContent().build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Contract Amendments
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{employeeId}/contracts/{contractId}/amendments")
    fun listAmendments(
        @PathVariable employeeId: String,
        @PathVariable contractId: String
    ): ResponseEntity<List<AmendmentResponse>> = runBlocking {
        val amendments = listAmendmentsHandler.handle(EmploymentContractId.fromString(contractId))
        ResponseEntity.ok(amendments.map { it.toResponse() })
    }

    @PostMapping("/{employeeId}/contracts/{contractId}/amendments")
    fun createAmendment(
        @PathVariable employeeId: String,
        @PathVariable contractId: String,
        @RequestBody request: CreateAmendmentRequest
    ): ResponseEntity<Map<String, String>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create amendments")
        }

        val amendmentId = createAmendmentHandler.handle(CreateAmendmentCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            contractId = EmploymentContractId.fromString(contractId),
            effectiveFrom = request.effectiveFrom,
            compensation = request.compensation.toDomain()
        ))
        ResponseEntity.status(HttpStatus.CREATED).body(mapOf("amendmentId" to amendmentId.toString()))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Compensation
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{employeeId}/compensation")
    fun getCurrentCompensation(@PathVariable employeeId: String): ResponseEntity<CompensationResponse?> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val config = getCompensationHandler.handleCurrent(EmployeeId.fromString(employeeId), principal.studioId)
        ResponseEntity.ok(config?.toResponse())
    }

    @GetMapping("/{employeeId}/compensation/history")
    fun getCompensationHistory(@PathVariable employeeId: String): ResponseEntity<List<CompensationResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val configs = getCompensationHandler.handleHistory(EmployeeId.fromString(employeeId), principal.studioId)
        ResponseEntity.ok(configs.map { it.toResponse() })
    }

    @PostMapping("/{employeeId}/compensation")
    fun setCompensation(
        @PathVariable employeeId: String,
        @RequestBody request: SetCompensationRequest
    ): ResponseEntity<Map<String, String>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can configure compensation")
        }

        val components = request.components.map { c ->
            CompensationComponent(
                id = CompensationComponentId.random(),
                name = c.name,
                type = c.type,
                calculationBase = c.calculationBase,
                value = c.value,
                thresholds = c.thresholds.map { t ->
                    Threshold(
                        minValue = Money.fromCents(t.minValueCents),
                        maxValue = t.maxValueCents?.let { Money.fromCents(it) },
                        rate = t.rate
                    )
                },
                frequency = c.frequency,
                isActive = c.isActive,
                description = c.description
            )
        }

        val configId = setCompensationHandler.handle(SetCompensationCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            contractId = EmploymentContractId.fromString(request.contractId),
            effectiveFrom = request.effectiveFrom,
            employmentMode = request.employmentMode,
            etatFraction = request.etatFraction,
            monthlySalaryGross = request.monthlySalaryGrossCents?.let { Money.fromCents(it) },
            baseSalaryGross = request.baseSalaryGrossCents?.let { Money.fromCents(it) },
            hourlyRateGross = request.hourlyRateGrossCents?.let { Money.fromCents(it) },
            hourlyRateNet = request.hourlyRateNetCents?.let { Money.fromCents(it) },
            components = components
        ))
        ResponseEntity.status(HttpStatus.CREATED).body(mapOf("configId" to configId.toString()))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Work Time
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{employeeId}/worktime/periods")
    fun listWorkTimePeriods(
        @PathVariable employeeId: String
    ): ResponseEntity<List<WorkTimePeriodSummaryResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val periods = getWorkTimePeriodsHandler.handle(
            EmployeeId.fromString(employeeId), principal.studioId
        )
        ResponseEntity.ok(periods.map {
            WorkTimePeriodSummaryResponse(
                period = it.period,
                totalHours = it.totalHours,
                status = it.status.name
            )
        })
    }

    @GetMapping("/{employeeId}/worktime")
    fun listWorkTime(
        @PathVariable employeeId: String,
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?
    ): ResponseEntity<List<WorkTimeEntryResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val entries = listWorkTimeHandler.handleForEmployee(
            EmployeeId.fromString(employeeId), principal.studioId, from, to
        )
        ResponseEntity.ok(entries.map { it.toResponse() })
    }

    @PutMapping("/{employeeId}/worktime/periods/{period}")
    fun saveWorkTimePeriod(
        @PathVariable employeeId: String,
        @PathVariable period: String,
        @RequestBody request: SaveWorkTimePeriodRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        saveWorkTimePeriodHandler.handle(
            SaveWorkTimePeriodCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                employeeId = EmployeeId.fromString(employeeId),
                period = YearMonth.parse(period),
                regularEntries = request.regular.map {
                    SaveWorkTimePeriodCommand.RegularEntry(it.date, it.hours)
                },
                benefitEntries = request.benefits.map {
                    val benefitType = try {
                        WorkTimeEntryType.valueOf(it.benefitType)
                    } catch (e: IllegalArgumentException) {
                        throw ValidationException("Unknown benefitType '${it.benefitType}'")
                    }
                    SaveWorkTimePeriodCommand.BenefitEntry(it.date, benefitType, it.hours)
                }
            )
        )
        ResponseEntity.noContent().build()
    }

    @PostMapping("/{employeeId}/worktime/periods/{period}/approve")
    fun approvePeriodWorkTime(
        @PathVariable employeeId: String,
        @PathVariable period: String
    ): ResponseEntity<ApprovePeriodResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can approve work time periods")
        }
        val result = approvePeriodWorkTimeHandler.handle(
            employeeId = EmployeeId.fromString(employeeId),
            period = YearMonth.parse(period),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )
        ResponseEntity.ok(ApprovePeriodResponse(
            period = period,
            approvedCount = result.approvedCount,
            skippedCount = result.skippedCount
        ))
    }

    @DeleteMapping("/{employeeId}/worktime/{entryId}")
    fun deleteWorkTimeEntry(
        @PathVariable employeeId: String,
        @PathVariable entryId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        deleteWorkTimeEntryHandler.handle(
            employeeId = EmployeeId.fromString(employeeId),
            entryId = WorkTimeEntryId.fromString(entryId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )
        ResponseEntity.noContent().build()
    }

    @GetMapping("/worktime/pending")
    fun listPendingWorkTime(): ResponseEntity<List<WorkTimeEntryResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val entries = listWorkTimeHandler.handlePendingForStudio(principal.studioId)
        ResponseEntity.ok(entries.map { it.toResponse() })
    }

    @GetMapping("/{employeeId}/worktime/summary")
    fun getWorkTimeSummary(
        @PathVariable employeeId: String,
        @RequestParam period: String
    ): ResponseEntity<WorkTimeSummaryResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val summary = workTimeSummaryHandler.handle(
            EmployeeId.fromString(employeeId), principal.studioId, YearMonth.parse(period)
        )
        ResponseEntity.ok(WorkTimeSummaryResponse(
            employeeId = summary.employeeId.toString(),
            period = summary.period.toString(),
            totalHours = summary.totalHours,
            regularHours = summary.regularHours,
            overtimeHours = summary.overtimeHours,
            approvedHours = summary.approvedHours,
            pendingHours = summary.pendingHours,
            entriesCount = summary.entriesCount,
            hoursPerType = summary.hoursPerType
        ))
    }

    @PostMapping("/{employeeId}/worktime")
    fun logWorkTime(
        @PathVariable employeeId: String,
        @RequestBody request: LogWorkTimeRequest
    ): ResponseEntity<Map<String, String>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val entryId = logWorkTimeHandler.handle(LogWorkTimeCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            date = request.date,
            startTime = request.startTime,
            endTime = request.endTime,
            breakMinutes = request.breakMinutes,
            entryType = request.entryType,
            notes = request.notes
        ))
        ResponseEntity.status(HttpStatus.CREATED).body(mapOf("entryId" to entryId.toString()))
    }

    @PostMapping("/worktime/{entryId}/approve")
    fun approveWorkTime(
        @PathVariable entryId: String,
        @RequestBody request: ApproveWorkTimeRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can approve work time")
        }

        approveWorkTimeHandler.handle(ApproveWorkTimeCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            entryId = WorkTimeEntryId.fromString(entryId),
            approve = request.approve,
            rejectionReason = request.rejectionReason
        ))
        ResponseEntity.noContent().build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Leave
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{employeeId}/leaves")
    fun listLeaves(@PathVariable employeeId: String): ResponseEntity<List<LeaveRequestResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val leaves = listLeavesHandler.handleForEmployee(EmployeeId.fromString(employeeId), principal.studioId)
        ResponseEntity.ok(leaves.map { it.toResponse() })
    }

    @GetMapping("/leaves/pending")
    fun listPendingLeaves(): ResponseEntity<List<LeaveRequestResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val leaves = listLeavesHandler.handlePendingForStudio(principal.studioId)
        ResponseEntity.ok(leaves.map { it.toResponse() })
    }

    @PostMapping("/{employeeId}/leaves")
    fun requestLeave(
        @PathVariable employeeId: String,
        @RequestBody request: RequestLeaveRequest
    ): ResponseEntity<Map<String, String>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val leaveId = requestLeaveHandler.handle(RequestLeaveCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            leaveType = request.leaveType,
            startDate = request.startDate,
            endDate = request.endDate,
            reason = request.reason
        ))
        ResponseEntity.status(HttpStatus.CREATED).body(mapOf("leaveRequestId" to leaveId.toString()))
    }

    @PostMapping("/leaves/{leaveRequestId}/review")
    fun reviewLeave(
        @PathVariable leaveRequestId: String,
        @RequestBody request: ReviewLeaveRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can review leave requests")
        }

        reviewLeaveHandler.handle(ReviewLeaveCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            leaveRequestId = LeaveRequestId.fromString(leaveRequestId),
            approve = request.approve,
            reviewNote = request.reviewNote
        ))
        ResponseEntity.noContent().build()
    }

    @GetMapping("/{employeeId}/leave-balance")
    fun getLeaveBalance(
        @PathVariable employeeId: String,
        @RequestParam(required = false) year: Int?
    ): ResponseEntity<Any> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val empId = EmployeeId.fromString(employeeId)

        if (year != null) {
            val balance = leaveBalanceHandler.getBalance(empId, principal.studioId, year)
            ResponseEntity.ok(balance?.toResponse())
        } else {
            val balances = leaveBalanceHandler.getAllBalances(empId, principal.studioId)
            ResponseEntity.ok(balances.map { it.toResponse() })
        }
    }

    @PostMapping("/{employeeId}/leave-balance")
    fun initLeaveBalance(
        @PathVariable employeeId: String,
        @RequestBody request: InitLeaveBalanceRequest
    ): ResponseEntity<LeaveBalanceResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can initialise leave balances")
        }

        val balance = leaveBalanceHandler.initBalance(InitLeaveBalanceCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            employeeId = EmployeeId.fromString(employeeId),
            year = request.year,
            totalDays = request.totalDays,
            carriedOverDays = request.carriedOverDays,
            adjustmentDays = request.adjustmentDays,
            notes = request.notes
        ))
        ResponseEntity.status(HttpStatus.CREATED).body(balance.toResponse())
    }

    @PatchMapping("/{employeeId}/leave-balance/adjust")
    fun adjustLeaveBalance(
        @PathVariable employeeId: String,
        @RequestBody request: AdjustLeaveBalanceRequest
    ): ResponseEntity<LeaveBalanceResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can adjust leave balances")
        }

        val balance = leaveBalanceHandler.adjustBalance(AdjustLeaveBalanceCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            employeeId = EmployeeId.fromString(employeeId),
            year = request.year,
            adjustmentDays = request.adjustmentDays,
            notes = request.notes
        ))
        ResponseEntity.ok(balance.toResponse())
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Payroll
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{employeeId}/payroll")
    fun listPayroll(@PathVariable employeeId: String): ResponseEntity<List<PayrollEntryResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val entries = listPayrollHandler.handleForEmployee(EmployeeId.fromString(employeeId), principal.studioId)
        ResponseEntity.ok(entries.map { it.toResponse() })
    }

    @GetMapping("/payroll")
    fun listPayrollForPeriod(@RequestParam period: String): ResponseEntity<List<PayrollEntryResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val entries = listPayrollHandler.handleForPeriod(principal.studioId, YearMonth.parse(period))
        ResponseEntity.ok(entries.map { it.toResponse() })
    }

    @PostMapping("/{employeeId}/payroll/generate")
    fun generatePayroll(
        @PathVariable employeeId: String,
        @RequestBody request: GeneratePayrollRequest
    ): ResponseEntity<Map<String, String>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can generate payroll")
        }

        val payrollId = generatePayrollHandler.handle(GeneratePayrollCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            period = YearMonth.parse(request.period),
            revenueGrossCents = request.revenueGrossCents,
            revenueNetCents = request.revenueNetCents,
            notes = request.notes
        ))
        ResponseEntity.status(HttpStatus.CREATED).body(mapOf("payrollId" to payrollId.toString()))
    }

    @PostMapping("/payroll/{payrollId}/confirm")
    fun confirmPayroll(
        @PathVariable payrollId: String,
        @RequestBody request: ConfirmPayrollRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can confirm payroll")
        }

        confirmPayrollHandler.handle(ConfirmPayrollCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            payrollEntryId = PayrollEntryId.fromString(payrollId),
            markAsPaid = request.markAsPaid,
            totalNet = request.totalNetCents?.let { Money.fromCents(it) },
            employerCostTotal = request.employerCostTotalCents?.let { Money.fromCents(it) }
        ))
        ResponseEntity.noContent().build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bonuses & one-time additions
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/{employeeId}/bonuses")
    fun listBonuses(
        @PathVariable employeeId: String,
        @RequestParam(required = false) period: String?
    ): ResponseEntity<List<BonusEntryResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val bonuses = listBonusesHandler.handle(
            employeeId = EmployeeId.fromString(employeeId),
            studioId = principal.studioId,
            period = period?.let { java.time.YearMonth.parse(it) }
        )
        ResponseEntity.ok(bonuses.map { it.toResponse() })
    }

    @PostMapping("/{employeeId}/bonuses")
    fun addBonus(
        @PathVariable employeeId: String,
        @RequestBody request: AddBonusRequest
    ): ResponseEntity<Map<String, String>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can add bonuses")
        }

        val bonusId = addBonusHandler.handle(AddBonusCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            period = java.time.YearMonth.parse(request.period),
            name = request.name,
            amountCents = request.amountCents,
            notes = request.notes
        ))
        ResponseEntity.status(HttpStatus.CREATED).body(mapOf("bonusEntryId" to bonusId.toString()))
    }

    @DeleteMapping("/{employeeId}/bonuses/{bonusEntryId}")
    fun deleteBonus(
        @PathVariable employeeId: String,
        @PathVariable bonusEntryId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can delete bonuses")
        }

        deleteBonusHandler.handle(DeleteBonusCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            bonusEntryId = BonusEntryId.fromString(bonusEntryId)
        ))
        ResponseEntity.noContent().build()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Request DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class TerminateEmployeeRequest(val terminationDate: LocalDate, val reason: String?)

/**
 * Compensation block inside contract-creation and amendment requests.
 * Mirrors the frontend discriminated union InitialCompensation.
 */
data class InitialCompensationRequest(
    val employmentMode: EmploymentMode,
    /** Required when employmentMode = SALARY */
    val etatFraction: EtatFraction? = null,
    val monthlySalaryGrossCents: Long? = null,
    /** Required when employmentMode = HOURLY and rateType = GROSS (UZ) */
    val rateType: String? = null,
    val hourlyRateGrossCents: Long? = null,
    /** Required when employmentMode = HOURLY and rateType = NET (B2B) */
    val hourlyRateNetCents: Long? = null
) {
    fun toDomain(): InitialCompensationData = when (employmentMode) {
        EmploymentMode.SALARY -> {
            val etat = etatFraction ?: throw ValidationException("etatFraction is required for SALARY mode")
            val salary = monthlySalaryGrossCents ?: throw ValidationException("monthlySalaryGrossCents is required for SALARY mode")
            InitialCompensationData.Salary(etat, salary)
        }
        EmploymentMode.HOURLY -> when (rateType?.uppercase()) {
            "NET" -> {
                val rate = hourlyRateNetCents ?: throw ValidationException("hourlyRateNetCents is required for HOURLY/NET mode")
                InitialCompensationData.HourlyNet(rate)
            }
            else -> {
                val rate = hourlyRateGrossCents ?: throw ValidationException("hourlyRateGrossCents is required for HOURLY/GROSS mode")
                InitialCompensationData.HourlyGross(rate)
            }
        }
    }
}

data class CreateContractRequest(
    val contractType: ContractType,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val documentFileId: String?,
    val initialCompensation: InitialCompensationRequest
)

data class EndContractRequest(val terminationDate: LocalDate, val terminationReason: String?)

data class CreateAmendmentRequest(
    val effectiveFrom: LocalDate,
    val compensation: InitialCompensationRequest
)

data class SetCompensationRequest(
    val contractId: String,
    val effectiveFrom: LocalDate,
    val employmentMode: EmploymentMode,
    val etatFraction: EtatFraction?,
    val monthlySalaryGrossCents: Long?,
    val baseSalaryGrossCents: Long?,
    val hourlyRateGrossCents: Long?,
    val hourlyRateNetCents: Long?,
    val components: List<CompensationComponentRequest>
)

data class CompensationComponentRequest(
    val name: String,
    val type: ComponentType,
    val calculationBase: CalculationBase?,
    val value: BigDecimal,
    val thresholds: List<ThresholdRequest>,
    val frequency: PaymentFrequency,
    val isActive: Boolean,
    val description: String?
)

data class ThresholdRequest(val minValueCents: Long, val maxValueCents: Long?, val rate: BigDecimal)

data class LogWorkTimeRequest(
    val date: LocalDate,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val breakMinutes: Int,
    val entryType: WorkTimeEntryType,
    val notes: String?
)

data class ApproveWorkTimeRequest(val approve: Boolean, val rejectionReason: String?)

data class SaveWorkTimePeriodRequest(
    val regular: List<RegularEntryRequest>,
    val benefits: List<BenefitEntryRequest>
) {
    data class RegularEntryRequest(val date: LocalDate, val hours: BigDecimal)
    data class BenefitEntryRequest(val date: LocalDate, val benefitType: String, val hours: BigDecimal)
}

data class RequestLeaveRequest(
    val leaveType: LeaveType,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val reason: String?
)

data class ReviewLeaveRequest(val approve: Boolean, val reviewNote: String?)

data class InitLeaveBalanceRequest(
    val year: Int,
    val totalDays: Int,
    val carriedOverDays: Int = 0,
    val adjustmentDays: Int = 0,
    val notes: String?
)

data class AdjustLeaveBalanceRequest(val year: Int, val adjustmentDays: Int, val notes: String?)

data class GeneratePayrollRequest(
    val period: String,
    /**
     * Gross revenue generated by the employee this period.
     * Required when any active compensation component has type PERCENTAGE_OF_REVENUE
     * with calculationBase GROSS_REVENUE.
     */
    val revenueGrossCents: Long? = null,
    /**
     * Net revenue generated by the employee this period.
     * Required when any active compensation component has type PERCENTAGE_OF_REVENUE
     * with calculationBase NET_REVENUE.
     */
    val revenueNetCents: Long? = null,
    val notes: String? = null
)

data class AddBonusRequest(
    /** Payroll period this bonus applies to, format "YYYY-MM" */
    val period: String,
    val name: String,
    /** Positive = bonus/addition, negative = deduction. In grosz (1/100 PLN). */
    val amountCents: Long,
    val notes: String? = null
)

data class ConfirmPayrollRequest(
    val markAsPaid: Boolean = false,
    val totalNetCents: Long?,
    val employerCostTotalCents: Long?
)

// ─────────────────────────────────────────────────────────────────────────────
// Response DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class EmployeeListItem(
    val id: String,
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val position: String,
    val email: String?,
    val phone: String?,
    val status: String,
    val hireDate: String,
    val linkedUserId: String?
)

data class EmployeeListResponse(
    val items: List<EmployeeListItem>,
    val pagination: EmployeePaginationInfo
)

data class EmployeePaginationInfo(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)

data class EmployeeDetailResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val linkedUserId: String?,
    val phone: String?,
    val email: String?,
    val personalEmail: String?,
    val pesel: String?,
    val nip: String?,
    val addressStreet: String?,
    val addressCity: String?,
    val addressPostalCode: String?,
    val position: String,
    val hireDate: String,
    val terminationDate: String?,
    val status: String,
    val notes: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class SalaryBasisResponse(
    val monthlySalaryGrossCents: Long?,
    val baseSalaryGrossCents: Long?,
    val hourlyRateGrossCents: Long?,
    val hourlyRateNetCents: Long?,
    val effectiveFrom: String,
    val effectiveTo: String?
)

data class ContractResponse(
    val id: String,
    val contractType: String,
    val etatFraction: String?,
    val startDate: String,
    val endDate: String?,
    val terminationDate: String?,
    val terminationReason: String?,
    val isActive: Boolean,
    val documentFileId: String?,
    val createdAt: Instant,
    val salaryBasis: SalaryBasisResponse?
)

data class AmendmentResponse(
    val id: String,
    val contractId: String,
    val effectiveFrom: String,
    val effectiveTo: String?,
    val employmentMode: String,
    val etatFraction: String?,
    val monthlySalaryGrossCents: Long?,
    val hourlyRateGrossCents: Long?,
    val hourlyRateNetCents: Long?,
    val createdAt: Instant
)

data class CompensationResponse(
    val id: String,
    val contractId: String,
    val effectiveFrom: String,
    val effectiveTo: String?,
    val employmentMode: String,
    val etatFraction: String?,
    val standardMonthlyHours: Int?,
    val monthlySalaryGrossCents: Long?,
    val baseSalaryGrossCents: Long?,
    val hourlyRateGrossCents: Long?,
    val hourlyRateNetCents: Long?,
    val components: List<CompensationComponentResponse>,
    val createdAt: Instant
)

data class CompensationComponentResponse(
    val id: String,
    val name: String,
    val type: String,
    val calculationBase: String?,
    val value: BigDecimal,
    val frequency: String,
    val isActive: Boolean,
    val description: String?
)

data class WorkTimeEntryResponse(
    val id: String,
    val date: String,
    val effectiveHours: BigDecimal,
    val entryType: String,
    val status: String,
    val notes: String?
)

data class WorkTimeSummaryResponse(
    val employeeId: String,
    val period: String,
    val totalHours: BigDecimal,
    val regularHours: BigDecimal,
    val overtimeHours: BigDecimal,
    val approvedHours: BigDecimal,
    val pendingHours: BigDecimal,
    val entriesCount: Int,
    /** Approved hours per WorkTimeEntryType (e.g. "REGULAR" → 160.0, "OVERTIME_150" → 4.0). */
    val hoursPerType: Map<String, BigDecimal>
)

data class WorkTimePeriodSummaryResponse(
    val period: String,
    val totalHours: BigDecimal,
    val status: String
)

data class ApprovePeriodResponse(
    val period: String,
    val approvedCount: Int,
    val skippedCount: Int
)

data class LeaveRequestResponse(
    val id: String,
    val employeeId: String,
    val leaveType: String,
    val startDate: String,
    val endDate: String,
    val businessDaysCount: Int,
    val status: String,
    val reason: String?,
    val reviewedBy: String?,
    val reviewedAt: Instant?,
    val reviewNote: String?,
    val createdAt: Instant
)

data class LeaveBalanceResponse(
    val id: String,
    val employeeId: String,
    val year: Int,
    val totalDays: Int,
    val usedDays: Int,
    val pendingDays: Int,
    val carriedOverDays: Int,
    val adjustmentDays: Int,
    val remainingDays: Int,
    val notes: String?,
    val updatedAt: Instant
)

data class PayrollEntryResponse(
    val id: String,
    val employeeId: String,
    val contractId: String,
    val period: String,
    val baseSalaryGrossCents: Long,
    val totalHoursWorked: BigDecimal,
    val regularHoursWorked: BigDecimal,
    val componentBreakdown: List<PayrollBreakdownResponse>,
    val totalGrossCents: Long,
    val totalNetCents: Long?,
    val employerCostTotalCents: Long?,
    val status: String,
    val notes: String?,
    val confirmedBy: String?,
    val confirmedAt: Instant?,
    val createdAt: Instant
)

data class PayrollBreakdownResponse(
    val componentName: String,
    val calculatedAmountCents: Long,
    val calculationDetails: String
)

data class BonusEntryResponse(
    val id: String,
    val employeeId: String,
    val period: String,
    val name: String,
    /** Positive = bonus, negative = deduction. In grosz (1/100 PLN). */
    val amountCents: Long,
    val status: String,
    val payrollEntryId: String?,
    val notes: String?,
    val createdAt: Instant
)

// ─────────────────────────────────────────────────────────────────────────────
// Domain → Response mapping extensions
// ─────────────────────────────────────────────────────────────────────────────

private fun Employee.toListItem() = EmployeeListItem(
    id = id.toString(),
    firstName = firstName,
    lastName = lastName,
    fullName = fullName(),
    position = position,
    email = email,
    phone = phone,
    status = status.name,
    hireDate = hireDate.toString(),
    linkedUserId = userId?.toString()
)

private fun Employee.toDetailResponse() = EmployeeDetailResponse(
    id = id.toString(),
    firstName = firstName,
    lastName = lastName,
    fullName = fullName(),
    linkedUserId = userId?.toString(),
    phone = phone,
    email = email,
    personalEmail = personalEmail,
    pesel = pesel,
    nip = nip,
    addressStreet = address?.street,
    addressCity = address?.city,
    addressPostalCode = address?.postalCode,
    position = position,
    hireDate = hireDate.toString(),
    terminationDate = terminationDate?.toString(),
    status = status.name,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun EmploymentContract.toResponse(compensation: CompensationConfig? = null) = ContractResponse(
    id = id.toString(),
    contractType = contractType.name,
    etatFraction = null, // etat lives on CompensationConfig; included here for convenience if needed
    startDate = startDate.toString(),
    endDate = endDate?.toString(),
    terminationDate = terminationDate?.toString(),
    terminationReason = terminationReason,
    isActive = isActive,
    documentFileId = documentFileId,
    createdAt = createdAt,
    salaryBasis = compensation?.let {
        SalaryBasisResponse(
            monthlySalaryGrossCents = it.monthlySalaryGross?.amountInCents,
            baseSalaryGrossCents = it.baseSalaryGross?.amountInCents,
            hourlyRateGrossCents = it.hourlyRateGross?.amountInCents,
            hourlyRateNetCents = it.hourlyRateNet?.amountInCents,
            effectiveFrom = it.effectiveFrom.toString(),
            effectiveTo = it.effectiveTo?.toString()
        )
    }
)

private fun ContractAmendment.toResponse() = AmendmentResponse(
    id = id.toString(),
    contractId = contractId.toString(),
    effectiveFrom = effectiveFrom.toString(),
    effectiveTo = effectiveTo?.toString(),
    employmentMode = employmentMode.name,
    etatFraction = etatFraction?.name,
    monthlySalaryGrossCents = monthlySalaryGross?.amountInCents,
    hourlyRateGrossCents = hourlyRateGross?.amountInCents,
    hourlyRateNetCents = hourlyRateNet?.amountInCents,
    createdAt = createdAt
)

/** Standard monthly hours derived from etat fraction */
private fun EtatFraction.toMonthlyHours(): Int = when (this) {
    EtatFraction.FULL -> 168
    EtatFraction.HALF -> 84
    EtatFraction.QUARTER -> 42
}

private fun CompensationConfig.toResponse() = CompensationResponse(
    id = id.toString(),
    contractId = contractId.toString(),
    effectiveFrom = effectiveFrom.toString(),
    effectiveTo = effectiveTo?.toString(),
    employmentMode = employmentMode.name,
    etatFraction = etatFraction?.name,
    standardMonthlyHours = etatFraction?.toMonthlyHours(),
    monthlySalaryGrossCents = monthlySalaryGross?.amountInCents,
    baseSalaryGrossCents = baseSalaryGross?.amountInCents,
    hourlyRateGrossCents = hourlyRateGross?.amountInCents,
    hourlyRateNetCents = hourlyRateNet?.amountInCents,
    components = components.map {
        CompensationComponentResponse(
            id = it.id.toString(),
            name = it.name,
            type = it.type.name,
            calculationBase = it.calculationBase?.name,
            value = it.value,
            frequency = it.frequency.name,
            isActive = it.isActive,
            description = it.description
        )
    },
    createdAt = createdAt
)

private fun WorkTimeEntry.toResponse() = WorkTimeEntryResponse(
    id = id.toString(),
    date = date.toString(),
    effectiveHours = effectiveHours,
    entryType = entryType.name,
    status = status.name,
    notes = notes
)

private fun LeaveRequest.toResponse() = LeaveRequestResponse(
    id = id.toString(),
    employeeId = employeeId.toString(),
    leaveType = leaveType.name,
    startDate = startDate.toString(),
    endDate = endDate.toString(),
    businessDaysCount = businessDaysCount,
    status = status.name,
    reason = reason,
    reviewedBy = reviewedBy?.toString(),
    reviewedAt = reviewedAt,
    reviewNote = reviewNote,
    createdAt = createdAt
)

private fun LeaveBalance.toResponse() = LeaveBalanceResponse(
    id = id.toString(),
    employeeId = employeeId.toString(),
    year = year,
    totalDays = totalDays,
    usedDays = usedDays,
    pendingDays = pendingDays,
    carriedOverDays = carriedOverDays,
    adjustmentDays = adjustmentDays,
    remainingDays = remainingDays(),
    notes = notes,
    updatedAt = updatedAt
)

private fun BonusEntry.toResponse() = BonusEntryResponse(
    id = id.toString(),
    employeeId = employeeId.toString(),
    period = period.toString(),
    name = name,
    amountCents = amountCents,
    status = status.name,
    payrollEntryId = payrollEntryId?.toString(),
    notes = notes,
    createdAt = createdAt
)

private fun PayrollEntry.toResponse() = PayrollEntryResponse(
    id = id.toString(),
    employeeId = employeeId.toString(),
    contractId = contractId.toString(),
    period = period.toString(),
    baseSalaryGrossCents = baseSalaryGross.amountInCents,
    totalHoursWorked = totalHoursWorked,
    regularHoursWorked = regularHoursWorked,
    componentBreakdown = componentBreakdown.map {
        PayrollBreakdownResponse(
            componentName = it.componentName,
            calculatedAmountCents = it.calculatedAmount.amountInCents,
            calculationDetails = it.calculationDetails
        )
    },
    totalGrossCents = totalGross.amountInCents,
    totalNetCents = totalNet?.amountInCents,
    employerCostTotalCents = employerCostTotal?.amountInCents,
    status = status.name,
    notes = notes,
    confirmedBy = confirmedBy?.toString(),
    confirmedAt = confirmedAt,
    createdAt = createdAt
)

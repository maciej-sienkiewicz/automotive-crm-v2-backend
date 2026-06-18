package pl.detailing.crm.employee

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.employee.account.*
import pl.detailing.crm.employee.create.CreateEmployeeCommand
import pl.detailing.crm.employee.create.CreateEmployeeHandler
import pl.detailing.crm.employee.create.CreateEmployeeRequest
import pl.detailing.crm.employee.delete.DeleteEmployeeHandler
import pl.detailing.crm.employee.domain.Employee
import pl.detailing.crm.employee.get.GetEmployeeHandler
import pl.detailing.crm.employee.list.ListEmployeesHandler
import pl.detailing.crm.employee.update.UpdateEmployeeCommand
import pl.detailing.crm.employee.update.UpdateEmployeeHandler
import pl.detailing.crm.employee.update.UpdateEmployeeRequest
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.RoleId
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant

@RestController
@RequestMapping("/api/v1/employees")
class EmployeeController(
    private val createEmployeeHandler: CreateEmployeeHandler,
    private val updateEmployeeHandler: UpdateEmployeeHandler,
    private val getEmployeeHandler: GetEmployeeHandler,
    private val listEmployeesHandler: ListEmployeesHandler,
    private val provisionEmployeeAccountHandler: ProvisionEmployeeAccountHandler,
    private val blockEmployeeAccountHandler: BlockEmployeeAccountHandler,
    private val deleteEmployeeAccountHandler: DeleteEmployeeAccountHandler,
    private val deleteEmployeeHandler: DeleteEmployeeHandler,
    private val changeEmployeeAccountPasswordHandler: ChangeEmployeeAccountPasswordHandler,
    private val userRepository: UserRepository
) {

    @GetMapping
    fun listEmployees(
        @RequestParam(required = false, defaultValue = "") search: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "50") limit: Int
    ): ResponseEntity<EmployeeListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        var employees = listEmployeesHandler.handle(principal.studioId)

        if (search.isNotBlank()) {
            employees = employees.filter {
                it.fullName().contains(search, ignoreCase = true) ||
                    it.email?.contains(search, ignoreCase = true) == true
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
        val accountInfo = employee.userId?.let {
            userRepository.findByIdAndStudioId(it.value, principal.studioId.value)
                ?.let { u -> EmployeeAccountInfo(u.id.toString(), u.email, if (u.isOwner) "OWNER" else "USER", u.isActive) }
        }
        ResponseEntity.ok(employee.toDetailResponse(accountInfo))
    }

    @PostMapping
    fun createEmployee(@RequestBody request: CreateEmployeeRequest): ResponseEntity<EmployeeDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = createEmployeeHandler.handle(CreateEmployeeCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            firstName = request.firstName,
            lastName = request.lastName,
            phone = request.phone,
            email = request.email,
            createAccount = request.createAccount,
            roleId = request.roleId?.let { RoleId.fromString(it) }
        ))

        val employee = getEmployeeHandler.handle(result.employeeId, principal.studioId)
        val accountInfo = employee.userId?.let {
            userRepository.findByIdAndStudioId(it.value, principal.studioId.value)
                ?.let { u -> EmployeeAccountInfo(u.id.toString(), u.email, if (u.isOwner) "OWNER" else "USER", u.isActive) }
        }
        ResponseEntity.status(HttpStatus.CREATED).body(employee.toDetailResponse(accountInfo))
    }

    @PutMapping("/{employeeId}")
    fun updateEmployee(
        @PathVariable employeeId: String,
        @RequestBody request: UpdateEmployeeRequest
    ): ResponseEntity<EmployeeDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        updateEmployeeHandler.handle(UpdateEmployeeCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            employeeId = EmployeeId.fromString(employeeId),
            firstName = request.firstName,
            lastName = request.lastName,
            phone = request.phone,
            email = request.email
        ))

        val employee = getEmployeeHandler.handle(EmployeeId.fromString(employeeId), principal.studioId)
        val accountInfo = employee.userId?.let {
            userRepository.findByIdAndStudioId(it.value, principal.studioId.value)
                ?.let { u -> EmployeeAccountInfo(u.id.toString(), u.email, if (u.isOwner) "OWNER" else "USER", u.isActive) }
        }
        ResponseEntity.ok(employee.toDetailResponse(accountInfo))
    }

    @DeleteMapping("/{employeeId}")
    fun deleteEmployee(@PathVariable employeeId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (!principal.isOwner) {
            throw ForbiddenException("Tylko właściciel może usunąć pracownika")
        }
        deleteEmployeeHandler.handle(
            studioId = principal.studioId,
            employeeId = EmployeeId.fromString(employeeId),
            requestedBy = principal.userId,
            requestedByName = principal.fullName
        )
        ResponseEntity.noContent().build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Employee Account Management
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/{employeeId}/account")
    fun provisionAccount(
        @PathVariable employeeId: String,
        @RequestBody request: ProvisionAccountRequest
    ): ResponseEntity<Map<String, String>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val userId = provisionEmployeeAccountHandler.handle(
            ProvisionEmployeeAccountCommand(
                studioId = principal.studioId,
                requestedBy = principal.userId,
                requestedByName = principal.fullName,
                employeeId = EmployeeId.fromString(employeeId),
                email = request.email
            )
        )
        ResponseEntity.status(HttpStatus.CREATED).body(mapOf("userId" to userId.toString()))
    }

    @PatchMapping("/{employeeId}/account/block")
    fun blockAccount(
        @PathVariable employeeId: String,
        @RequestBody request: BlockAccountRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        blockEmployeeAccountHandler.handle(
            studioId = principal.studioId,
            employeeId = EmployeeId.fromString(employeeId),
            block = request.block,
            requestedBy = principal.userId,
            requestedByName = principal.fullName
        )
        ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{employeeId}/account")
    fun deleteAccount(@PathVariable employeeId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (!principal.isOwner) {
            throw ForbiddenException("Tylko właściciel może usunąć konto pracownika")
        }

        deleteEmployeeAccountHandler.handle(
            studioId = principal.studioId,
            employeeId = EmployeeId.fromString(employeeId),
            requestedBy = principal.userId,
            requestedByName = principal.fullName
        )
        ResponseEntity.noContent().build()
    }

    @PostMapping("/{employeeId}/account/change-password")
    fun changeAccountPassword(
        @PathVariable employeeId: String,
        @RequestBody request: ChangeAccountPasswordRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        changeEmployeeAccountPasswordHandler.handle(
            studioId = principal.studioId,
            employeeId = EmployeeId.fromString(employeeId),
            newPassword = request.newPassword,
            confirmPassword = request.confirmPassword,
            requestedBy = principal.userId,
            requestedByName = principal.fullName
        )
        ResponseEntity.noContent().build()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Request DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class ProvisionAccountRequest(val email: String)
data class BlockAccountRequest(val block: Boolean)
data class ChangeAccountPasswordRequest(val newPassword: String, val confirmPassword: String)

// ─────────────────────────────────────────────────────────────────────────────
// Response DTOs
// ─────────────────────────────────────────────────────────────────────────────

data class EmployeeListItem(
    val id: String,
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val email: String?,
    val phone: String?,
    val hasAccount: Boolean
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

data class EmployeeAccountInfo(
    val userId: String,
    val email: String,
    val role: String,
    val isActive: Boolean
)

data class EmployeeDetailResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val phone: String?,
    val email: String?,
    val account: EmployeeAccountInfo?,
    val createdAt: Instant,
    val updatedAt: Instant
)

// ─────────────────────────────────────────────────────────────────────────────
// Domain → Response mapping extensions
// ─────────────────────────────────────────────────────────────────────────────

private fun Employee.toListItem() = EmployeeListItem(
    id = id.toString(),
    firstName = firstName,
    lastName = lastName,
    fullName = fullName(),
    email = email,
    phone = phone,
    hasAccount = userId != null
)

private fun Employee.toDetailResponse(accountInfo: EmployeeAccountInfo? = null) = EmployeeDetailResponse(
    id = id.toString(),
    firstName = firstName,
    lastName = lastName,
    fullName = fullName(),
    phone = phone,
    email = email,
    account = accountInfo,
    createdAt = createdAt,
    updatedAt = updatedAt
)

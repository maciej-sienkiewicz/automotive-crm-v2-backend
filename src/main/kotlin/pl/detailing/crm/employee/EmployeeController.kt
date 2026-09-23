package pl.detailing.crm.employee

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.Pagination
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
import pl.detailing.crm.auth.passwordreset.PasswordResetProperties
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Duration
import java.time.Instant
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.domain.Permission

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
    private val resendEmployeeInvitationHandler: ResendEmployeeInvitationHandler,
    private val passwordResetProperties: PasswordResetProperties,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val permissionCheckService: PermissionCheckService
) {

    // Deliberately NOT permission-gated: the coworker list (names) is needed by
    // calendar/visit assignment views and requires no permission per the catalog.
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
        val safePage = Pagination.normalizePage(page)
        val safeLimit = Pagination.normalizeLimit(limit, max = 500)
        val paginatedItems = Pagination.slice(employees, safePage, safeLimit)

        // Role is management information, and this endpoint is deliberately open so the
        // calendar can read coworker names. Enrich only for callers who administer the
        // team; everyone else keeps the plain name list they had before.
        val canManage = permissionCheckService.hasPermission(principal.userId, principal.studioId, Permission.EMPLOYEES_MANAGE)
        val studioUsers = if (canManage) userRepository.findByStudioId(principal.studioId.value) else emptyList()
        val roleByUser: Map<String, RoleRef> =
            if (canManage) {
                val roleNames = roleRepository.findByStudioId(principal.studioId.value)
                    .associate { it.id to it.name }
                studioUsers
                    .mapNotNull { user ->
                        val roleId = user.customRoleId ?: return@mapNotNull null
                        val name = roleNames[roleId] ?: return@mapNotNull null
                        user.id.toString() to RoleRef(roleId.toString(), name)
                    }
                    .toMap()
            } else emptyMap()
        // Tak samo jak rola - informacja kadrowa, tylko dla zarządzających zespołem.
        val pendingUserIds: Set<String> = studioUsers
            .filter { it.invitationPending }
            .mapTo(HashSet()) { it.id.toString() }

        ResponseEntity.ok(EmployeeListResponse(
            items = paginatedItems.map { it.toListItem(roleByUser, pendingUserIds) },
            pagination = EmployeePaginationInfo(
                currentPage = safePage,
                totalPages = Pagination.totalPages(totalItems, safeLimit),
                totalItems = totalItems,
                itemsPerPage = safeLimit
            )
        ))
    }

    @GetMapping("/{employeeId}")
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
    fun getEmployee(@PathVariable employeeId: String): ResponseEntity<EmployeeDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val employee = getEmployeeHandler.handle(EmployeeId.fromString(employeeId), principal.studioId)
        val accountInfo = employee.userId?.let {
            userRepository.findByIdAndStudioId(it.value, principal.studioId.value)
                ?.let { u -> accountInfoOf(u) }
        }
        ResponseEntity.ok(employee.toDetailResponse(accountInfo))
    }

    @PostMapping
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
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
                ?.let { u -> accountInfoOf(u) }
        }
        ResponseEntity.status(HttpStatus.CREATED).body(employee.toDetailResponse(accountInfo))
    }

    @PutMapping("/{employeeId}")
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
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
                ?.let { u -> accountInfoOf(u) }
        }
        ResponseEntity.ok(employee.toDetailResponse(accountInfo))
    }

    @DeleteMapping("/{employeeId}")
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
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
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
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
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
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
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
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
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
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

    /** „Wyślij maila ponownie" - nowy link do ustawienia hasła dla konta, którego pracownik jeszcze nie aktywował. */
    @PostMapping("/{employeeId}/account/resend-invitation")
    @RequiresPermission(Permission.EMPLOYEES_MANAGE)
    fun resendInvitation(@PathVariable employeeId: String): ResponseEntity<ResendInvitationResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = resendEmployeeInvitationHandler.handle(
            studioId = principal.studioId,
            employeeId = EmployeeId.fromString(employeeId),
            requestedBy = principal.userId,
            requestedByName = principal.fullName
        )
        ResponseEntity.ok(ResendInvitationResponse(sentAt = result.sentAt, expiresAt = result.expiresAt))
    }

    private fun accountInfoOf(user: UserEntity): EmployeeAccountInfo {
        val sentAt = user.invitationSentAt.takeIf { user.invitationPending }
        return EmployeeAccountInfo(
            userId = user.id.toString(),
            roleId = user.customRoleId?.toString(),
            isActive = user.isActive,
            hasPinConfigured = user.pinHash != null,
            email = user.email,
            invitationPending = user.invitationPending,
            invitationSentAt = sentAt,
            invitationExpiresAt = sentAt?.plus(Duration.ofHours(passwordResetProperties.invitationTokenTtlHours))
        )
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
    val hasAccount: Boolean,
    /**
     * Konto czeka na aktywację z zaproszenia (tylko dla zarządzających zespołem; dla
     * pozostałych zawsze false, jak [role]).
     */
    val accountPending: Boolean = false,
    /**
     * The account's role, when the caller administers the team. Null covers three
     * different states the UI must tell apart, using [hasAccount]: no account at all,
     * an account with no role (no access), or a caller not allowed to see roles.
     */
    val role: RoleRef? = null
)

data class RoleRef(
    val id: String,
    val name: String
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
    val roleId: String?,
    /** Konto nie jest zablokowane. NIE znaczy, że pracownik je aktywował - patrz [invitationPending]. */
    val isActive: Boolean,
    val hasPinConfigured: Boolean = false,
    /** Login konta - na ten adres idzie zaproszenie. */
    val email: String? = null,
    /** Pracownik jeszcze nie aktywował konta z zaproszenia (nie ustawił hasła, nie wszedł do aplikacji). */
    val invitationPending: Boolean = false,
    /** Kiedy doszło ostatnie zaproszenie; null, gdy nie czeka albo wysyłka się nie udała. */
    val invitationSentAt: Instant? = null,
    /** Do kiedy działa link z ostatniego zaproszenia. */
    val invitationExpiresAt: Instant? = null
)

data class ResendInvitationResponse(
    val sentAt: Instant,
    val expiresAt: Instant
)

data class EmployeeDetailResponse(
    val id: String,
    val userId: String?,
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

private fun Employee.toListItem(
    roleByUser: Map<String, RoleRef> = emptyMap(),
    pendingUserIds: Set<String> = emptySet()
) = EmployeeListItem(
    id = id.toString(),
    firstName = firstName,
    lastName = lastName,
    fullName = fullName(),
    email = email,
    phone = phone,
    hasAccount = userId != null,
    accountPending = userId?.let { it.toString() in pendingUserIds } ?: false,
    role = userId?.let { roleByUser[it.toString()] }
)

private fun Employee.toDetailResponse(accountInfo: EmployeeAccountInfo? = null) = EmployeeDetailResponse(
    id = id.toString(),
    userId = userId?.toString(),
    firstName = firstName,
    lastName = lastName,
    fullName = fullName(),
    phone = phone,
    email = email,
    account = accountInfo,
    createdAt = createdAt,
    updatedAt = updatedAt
)

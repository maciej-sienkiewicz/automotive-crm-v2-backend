package pl.detailing.crm.task

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.task.archive.ListArchivedTasksHandler
import pl.detailing.crm.task.archive.ListArchivedTasksQuery
import pl.detailing.crm.task.create.CreateTaskCommand
import pl.detailing.crm.task.create.CreateTaskHandler
import pl.detailing.crm.task.delete.DeleteTaskCommand
import pl.detailing.crm.task.delete.DeleteTaskHandler
import pl.detailing.crm.task.domain.TaskVisibilityType
import pl.detailing.crm.task.list.ListTasksHandler
import pl.detailing.crm.task.list.ListTasksQuery
import pl.detailing.crm.task.update.UpdateTaskCommand
import pl.detailing.crm.task.update.UpdateTaskHandler
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

// TASKS_VIEW covers viewing and completing tasks; creating/assigning and deleting
// require TASKS_MANAGE (method-level overrides below).
@RequiresPermission(Permission.TASKS_VIEW)
@RestController
@RequestMapping("/api/v1/tasks")
class TasksController(
    private val listTasksHandler: ListTasksHandler,
    private val createTaskHandler: CreateTaskHandler,
    private val updateTaskHandler: UpdateTaskHandler,
    private val deleteTaskHandler: DeleteTaskHandler,
    private val listArchivedTasksHandler: ListArchivedTasksHandler,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository
) {

    /**
     * GET /api/v1/tasks
     */
    @GetMapping
    fun getTasks(): ResponseEntity<List<TaskDto>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val tasks = listTasksHandler.handle(
            ListTasksQuery(
                studioId = principal.studioId,
                userId = principal.userId,
                isOwner = principal.isOwner
            )
        )

        ResponseEntity.ok(tasks)
    }

    /**
     * GET /api/v1/tasks/visibility-options
     * Returns assignable users and roles for the task visibility picker.
     */
    @GetMapping("/visibility-options")
    fun getVisibilityOptions(): ResponseEntity<TaskVisibilityOptionsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val users = userRepository.findActiveByStudioId(principal.studioId.value)
            .map { TaskVisibilityUser(userId = it.id.toString(), fullName = "${it.firstName} ${it.lastName}") }

        val roles = roleRepository.findByStudioId(principal.studioId.value)
            .map { TaskVisibilityRole(roleId = it.id.toString(), name = it.name) }

        ResponseEntity.ok(TaskVisibilityOptionsResponse(users = users, roles = roles))
    }

    /**
     * GET /api/v1/tasks/archive
     */
    @GetMapping("/archive")
    fun getArchivedTasks(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) search: String?
    ): ResponseEntity<ArchivedTasksPage> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = listArchivedTasksHandler.handle(
            ListArchivedTasksQuery(
                studioId = principal.studioId,
                page = maxOf(1, page),
                pageSize = maxOf(1, minOf(100, size)),
                search = search
            )
        )

        ResponseEntity.ok(result)
    }

    /**
     * POST /api/v1/tasks
     */
    @PostMapping
    @RequiresPermission(Permission.TASKS_MANAGE)
    fun createTask(@RequestBody request: CreateTaskRequest): ResponseEntity<TaskDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val visibilityType = runCatching { TaskVisibilityType.valueOf(request.visibilityType) }
            .getOrDefault(TaskVisibilityType.ALL)
        val visibleToUserIds = request.visibleToUserIds
            ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: emptyList()
        val visibleToRoleId = request.visibleToRoleId?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        val task = createTaskHandler.handle(
            CreateTaskCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                title = request.title,
                meta = request.meta,
                visibilityType = visibilityType,
                visibleToUserIds = visibleToUserIds,
                visibleToRoleId = visibleToRoleId
            )
        )

        ResponseEntity.status(HttpStatus.CREATED).body(task.toDto(createdByUserName = principal.fullName))
    }

    /**
     * PATCH /api/v1/tasks/{id}
     */
    @PatchMapping("/{id}")
    fun updateTask(
        @PathVariable id: String,
        @RequestBody request: UpdateTaskRequest
    ): ResponseEntity<TaskDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val task = updateTaskHandler.handle(
            UpdateTaskCommand(
                taskId = TaskId.fromString(id),
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                title = request.title,
                meta = request.meta,
                done = request.done
            )
        )

        ResponseEntity.ok(task.toDto())
    }

    /**
     * DELETE /api/v1/tasks/{id}
     */
    @DeleteMapping("/{id}")
    @RequiresPermission(Permission.TASKS_MANAGE)
    fun deleteTask(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        deleteTaskHandler.handle(
            DeleteTaskCommand(
                taskId = TaskId.fromString(id),
                studioId = principal.studioId,
                userId = principal.userId
            )
        )

        ResponseEntity.noContent().build()
    }
}

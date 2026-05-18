package pl.detailing.crm.task

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.task.archive.ListArchivedTasksHandler
import pl.detailing.crm.task.archive.ListArchivedTasksQuery
import pl.detailing.crm.task.create.CreateTaskCommand
import pl.detailing.crm.task.create.CreateTaskHandler
import pl.detailing.crm.task.delete.DeleteTaskCommand
import pl.detailing.crm.task.delete.DeleteTaskHandler
import pl.detailing.crm.task.list.ListTasksHandler
import pl.detailing.crm.task.list.ListTasksQuery
import pl.detailing.crm.task.update.UpdateTaskCommand
import pl.detailing.crm.task.update.UpdateTaskHandler

@RestController
@RequestMapping("/api/v1/tasks")
class TasksController(
    private val listTasksHandler: ListTasksHandler,
    private val createTaskHandler: CreateTaskHandler,
    private val updateTaskHandler: UpdateTaskHandler,
    private val deleteTaskHandler: DeleteTaskHandler,
    private val listArchivedTasksHandler: ListArchivedTasksHandler
) {

    /**
     * GET /api/v1/tasks
     */
    @GetMapping
    fun getTasks(): ResponseEntity<List<TaskDto>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val tasks = listTasksHandler.handle(
            ListTasksQuery(studioId = principal.studioId)
        )

        ResponseEntity.ok(tasks.map { it.toDto() })
    }

    /**
     * GET /api/v1/tasks/archive
     */
    @GetMapping("/archive")
    fun getArchivedTasks(): ResponseEntity<List<ArchivedTaskDto>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val tasks = listArchivedTasksHandler.handle(
            ListArchivedTasksQuery(studioId = principal.studioId)
        )

        ResponseEntity.ok(tasks.map { it.toArchivedDto() })
    }

    /**
     * POST /api/v1/tasks
     */
    @PostMapping
    fun createTask(@RequestBody request: CreateTaskRequest): ResponseEntity<TaskDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val task = createTaskHandler.handle(
            CreateTaskCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                title = request.title,
                meta = request.meta
            )
        )

        ResponseEntity.status(HttpStatus.CREATED).body(task.toDto())
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

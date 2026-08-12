package pl.detailing.crm.task.my

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.TaskId

/**
 * Self-service task surface ("Powiadomienia") — deliberately NOT gated by any
 * permission: every endpoint operates exclusively on tasks already visible to
 * the authenticated user under [pl.detailing.crm.task.domain.TaskVisibility]
 * (targeted at them, their role, or the whole studio). This is the only way a
 * role without TASKS_VIEW / dashboard access learns about work assigned to it.
 *
 * Listed as SELF_SERVICE in AuthorizationSurfaceScanTest.
 */
@RestController
@RequestMapping("/api/v1/my/tasks")
class MyTasksController(
    private val myTasksService: MyTasksService
) {

    /** GET /api/v1/my/tasks — tasks visible to me, newest first, with unread flags. */
    @GetMapping
    fun myTasks(): ResponseEntity<List<MyTaskDto>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(myTasksService.list(principal.studioId, principal.userId, principal.isOwner))
    }

    /** GET /api/v1/my/tasks/summary — badge counts (pending + unread). */
    @GetMapping("/summary")
    fun summary(): ResponseEntity<MyTasksSummaryDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(myTasksService.summary(principal.studioId, principal.userId, principal.isOwner))
    }

    /** POST /api/v1/my/tasks/mark-read — marks all my visible tasks as read; idempotent. */
    @PostMapping("/mark-read")
    fun markAllRead(): ResponseEntity<MyTasksSummaryDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(myTasksService.markAllRead(principal.studioId, principal.userId, principal.isOwner))
    }

    /** PATCH /api/v1/my/tasks/{taskId}/done — complete/uncomplete a task visible to me. */
    @PatchMapping("/{taskId}/done")
    fun setDone(
        @PathVariable taskId: String,
        @RequestBody request: SetMyTaskDoneRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        myTasksService.setDone(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            isOwner = principal.isOwner,
            taskId = TaskId.fromString(taskId),
            done = request.done
        )
        ResponseEntity.noContent().build()
    }
}

data class SetMyTaskDoneRequest(val done: Boolean)

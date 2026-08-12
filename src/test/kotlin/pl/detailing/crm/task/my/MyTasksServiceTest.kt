package pl.detailing.crm.task.my

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.task.domain.Task
import pl.detailing.crm.task.infrastructure.TaskEntity
import pl.detailing.crm.task.infrastructure.TaskReadEntity
import pl.detailing.crm.task.infrastructure.TaskReadRepository
import pl.detailing.crm.task.infrastructure.TaskRepository
import pl.detailing.crm.task.update.UpdateTaskHandler
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant
import java.util.Optional
import java.util.UUID

class MyTasksServiceTest {

    private val taskRepository = mockk<TaskRepository>()
    private val taskReadRepository = mockk<TaskReadRepository>()
    private val userRepository = mockk<UserRepository>()
    private val updateTaskHandler = mockk<UpdateTaskHandler>()

    private val service = MyTasksService(taskRepository, taskReadRepository, userRepository, updateTaskHandler)

    private val studioId = StudioId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())
    private val otherUserId = UUID.randomUUID()

    private fun task(
        visibilityType: String = "ALL",
        visibleToUserIds: String? = null,
        done: Boolean = false,
        createdBy: UUID = otherUserId
    ) = TaskEntity(
        id = UUID.randomUUID(),
        studioId = studioId.value,
        createdByUserId = createdBy,
        title = "task",
        meta = null,
        done = done,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
        visibilityType = visibilityType,
        visibleToUserIds = visibleToUserIds,
        visibleToRoleId = null
    )

    private fun stubTasks(vararg tasks: TaskEntity) {
        every { taskRepository.findByStudioIdAndDeletedAtIsNullOrderByCreatedAtDesc(studioId.value) } returns tasks.toList()
        every { userRepository.findById(userId.value) } returns Optional.empty()
        every { userRepository.findAllById(any<List<UUID>>()) } returns emptyList()
    }

    @Test
    fun `summary counts only visible pending tasks and marks unseen ones unread`() = runTest {
        val mine = task(visibilityType = "USERS", visibleToUserIds = userId.value.toString())
        val someoneElses = task(visibilityType = "USERS", visibleToUserIds = otherUserId.toString())
        val forEveryone = task(visibilityType = "ALL")
        val alreadyDone = task(visibilityType = "ALL", done = true)
        stubTasks(mine, someoneElses, forEveryone, alreadyDone)
        every { taskReadRepository.findByUserIdAndTaskIdIn(userId.value, any()) } returns
            listOf(TaskReadEntity(taskId = forEveryone.id, userId = userId.value))

        val summary = service.summary(studioId, userId, isOwner = false)

        assertEquals(2, summary.pendingCount) // mine + forEveryone; not someoneElses, not done
        assertEquals(1, summary.unreadCount)  // only mine is unseen
    }

    @Test
    fun `list decorates visible tasks with unread flags`() = runTest {
        val seen = task(visibilityType = "ALL")
        val unseen = task(visibilityType = "USERS", visibleToUserIds = userId.value.toString())
        stubTasks(seen, unseen)
        every { taskReadRepository.findByUserIdAndTaskIdIn(userId.value, any()) } returns
            listOf(TaskReadEntity(taskId = seen.id, userId = userId.value))

        val tasks = service.list(studioId, userId, isOwner = false)

        assertEquals(2, tasks.size)
        assertFalse(tasks.first { it.id == seen.id.toString() }.unread)
        assertTrue(tasks.first { it.id == unseen.id.toString() }.unread)
    }

    @Test
    fun `markAllRead inserts receipts only for unseen tasks`() = runTest {
        val seen = task(visibilityType = "ALL")
        val unseen = task(visibilityType = "ALL")
        stubTasks(seen, unseen)
        every { taskReadRepository.findByUserIdAndTaskIdIn(userId.value, any()) } returns
            listOf(TaskReadEntity(taskId = seen.id, userId = userId.value))
        val saved = slot<List<TaskReadEntity>>()
        every { taskReadRepository.saveAll(capture(saved)) } answers { saved.captured }

        val summary = service.markAllRead(studioId, userId, isOwner = false)

        assertEquals(listOf(unseen.id), saved.captured.map { it.taskId })
        assertEquals(0, summary.unreadCount)
    }

    @Test
    fun `setDone refuses a task that is not visible to the caller`() = runTest {
        val someoneElses = task(visibilityType = "USERS", visibleToUserIds = otherUserId.toString())
        stubTasks(someoneElses)

        assertThrows<EntityNotFoundException> {
            service.setDone(studioId, userId, "Test User", isOwner = false,
                taskId = TaskId(someoneElses.id), done = true)
        }
    }

    @Test
    fun `setDone delegates to UpdateTaskHandler for a visible task`() = runTest {
        val mine = task(visibilityType = "USERS", visibleToUserIds = userId.value.toString())
        stubTasks(mine)
        coEvery { updateTaskHandler.handle(any()) } returns mockk<Task>()

        service.setDone(studioId, userId, "Test User", isOwner = false, taskId = TaskId(mine.id), done = true)

        coVerify(exactly = 1) {
            updateTaskHandler.handle(match { it.taskId.value == mine.id && it.done == true })
        }
    }
}

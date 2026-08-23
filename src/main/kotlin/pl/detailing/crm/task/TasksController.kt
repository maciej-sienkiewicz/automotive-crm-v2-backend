package pl.detailing.crm.task

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.TaskId
import pl.detailing.crm.shared.UnprocessableEntityException
import pl.detailing.crm.shared.ValidationException
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
import pl.detailing.crm.voice.OpenAiTranscriptionService
import java.util.UUID

/** Tytuł zadania jest kolumną VARCHAR(255) — dłuższa transkrypcja idzie do `meta`. */
private const val MAX_TASK_TITLE_LENGTH = 255

/** Ten sam limit co w dyktowaniu z telefonu (`/api/mobile/voice`). */
private const val MAX_VOICE_AUDIO_BYTES = 5 * 1024 * 1024L

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
    private val roleRepository: RoleRepository,
    private val transcriptionService: OpenAiTranscriptionService
) {

    private val log = LoggerFactory.getLogger(TasksController::class.java)

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
     * POST /api/v1/tasks/voice
     *
     * Dyktowanie zadania: nagranie → Whisper → gotowe zadanie. Klient dostaje
     * z powrotem pełny [TaskDto], a nie samo id, żeby lista mogła się odświeżyć
     * bez dodatkowego GET-a.
     *
     * Odpowiednik `/api/mobile/voice/note`, ale dla zalogowanej sesji zamiast
     * tokenu mobilnego — i z tym samym uprawnieniem co ręczne dodanie zadania.
     *
     * Transkrypcja bywa dłuższa niż kolumna tytułu, więc gdy przekroczy limit,
     * tytuł jest przycięty, a `meta` niesie całą treść — nic z podyktowanego
     * tekstu nie ginie.
     */
    @PostMapping("/voice", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @RequiresPermission(Permission.TASKS_MANAGE)
    fun createTaskFromVoice(@RequestPart("audio") audio: MultipartFile): ResponseEntity<TaskDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (audio.isEmpty) throw ValidationException("Plik audio jest pusty")
        if (audio.size > MAX_VOICE_AUDIO_BYTES) {
            throw ValidationException("Plik audio przekracza dozwolony rozmiar (max 5 MB)")
        }

        log.info(
            "[tasks/voice] Nagranie: '{}', {} B, {} — wysyłam do transkrypcji",
            audio.originalFilename, audio.size, audio.contentType
        )

        // Awaria Whispera nie jest błędem danych użytkownika, więc nie udajemy
        // walidacji — wyjątek leci do globalnego handlera i kończy się 500.
        val transcript = transcriptionService
            .transcribe(audio.bytes, audio.originalFilename ?: "task-note.webm")
            .trim()

        // Nagranie dotarło i zostało przetworzone, tylko nie było w nim mowy:
        // żądanie jest poprawne, a mimo to nie da się go wykonać — stąd 422,
        // nie 400. Front rozróżnia te przypadki w komunikacie dla użytkownika.
        if (transcript.isBlank()) {
            log.warn("[tasks/voice] Transkrypcja pusta — nie tworzę zadania")
            throw UnprocessableEntityException("Nie udało się rozpoznać mowy w nagraniu")
        }

        val task = createTaskHandler.handle(
            CreateTaskCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                title = transcript.take(MAX_TASK_TITLE_LENGTH),
                meta = transcript.takeIf { it.length > MAX_TASK_TITLE_LENGTH }
            )
        )

        log.info("[tasks/voice] Zadanie utworzone — id: {}", task.id.value)
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

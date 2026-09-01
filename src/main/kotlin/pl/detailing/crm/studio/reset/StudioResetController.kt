package pl.detailing.crm.studio.reset

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.permission.RequiresOwner
import pl.detailing.crm.shared.NotFoundException
import java.util.UUID

data class StartStudioResetRequest(
    val currentPassword: String,
    val confirmationName: String,
    val wipeCompanyData: Boolean = false
)

data class StudioResetJobResponse(
    val jobId: String,
    val status: String,
    val currentStep: Int,
    val totalSteps: Int,
    val currentStepName: String?,
    val error: String?,
    val createdAt: String,
    val startedAt: String?,
    val finishedAt: String?
) {
    companion object {
        fun from(job: StudioResetJobEntity) = StudioResetJobResponse(
            jobId = job.id.toString(),
            status = job.status.name,
            currentStep = job.currentStep,
            totalSteps = job.totalSteps,
            currentStepName = job.currentStepName,
            error = job.error,
            createdAt = job.createdAt.toString(),
            startedAt = job.startedAt?.toString(),
            finishedAt = job.finishedAt?.toString()
        )
    }
}

/**
 * Wyczyszczenie konta (factory reset) — operacja nieodwracalna, dlatego każdy endpoint
 * jest [RequiresOwner], a start wymaga hasła i przepisania nazwy firmy.
 */
@RestController
@RequestMapping("/api/v1/company/reset")
class StudioResetController(
    private val startStudioResetHandler: StartStudioResetHandler,
    private val studioResetJobRepository: StudioResetJobRepository
) {

    @PostMapping
    @RequiresOwner
    fun startReset(@RequestBody request: StartStudioResetRequest): ResponseEntity<StudioResetJobResponse> =
        runBlocking {
            val principal = SecurityContextHelper.getCurrentUser()
            val job = startStudioResetHandler.handle(
                StartStudioResetCommand(
                    principal = principal,
                    currentPassword = request.currentPassword,
                    confirmationName = request.confirmationName,
                    wipeCompanyData = request.wipeCompanyData
                )
            )
            ResponseEntity.status(HttpStatus.ACCEPTED).body(StudioResetJobResponse.from(job))
        }

    /** Ostatni job studia — pozwala frontendowi podjąć trwający reset po odświeżeniu strony. */
    @GetMapping("/latest")
    @RequiresOwner
    fun getLatest(): ResponseEntity<StudioResetJobResponse?> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val job = withContext(Dispatchers.IO) {
            studioResetJobRepository.findFirstByStudioIdOrderByCreatedAtDesc(principal.studioId.value)
        }
        ResponseEntity.ok(job?.let { StudioResetJobResponse.from(it) })
    }

    @GetMapping("/{jobId}")
    @RequiresOwner
    fun getStatus(@PathVariable jobId: UUID): ResponseEntity<StudioResetJobResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val job = withContext(Dispatchers.IO) {
            studioResetJobRepository.findByIdAndStudioId(jobId, principal.studioId.value)
        } ?: throw NotFoundException("Nie znaleziono zadania czyszczenia konta")
        ResponseEntity.ok(StudioResetJobResponse.from(job))
    }
}

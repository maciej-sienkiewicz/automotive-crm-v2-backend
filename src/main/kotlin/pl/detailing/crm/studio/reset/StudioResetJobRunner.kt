package pl.detailing.crm.studio.reset

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Duration
import java.time.Instant

/**
 * Wykonuje joby wyczyszczenia konta poza cyklem żądania HTTP.
 *
 * Wzorzec transakcyjny jak w [pl.detailing.crm.demo.DemoCleanupJob]: driver @Scheduled +
 * [TransactionTemplate] per krok. Świadomie NIE jedna transakcja na całość — purge ~140
 * tabel trzymałby locki i timeouty, a @Transactional na suspend/withContext to
 * udokumentowana pułapka tego kodu (patrz komentarze w SignupHandler).
 *
 * Krok i zapis postępu commitują się razem, więc po padzie instancji job wznawia się
 * dokładnie od pierwszego niezatwierdzonego kroku (kroki są idempotentne). Przejęcie
 * joba jest atomowe ([StudioResetJobRepository.claim] / [reclaimStale]), więc dwie
 * instancje aplikacji nie wykonają go równolegle.
 */
@Component
class StudioResetJobRunner(
    private val jobRepository: StudioResetJobRepository,
    private val purger: StudioDataPurger,
    private val finalizer: StudioResetFinalizer,
    private val s3StudioPurger: S3StudioPurger,
    private val auditService: AuditService,
    private val transactionTemplate: TransactionTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Job RUNNING bez heartbeatu przez ten czas uznajemy za porzucony przez martwą
         * instancję. Pojedynczy krok (bulk delete na największej tabeli) musi mieścić
         * się poniżej tego progu z zapasem.
         */
        private val STALE_AFTER: Duration = Duration.ofMinutes(10)
    }

    /**
     * Pliki S3 na końcu: krok jest wolny (paginacja po prefiksie), idempotentny
     * i nietransakcyjny, a jego powtórka po awarii nic nie kosztuje.
     */
    fun plan(): List<StudioResetStep> =
        purger.steps() + finalizer.steps() + StudioResetStep("Pliki (S3)") { ctx ->
            s3StudioPurger.purge(ctx.studioId)
        }

    @Scheduled(fixedDelay = 5_000)
    fun run() {
        val jobs = jobRepository.findRunnable()
        if (jobs.isEmpty()) return

        jobs.forEach { job ->
            try {
                process(job)
            } catch (e: Exception) {
                logger.error("Account reset failed: jobId={}, studioId={}: {}", job.id, job.studioId, e.message, e)
                markFailed(job, e)
            }
        }
    }

    private fun process(job: StudioResetJobEntity) {
        val now = Instant.now()
        val claimed = transactionTemplate.execute {
            when (job.status) {
                StudioResetJobStatus.PENDING -> jobRepository.claim(job.id, now)
                StudioResetJobStatus.RUNNING -> jobRepository.reclaimStale(job.id, now, now.minus(STALE_AFTER))
                else -> 0
            }
        } ?: 0
        if (claimed == 0) return

        // Odbicie skutków claim() w odłączonej encji — kolejne save() nie może cofnąć
        // statusu do PENDING, bo job stałby się widoczny do ponownego przejęcia.
        job.status = StudioResetJobStatus.RUNNING
        job.startedAt = now

        val steps = plan()
        val context = StudioResetContext(
            studioId = job.studioId,
            keepUserId = job.requestedBy,
            wipeCompanyData = job.wipeCompanyData
        )

        transactionTemplate.executeWithoutResult {
            job.totalSteps = steps.size
            job.currentStepName = steps.getOrNull(job.currentStep)?.name
            jobRepository.save(job)
        }

        logger.info(
            "Account reset {}: jobId={}, studioId={}, fromStep={}/{}",
            if (job.currentStep == 0) "started" else "resumed", job.id, job.studioId, job.currentStep, steps.size
        )

        for (index in job.currentStep until steps.size) {
            val step = steps[index]
            transactionTemplate.executeWithoutResult {
                step.execute(context)
                jobRepository.advance(job.id, index + 1, steps.getOrNull(index + 1)?.name, Instant.now())
            }
            job.currentStep = index + 1
            job.currentStepName = steps.getOrNull(index + 1)?.name
            logger.info("Account reset step done: jobId={}, step {}/{} '{}'", job.id, index + 1, steps.size, step.name)
        }

        transactionTemplate.executeWithoutResult {
            job.status = StudioResetJobStatus.COMPLETED
            job.currentStep = steps.size
            job.currentStepName = null
            job.finishedAt = Instant.now()
            jobRepository.save(job)
        }

        auditService.logSync(
            LogAuditCommand(
                studioId = StudioId(job.studioId),
                userId = UserId(job.requestedBy),
                userDisplayName = job.requestedByName,
                module = AuditModule.STUDIO,
                entityId = job.studioId.toString(),
                action = AuditAction.ACCOUNT_RESET_COMPLETED,
                metadata = mapOf("jobId" to job.id.toString())
            )
        )

        logger.warn("Account reset completed: jobId={}, studioId={}", job.id, job.studioId)
    }

    private fun markFailed(job: StudioResetJobEntity, cause: Exception) {
        try {
            transactionTemplate.executeWithoutResult {
                job.status = StudioResetJobStatus.FAILED
                job.error = cause.message ?: cause.javaClass.simpleName
                job.finishedAt = Instant.now()
                jobRepository.save(job)
            }
            auditService.logSync(
                LogAuditCommand(
                    studioId = StudioId(job.studioId),
                    userId = UserId(job.requestedBy),
                    userDisplayName = job.requestedByName,
                    module = AuditModule.STUDIO,
                    entityId = job.studioId.toString(),
                    action = AuditAction.ACCOUNT_RESET_FAILED,
                    metadata = mapOf(
                        "jobId" to job.id.toString(),
                        "step" to (job.currentStepName ?: job.currentStep.toString())
                    )
                )
            )
        } catch (e: Exception) {
            logger.error("Failed to mark reset job as FAILED: jobId={}: {}", job.id, e.message, e)
        }
    }
}

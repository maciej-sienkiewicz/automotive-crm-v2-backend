package pl.detailing.crm.studio.reset

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import java.time.Instant
import java.util.UUID

/** Wykonuje callbacki synchronicznie — testujemy orkiestrację, nie transakcje. */
private class NoOpTransactionManager : PlatformTransactionManager {
    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
        SimpleTransactionStatus()

    override fun commit(status: TransactionStatus) = Unit
    override fun rollback(status: TransactionStatus) = Unit
}

class StudioResetJobRunnerTest {

    private val jobRepository = mockk<StudioResetJobRepository>(relaxed = true)
    private val purger = mockk<StudioDataPurger>()
    private val finalizer = mockk<StudioResetFinalizer>()
    private val s3StudioPurger = mockk<S3StudioPurger>()
    private val auditService = mockk<AuditService>()

    private val runner = StudioResetJobRunner(
        jobRepository, purger, finalizer, s3StudioPurger, auditService,
        TransactionTemplate(NoOpTransactionManager())
    )

    private val studioId = UUID.randomUUID()
    private val ownerId = UUID.randomUUID()

    private val executed = mutableListOf<String>()

    private fun step(name: String, failing: Boolean = false) = StudioResetStep(name) {
        executed.add(name)
        if (failing) throw IllegalStateException("step '$name' exploded")
    }

    private fun job(status: StudioResetJobStatus = StudioResetJobStatus.PENDING, currentStep: Int = 0) =
        StudioResetJobEntity(
            studioId = studioId,
            requestedBy = ownerId,
            requestedByName = "Jan Właściciel",
            status = status,
            currentStep = currentStep
        )

    @BeforeEach
    fun setUp() {
        executed.clear()
        // Relaxed mock zwróciłby z generycznego save() gołe Object — a runner używa wyniku.
        every { jobRepository.save(any()) } answers { firstArg() }
        every { purger.steps() } returns listOf(step("Klienci"), step("Wizyty"))
        every { finalizer.steps() } returns listOf(step("Ustawienia domyślne"))
        every { s3StudioPurger.purge(studioId) } answers { executed.add("S3"); 7 }
        every { auditService.logSync(any()) } just Runs
    }

    @Test
    fun `plan konczy sie czyszczeniem S3 po krokach bazodanowych i finalizacji`() {
        val plan = runner.plan()
        assertEquals(listOf("Klienci", "Wizyty", "Ustawienia domyślne", "Pliki (S3)"), plan.map { it.name })
    }

    @Test
    fun `job PENDING jest przejmowany atomowo i wykonywany w calosci`() {
        val job = job()
        every { jobRepository.findRunnable() } returns listOf(job)
        every { jobRepository.claim(job.id, any()) } returns 1

        runner.run()

        assertEquals(listOf("Klienci", "Wizyty", "Ustawienia domyślne", "S3"), executed)
        assertEquals(StudioResetJobStatus.COMPLETED, job.status)
        assertEquals(4, job.currentStep)
        assertEquals(4, job.totalSteps)
        assertNotNull(job.finishedAt)
        verify { jobRepository.advance(job.id, 1, "Wizyty", any()) }
        verify { jobRepository.advance(job.id, 4, null, any()) }
    }

    @Test
    fun `job przejety przez inna instancje nie jest wykonywany drugi raz`() {
        val job = job()
        every { jobRepository.findRunnable() } returns listOf(job)
        every { jobRepository.claim(job.id, any()) } returns 0

        runner.run()

        assertTrue(executed.isEmpty())
        assertEquals(StudioResetJobStatus.PENDING, job.status)
    }

    @Test
    fun `job RUNNING z zywym heartbeatem nie jest przejmowany`() {
        val job = job(status = StudioResetJobStatus.RUNNING).apply { startedAt = Instant.now() }
        every { jobRepository.findRunnable() } returns listOf(job)
        every { jobRepository.reclaimStale(job.id, any(), any()) } returns 0

        runner.run()

        assertTrue(executed.isEmpty())
    }

    @Test
    fun `porzucony job RUNNING wznawia sie od pierwszego niezatwierdzonego kroku`() {
        val job = job(status = StudioResetJobStatus.RUNNING, currentStep = 2)
        every { jobRepository.findRunnable() } returns listOf(job)
        every { jobRepository.reclaimStale(job.id, any(), any()) } returns 1

        runner.run()

        // Kroki 0 i 1 ("Klienci", "Wizyty") były już zatwierdzone przed padem instancji.
        assertEquals(listOf("Ustawienia domyślne", "S3"), executed)
        assertEquals(StudioResetJobStatus.COMPLETED, job.status)
    }

    @Test
    fun `awaria kroku oznacza job jako FAILED z nazwa kroku i zostawia wpis audytowy`() {
        every { purger.steps() } returns listOf(step("Klienci"), step("Wizyty", failing = true))
        val job = job()
        every { jobRepository.findRunnable() } returns listOf(job)
        every { jobRepository.claim(job.id, any()) } returns 1

        val audited = mutableListOf<LogAuditCommand>()
        every { auditService.logSync(capture(audited)) } just Runs

        runner.run()

        assertEquals(StudioResetJobStatus.FAILED, job.status)
        assertEquals("step 'Wizyty' exploded", job.error)
        assertNotNull(job.finishedAt)
        assertEquals(listOf("Klienci", "Wizyty"), executed)
        assertEquals(AuditAction.ACCOUNT_RESET_FAILED, audited.single().action)
        assertEquals("Wizyty", audited.single().metadata["step"])
    }

    @Test
    fun `ukonczony reset zostawia krytyczny wpis audytowy`() {
        val job = job()
        every { jobRepository.findRunnable() } returns listOf(job)
        every { jobRepository.claim(job.id, any()) } returns 1

        val audited = slot<LogAuditCommand>()
        every { auditService.logSync(capture(audited)) } just Runs

        runner.run()

        assertEquals(AuditAction.ACCOUNT_RESET_COMPLETED, audited.captured.action)
        assertEquals(studioId, audited.captured.studioId.value)
        assertEquals("Jan Właściciel", audited.captured.userDisplayName)
    }

    @Test
    fun `awaria jednego joba nie zatrzymuje pozostalych`() {
        every { purger.steps() } returns listOf(step("Klienci", failing = true))
        val broken = job()
        val healthy = job()
        every { jobRepository.findRunnable() } returns listOf(broken, healthy)
        every { jobRepository.claim(any(), any()) } returns 1

        runner.run()

        assertEquals(StudioResetJobStatus.FAILED, broken.status)
        assertEquals(StudioResetJobStatus.FAILED, healthy.status)
        // Oba joby dostały próbę wykonania — pierwszy wybuchł, ale drugi też wystartował.
        assertEquals(listOf("Klienci", "Klienci"), executed)
    }
}

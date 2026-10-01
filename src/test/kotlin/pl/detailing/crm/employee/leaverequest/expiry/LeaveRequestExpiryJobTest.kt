package pl.detailing.crm.employee.leaverequest.expiry

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.employee.leaverequest.LeaveRequestFixtures
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestOrigin
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.pdf.LeaveRequestDocumentService
import pl.detailing.crm.shared.RecordingTransactionManager
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.UUID

/** Porzucone szkice znikają po dobie — także te, które administrator wprowadził w imieniu pracownika. */
class LeaveRequestExpiryJobTest {

    private val studio = StudioId.random()
    private val repository = mockk<LeaveRequestRepository>()
    private val documents = mockk<LeaveRequestDocumentService>()
    private val job = LeaveRequestExpiryJob(repository, documents, RecordingTransactionManager().template(), 24)

    private val selfService = LeaveRequestFixtures.request(studio, UUID.randomUUID(), UUID.randomUUID(), status = LeaveRequestStatus.DRAFT)
    private val onBehalf = LeaveRequestFixtures.request(
        studio, UUID.randomUUID(), null, status = LeaveRequestStatus.DRAFT,
        origin = LeaveRequestOrigin.ON_BEHALF, createdBy = UserId.random().value
    )

    init {
        every { repository.findDraftsCreatedBefore(any(), any()) } returns listOf(selfService, onBehalf)
        every { repository.deleteDraft(any(), studio.value) } returns 1
        coEvery { documents.deleteQuietly(any()) } just Runs
    }

    @Test
    fun `abandoned drafts of both origins are deleted with their files`() {
        assertEquals(2, job.deleteAbandonedDrafts())

        verify(exactly = 1) { repository.deleteDraft(onBehalf.id, studio.value) }
        coVerify(exactly = 1) { documents.deleteQuietly(onBehalf.documentS3Key) }
        coVerify(exactly = 1) { documents.deleteQuietly(selfService.documentS3Key) }
    }

    @Test
    fun `draft signed in the meantime keeps its file`() {
        every { repository.deleteDraft(onBehalf.id, studio.value) } returns 0

        assertEquals(1, job.deleteAbandonedDrafts())
        coVerify(exactly = 0) { documents.deleteQuietly(onBehalf.documentS3Key) }
    }
}

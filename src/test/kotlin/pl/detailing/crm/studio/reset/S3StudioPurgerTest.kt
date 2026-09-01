package pl.detailing.crm.studio.reset

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response
import software.amazon.awssdk.services.s3.model.S3Object
import java.util.UUID

class S3StudioPurgerTest {

    private val s3Client = mockk<S3Client>()
    private val purger = S3StudioPurger(s3Client, "detailing-crm-test")
    private val studioId = UUID.randomUUID()

    private fun listing(keys: List<String>, nextToken: String? = null): ListObjectsV2Response =
        ListObjectsV2Response.builder()
            .contents(keys.map { S3Object.builder().key(it).build() })
            .isTruncated(nextToken != null)
            .nextContinuationToken(nextToken)
            .build()

    @Test
    fun `usuwa wszystkie obiekty spod prefiksu studia, stronami`() {
        val listRequests = mutableListOf<ListObjectsV2Request>()
        every { s3Client.listObjectsV2(capture(listRequests)) } returnsMany listOf(
            listing(listOf("$studioId/visits/a.jpg", "$studioId/protocols/b.pdf"), nextToken = "token-2"),
            listing(listOf("$studioId/logo.png"))
        )
        val deleteRequests = mutableListOf<DeleteObjectsRequest>()
        every { s3Client.deleteObjects(capture(deleteRequests)) } returns
            DeleteObjectsResponse.builder().build()

        val deleted = purger.purge(studioId)

        assertEquals(3, deleted)
        // Każda strona listowania jest zawężona do prefiksu studia — nic spoza tenanta.
        assertEquals(listOf("$studioId/", "$studioId/"), listRequests.map { it.prefix() })
        assertEquals("token-2", listRequests[1].continuationToken())
        assertEquals(
            listOf(listOf("$studioId/visits/a.jpg", "$studioId/protocols/b.pdf"), listOf("$studioId/logo.png")),
            deleteRequests.map { req -> req.delete().objects().map { it.key() } }
        )
    }

    @Test
    fun `pusty prefiks jest bezpieczny - zadnych wywolan delete`() {
        every { s3Client.listObjectsV2(any<ListObjectsV2Request>()) } returns listing(emptyList())

        val deleted = purger.purge(studioId)

        assertEquals(0, deleted)
        verify(exactly = 0) { s3Client.deleteObjects(any<DeleteObjectsRequest>()) }
    }
}

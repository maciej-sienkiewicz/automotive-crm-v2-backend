package pl.detailing.crm.studio.reset

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.Delete
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.ObjectIdentifier
import java.util.UUID

/**
 * Usuwa wszystkie obiekty studia z S3. Każdy klucz w buckecie zaczyna się od
 * `{studioId}/` (patrz DocumentStorageService, PhotoSessionService,
 * S3ProtocolStorageService, S3ConsentStorageService, UserSignatureService),
 * więc czyszczenie sprowadza się do usunięcia prefiksu — stronami po maksymalnie
 * 1000 obiektów, bo tyle przyjmuje DeleteObjects.
 *
 * Operacja jest idempotentna: ponowne uruchomienie na pustym prefiksie nic nie robi.
 */
@Component
class S3StudioPurger(
    private val s3Client: S3Client,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun purge(studioId: UUID): Int {
        val prefix = "$studioId/"
        var deleted = 0
        var continuationToken: String? = null

        do {
            val listing = s3Client.listObjectsV2(
                ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build()
            )

            val keys = listing.contents().map { ObjectIdentifier.builder().key(it.key()).build() }
            if (keys.isNotEmpty()) {
                s3Client.deleteObjects(
                    DeleteObjectsRequest.builder()
                        .bucket(bucketName)
                        .delete(Delete.builder().objects(keys).quiet(true).build())
                        .build()
                )
                deleted += keys.size
            }

            continuationToken = if (listing.isTruncated) listing.nextContinuationToken() else null
        } while (continuationToken != null)

        logger.info("S3 purge complete: studioId={}, deletedObjects={}", studioId, deleted)
        return deleted
    }
}

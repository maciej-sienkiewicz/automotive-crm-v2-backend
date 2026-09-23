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
 * Usuwa wszystkie obiekty studia z S3, stronami po maksymalnie 1000 obiektów, bo tyle
 * przyjmuje DeleteObjects.
 *
 * Pliki studia leżą pod `{studioId}/` (patrz DocumentStorageService, PhotoSessionService,
 * S3ProtocolStorageService, S3ConsentStorageService, UserSignatureService), ale nie wszystkie:
 * miniatury zdjęć są pod `thumbs/{studioId}/`, a pliki tymczasowe pod `temp/{studioId}/`
 * (sesje zdjęć) i `temp/uploads/{studioId}/` (zdjęcia z telefonu przy przyjęciu). Czyszczenie
 * samego `{studioId}/` zostawiało w buckecie miniatury zdjęć studia, które już nie istnieje.
 *
 * Operacja jest idempotentna: ponowne uruchomienie na pustych prefiksach nic nie robi.
 */
@Component
class S3StudioPurger(
    private val s3Client: S3Client,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun purge(studioId: UUID): Int {
        val deleted = prefixesOf(studioId).sumOf { purgePrefix(it) }
        logger.info("S3 purge complete: studioId={}, deletedObjects={}", studioId, deleted)
        return deleted
    }

    private fun purgePrefix(prefix: String): Int {
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

        return deleted
    }

    companion object {
        /** Wszystkie prefiksy, pod którymi mogą leżeć pliki studia. */
        fun prefixesOf(studioId: UUID): List<String> = listOf(
            "$studioId/",
            "thumbs/$studioId/",
            "temp/$studioId/",
            "temp/uploads/$studioId/"
        )
    }
}

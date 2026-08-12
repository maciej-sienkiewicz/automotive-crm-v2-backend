package pl.detailing.crm.gallery.thumbnails

import net.coobird.thumbnailator.Thumbnails
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Generates and stores grid-size thumbnails for uploaded photos.
 *
 * Originals are uploaded directly to S3 by the browser (presigned PUT), so the
 * backend never sees the bytes at upload time. Thumbnails are therefore created
 * after the fact by [ThumbnailBackfillJob], which also covers all photos that
 * existed before this feature shipped.
 *
 * The thumbnail lives under a deterministic sibling key ("thumbs/{originalKey}.jpg")
 * and is uploaded with an immutable Cache-Control header so any future CDN in
 * front of the bucket can cache it indefinitely.
 */
@Service
class PhotoThumbnailService(
    private val s3Client: S3Client,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String,
    @Value("\${gallery.thumbnails.max-dimension:640}") private val maxDimension: Int,
    @Value("\${gallery.thumbnails.jpeg-quality:0.82}") private val jpegQuality: Double
) {

    companion object {
        private val logger = LoggerFactory.getLogger(PhotoThumbnailService::class.java)
        const val THUMBNAIL_KEY_PREFIX = "thumbs/"
        private const val CACHE_CONTROL = "public, max-age=31536000, immutable"
    }

    fun thumbnailKeyFor(originalKey: String): String = "$THUMBNAIL_KEY_PREFIX$originalKey.jpg"

    /**
     * Downloads the original, scales it down to [maxDimension] on the longer edge
     * and stores it as JPEG under the deterministic thumbnail key.
     *
     * @return the thumbnail S3 key
     * @throws NoSuchKeyException when the original object does not exist (yet)
     */
    fun generateThumbnail(originalKey: String): String {
        val originalBytes = s3Client.getObject(
            GetObjectRequest.builder().bucket(bucketName).key(originalKey).build()
        ).readAllBytes()

        val source = ImageIO.read(ByteArrayInputStream(originalBytes))
            ?: throw IllegalArgumentException("Unsupported or corrupted image: $originalKey")

        val thumbnailBytes = ByteArrayOutputStream().use { out ->
            Thumbnails.of(flattenToRgb(source))
                .size(maxDimension, maxDimension)
                .outputFormat("jpg")
                .outputQuality(jpegQuality)
                .toOutputStream(out)
            out.toByteArray()
        }

        val thumbnailKey = thumbnailKeyFor(originalKey)
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucketName)
                .key(thumbnailKey)
                .contentType("image/jpeg")
                .cacheControl(CACHE_CONTROL)
                .build(),
            RequestBody.fromBytes(thumbnailBytes)
        )

        logger.debug(
            "Generated thumbnail {} ({} KB -> {} KB)",
            thumbnailKey, originalBytes.size / 1024, thumbnailBytes.size / 1024
        )
        return thumbnailKey
    }

    // JPEG has no alpha channel — PNG/WebP sources with transparency are flattened onto white.
    private fun flattenToRgb(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_RGB) return source
        val rgb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val graphics = rgb.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, source.width, source.height)
            graphics.drawImage(source, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return rgb
    }
}

package pl.detailing.crm.gallery.thumbnails

import net.coobird.thumbnailator.Thumbnails
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.image.ExifOrientation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CopyObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.MetadataDirective
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
 * The thumbnail lives under a deterministic sibling key ("thumbs/{originalKey}.upright.jpg")
 * and is uploaded with an immutable Cache-Control header so any future CDN in
 * front of the bucket can cache it indefinitely.
 *
 * Miniatura jest stawiana pionowo wg EXIF, zanim zostanie zmniejszona. Telefon zapisuje
 * zdjęcie pionowe jako poziome ze znacznikiem „obróć", a ImageIO i Thumbnailator (skalujący
 * gotowy BufferedImage) znacznik pomijają. Miniatury powstawały więc bokiem, oryginał
 * w przeglądarce stał prosto, a podgląd w galerii i w wizycie „przeskakiwał" o 90°, gdy
 * pełna jakość zastępowała miniaturę.
 *
 * Miniatury sprzed tej poprawki mają stary klucz ("thumbs/{originalKey}.jpg"). Po nim
 * [ThumbnailBackfillJob] rozpoznaje je i generuje od nowa - inny klucz zamiast nadpisania
 * także dlatego, że stary obraz leży z nagłówkiem `immutable` i przeglądarki trzymają go
 * w cache przez rok.
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
        /** Znacznik miniatury postawionej pionowo wg EXIF - patrz opis klasy. */
        const val THUMBNAIL_KEY_SUFFIX = ".upright.jpg"
        private const val CACHE_CONTROL = "public, max-age=31536000, immutable"

        /** Miniatura sprzed poprawki orientacji - do wygenerowania od nowa. */
        fun isLegacyThumbnail(key: String): Boolean =
            key.startsWith(THUMBNAIL_KEY_PREFIX) && !key.endsWith(THUMBNAIL_KEY_SUFFIX)
    }

    fun thumbnailKeyFor(originalKey: String): String = "$THUMBNAIL_KEY_PREFIX$originalKey$THUMBNAIL_KEY_SUFFIX"

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

        val decoded = ImageIO.read(ByteArrayInputStream(originalBytes))
            ?: throw IllegalArgumentException("Unsupported or corrupted image: $originalKey")
        val source = ExifOrientation.upright(decoded, ExifOrientation.read(originalBytes))

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

    /**
     * Stara miniatura przeniesiona pod bieżący klucz bez przeliczania - gdy nowej zrobić się
     * nie da (oryginału już nie ma albo się nie dekoduje). Stara, nawet obrócona, jest lepsza
     * niż żadna, a bez przeniesienia zadanie próbowałoby ją poprawiać w nieskończoność.
     *
     * @return klucz, pod którym miniatura leży teraz
     * @throws NoSuchKeyException gdy nie ma także starej miniatury
     */
    fun adoptLegacyThumbnail(legacyKey: String, originalKey: String): String {
        val targetKey = thumbnailKeyFor(originalKey)
        s3Client.copyObject(
            CopyObjectRequest.builder()
                .sourceBucket(bucketName).sourceKey(legacyKey)
                .destinationBucket(bucketName).destinationKey(targetKey)
                .contentType("image/jpeg")
                .cacheControl(CACHE_CONTROL)
                .metadataDirective(MetadataDirective.REPLACE)
                .build()
        )
        // Starej kopii nie kasujemy tutaj: wolno ją usunąć dopiero, gdy baza wskazuje już
        // nowy klucz - robi to wywołujący po zapisie.
        return targetKey
    }

    /** Usuwa obiekt, nie przerywając zadania, gdy się nie uda - to tylko sprzątanie. */
    fun deleteQuietly(key: String) {
        runCatching {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build())
        }.onFailure { logger.warn("Could not delete obsolete thumbnail {}: {}", key, it.message) }
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

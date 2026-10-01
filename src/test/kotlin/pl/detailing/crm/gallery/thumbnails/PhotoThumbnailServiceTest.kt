package pl.detailing.crm.gallery.thumbnails

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Zgłoszenie z galerii: podgląd pokazywał zdjęcie w innej orientacji i po sekundzie
 * „przeskakiwał". Miniatura powstawała z pikseli zapisanych tak, jak leży matryca,
 * bez znacznika EXIF, a oryginał przeglądarka obracała wg EXIF.
 *
 * Zdjęcie testowe to dokładnie to, co zapisuje telefon trzymany pionowo: piksele
 * poziome (lewa połowa czerwona, prawa niebieska) i Orientation = 6 („obróć o 90°
 * w prawo"). Przeglądarka pokaże je pionowo, czerwienią u góry.
 */
class PhotoThumbnailServiceTest {

    private val s3 = mockk<S3Client>()
    private val service = PhotoThumbnailService(s3, "bucket", maxDimension = 64, jpegQuality = 0.9)

    private fun jpegFromPhone(orientation: Int): ByteArray {
        val sensor = BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB)
        val g = sensor.createGraphics()
        g.color = Color.RED; g.fillRect(0, 0, 200, 200)
        g.color = Color.BLUE; g.fillRect(200, 0, 200, 200)
        g.dispose()
        val jpeg = ByteArrayOutputStream().also { ImageIO.write(sensor, "jpg", it) }.toByteArray()
        return withExifOrientation(jpeg, orientation)
    }

    /** Segment APP1 z jednym wpisem IFD0: Orientation (0x0112, SHORT), wstawiony po SOI. */
    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // nagłówek TIFF, IFD0 pod 8
            0x00, 0x01,                                                             // 1 wpis
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,                         // Orientation, SHORT, 1
            0x00, orientation.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,                                                 // brak następnego IFD
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + app1 + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun thumbnailOf(original: ByteArray): Pair<String, BufferedImage> {
        every { s3.getObject(any<GetObjectRequest>()) } answers {
            ResponseInputStream(GetObjectResponse.builder().build(), AbortableInputStream.create(ByteArrayInputStream(original)))
        }
        val request = slot<PutObjectRequest>()
        val body = slot<RequestBody>()
        every { s3.putObject(capture(request), capture(body)) } returns PutObjectResponse.builder().build()

        val key = service.generateThumbnail("studio/visits/photo.jpg")
        assertEquals(key, request.captured.key())
        val bytes = body.captured.contentStreamProvider().newStream().use { it.readAllBytes() }
        return key to ImageIO.read(ByteArrayInputStream(bytes))
    }

    private fun isRed(rgb: Int) = Color(rgb).let { it.red > 180 && it.blue < 80 }
    private fun isBlue(rgb: Int) = Color(rgb).let { it.blue > 180 && it.red < 80 }

    @Test
    fun `zdjecie z telefonu trzymanego pionowo daje pionowa miniature, jak oryginal w przegladarce`() {
        val (_, thumb) = thumbnailOf(jpegFromPhone(orientation = 6))

        assertTrue(thumb.height > thumb.width, "miniatura ${thumb.width}×${thumb.height} powinna być pionowa")
        assertEquals(64, thumb.height)
        assertTrue(isRed(thumb.getRGB(thumb.width / 2, thumb.height / 4)), "czerwień u góry")
        assertTrue(isBlue(thumb.getRGB(thumb.width / 2, thumb.height * 3 / 4)), "niebieski u dołu")
    }

    @Test
    fun `zdjecie bez obrotu zostaje poziome`() {
        val (_, thumb) = thumbnailOf(jpegFromPhone(orientation = 1))

        assertTrue(thumb.width > thumb.height)
        assertTrue(isRed(thumb.getRGB(thumb.width / 4, thumb.height / 2)))
    }

    @Test
    fun `nowa miniatura ma klucz, po ktorym odroznia sie od sprzed poprawki`() {
        val (key, _) = thumbnailOf(jpegFromPhone(orientation = 6))

        assertEquals("thumbs/studio/visits/photo.jpg.upright.jpg", key)
        assertFalse(PhotoThumbnailService.isLegacyThumbnail(key))
        assertTrue(PhotoThumbnailService.isLegacyThumbnail("thumbs/studio/visits/photo.jpg.jpg"))
        // Oryginał wpisany jako własna miniatura (brak pliku) nie jest miniaturą do poprawy.
        assertFalse(PhotoThumbnailService.isLegacyThumbnail("studio/visits/photo.jpg"))
    }
}

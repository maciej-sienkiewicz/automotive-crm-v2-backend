package pl.detailing.crm.comms.signature

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import pl.detailing.crm.config.GlobalExceptionHandler
import pl.detailing.crm.security.TenantIsolationAuditService
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.logo.CompanyLogoProcessor
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Optional
import java.util.UUID
import javax.imageio.ImageIO

private fun image(width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_RGB, paint: (BufferedImage) -> Unit = {
    val g = it.createGraphics(); g.color = Color(200, 40, 45); g.fillRect(0, 0, width, height); g.dispose()
}): BufferedImage = BufferedImage(width, height, type).also(paint)

private fun BufferedImage.encode(format: String): ByteArray =
    ByteArrayOutputStream().use { ImageIO.write(this, format, it); it.toByteArray() }

private fun ByteArray.decode(): BufferedImage = ImageIO.read(ByteArrayInputStream(this))

/**
 * Zdjęcie i logo stopki wychodzą pod publiczny adres do cudzych skrzynek: zawsze
 * re-enkodowane, w rozmiarze stopki, zdjęcie jako kwadrat (Outlook nie zna object-fit).
 */
class MailSignatureImageProcessorTest {

    private val processor = MailSignatureImageProcessor(CompanyLogoProcessor())

    @Test
    fun `zdjecie jest wycinane do kwadratu i zmniejszane do rozmiaru stopki`() {
        val result = processor.process(image(1200, 800).encode("png"), MailSignatureImageKind.PHOTO)

        assertEquals("jpg", result.extension)
        assertEquals(MailSignatureImageProcessor.PHOTO_EDGE_PX, result.width)
        assertEquals(result.width, result.height)
        val decoded = result.bytes.decode()
        assertEquals(result.width, decoded.width)
        assertEquals(result.height, decoded.height)
    }

    @Test
    fun `male zdjecie nie jest powiekszane, za male jest odrzucane`() {
        val small = processor.process(image(200, 150).encode("jpg"), MailSignatureImageKind.PHOTO)
        assertEquals(150, small.width)

        assertThrows<ValidationException> {
            processor.process(image(80, 80).encode("png"), MailSignatureImageKind.PHOTO)
        }
    }

    @Test
    fun `przezroczyste zdjecie dostaje biale tlo zamiast czarnego`() {
        val transparent = image(200, 200, BufferedImage.TYPE_INT_ARGB) { }
        val result = processor.process(transparent.encode("png"), MailSignatureImageKind.PHOTO).bytes.decode()

        val rgb = Color(result.getRGB(100, 100))
        assertTrue(rgb.red > 240 && rgb.green > 240 && rgb.blue > 240, "piksel: $rgb")
    }

    @Test
    fun `logo traci puste marginesy, zachowuje przezroczystosc i miesci sie w limicie`() {
        val logo = image(1600, 800, BufferedImage.TYPE_INT_ARGB) {
            val g = it.createGraphics(); g.color = Color.BLACK; g.fillRect(400, 200, 800, 400); g.dispose()
        }
        val result = processor.process(logo.encode("png"), MailSignatureImageKind.LOGO)

        assertEquals("png", result.extension)
        assertEquals(MailSignatureImageProcessor.LOGO_MAX_EDGE_PX, result.width)
        assertEquals(240, result.height)
        assertTrue(result.bytes.decode().colorModel.hasAlpha())
    }

    @Test
    fun `svg, gif i smieci sa odrzucane`() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".toByteArray()
        assertThrows<ValidationException> { processor.process(svg, MailSignatureImageKind.LOGO) }
        assertThrows<ValidationException> { processor.process(image(200, 200).encode("gif"), MailSignatureImageKind.PHOTO) }
        assertThrows<ValidationException> { processor.process("<html>".toByteArray(), MailSignatureImageKind.PHOTO) }
        assertThrows<ValidationException> { processor.process(ByteArray(0), MailSignatureImageKind.PHOTO) }
    }
}

class MailSignatureImageServiceTest {

    private val studioId = StudioId.random()
    private val s3 = mockk<S3Client>()
    private val settingsRepository = mockk<StudioSettingsRepository>()
    private val service = MailSignatureImageService(
        s3,
        MailSignatureImageProcessor(CompanyLogoProcessor()),
        settingsRepository,
        bucketName = "bucket",
        publicBaseUrl = "https://api.example.pl/"
    )

    @Test
    fun `wgrany obrazek dostaje absolutny adres z hashem tresci pod prefiksem studia`() {
        val request = slot<PutObjectRequest>()
        every { s3.putObject(capture(request), any<RequestBody>()) } returns mockk()

        val url = service.upload(studioId, MailSignatureImageKind.PHOTO, image(400, 400).encode("png"))

        val match = Regex("""^https://api\.example\.pl/api/public/mail-signature/${studioId.value}/([0-9a-f]{16})\.jpg$""")
            .matchEntire(url)
        assertTrue(match != null, url)
        assertEquals("${studioId.value}/mail-signature/${match!!.groupValues[1]}.jpg", request.captured.key())
        assertEquals("image/jpeg", request.captured.contentType())
        assertEquals("public, max-age=31536000, immutable", request.captured.cacheControl())
    }

    @Test
    fun `ten sam plik daje ten sam adres`() {
        every { s3.putObject(any<PutObjectRequest>(), any<RequestBody>()) } returns mockk()
        val bytes = image(400, 400).encode("png")

        assertEquals(
            service.upload(studioId, MailSignatureImageKind.LOGO, bytes),
            service.upload(studioId, MailSignatureImageKind.LOGO, bytes)
        )
    }

    @Test
    fun `adres spoza wzorca nie dotyka S3`() {
        assertNull(service.load(studioId.value, "../../x", "jpg"))
        assertNull(service.load(studioId.value, "0123456789abcdef", "svg"))
        verify(exactly = 0) { s3.getObject(any<software.amazon.awssdk.services.s3.model.GetObjectRequest>()) }
    }

    @Test
    fun `studio bez logo dostaje czytelny komunikat`() {
        every { settingsRepository.findById(studioId.value) } returns Optional.of(StudioSettingsEntity(studioId = studioId.value))

        assertThrows<NotFoundException> { service.copyCompanyLogo(studioId) }
    }

    @Test
    fun `katalog ikon jest absolutny i wersjonowany`() {
        assertEquals("https://api.example.pl/api/public/mail-signature/icons/v1", service.iconsBaseUrl)
    }
}

/** Publiczne adresy pobiera klient poczty odbiorcy: bez sesji, z cache na rok. */
class PublicMailSignatureControllerTest {

    private val studioId = UUID.randomUUID()
    private val images = mockk<MailSignatureImageService>()
    private val mockMvc = MockMvcBuilders
        .standaloneSetup(PublicMailSignatureController(images, MailSignatureIcons()))
        .setControllerAdvice(GlobalExceptionHandler(mockk<TenantIsolationAuditService>(relaxed = true)))
        .build()

    @Test
    fun `kazda ikona z listy istnieje w zasobach`() {
        MailSignatureIcons.SETS.forEach { (set, names) ->
            names.forEach { name ->
                mockMvc.perform(get("/api/public/mail-signature/icons/v1/$set/$name.png"))
                    .andExpect(status().isOk)
                    .andExpect(content().contentType("image/png"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"))
            }
        }
    }

    @Test
    fun `ikona spoza listy albo innej wersji to 404`() {
        mockMvc.perform(get("/api/public/mail-signature/icons/v1/mono/whatsapp.png")).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/public/mail-signature/icons/v1/contact/linkedin.png")).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/public/mail-signature/icons/v9/mono/linkedin.png")).andExpect(status().isNotFound)
    }

    @Test
    fun `obrazek stopki ma typ, nosniff i cache na rok`() {
        every { images.load(studioId, "0123456789abcdef", "jpg") } returns
            StoredSignatureImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg")

        mockMvc.perform(get("/api/public/mail-signature/$studioId/0123456789abcdef.jpg"))
            .andExpect(status().isOk)
            .andExpect(content().contentType("image/jpeg"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=31536000, public, immutable"))
    }

    @Test
    fun `nieistniejacy obrazek to 404`() {
        every { images.load(studioId, "fedcba9876543210", "png") } returns null

        mockMvc.perform(get("/api/public/mail-signature/$studioId/fedcba9876543210.png"))
            .andExpect(status().isNotFound)
    }
}

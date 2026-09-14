package pl.detailing.crm.studio.logo

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import java.net.URL
import java.util.Optional
import java.util.UUID

class CompanyLogoServiceTest {

    private val studioId = UUID.randomUUID()
    private val s3Client = mockk<S3Client>()
    private val s3Presigner = mockk<S3Presigner>()
    private val repository = mockk<StudioSettingsRepository>()
    private val service = CompanyLogoService(s3Client, s3Presigner, CompanyLogoProcessor(), repository, "test-bucket")

    private val uploaded = linkedMapOf<String, ByteArray>()
    private val deleted = mutableListOf<String>()

    init {
        val put = slot<PutObjectRequest>()
        val body = slot<RequestBody>()
        every { s3Client.putObject(capture(put), capture(body)) } answers {
            uploaded[put.captured.key()] = body.captured.contentStreamProvider().newStream().readAllBytes()
            PutObjectResponse.builder().build()
        }
        val del = slot<DeleteObjectRequest>()
        every { s3Client.deleteObject(capture(del)) } answers {
            deleted += del.captured.key()
            DeleteObjectResponse.builder().build()
        }
        every { repository.save(any()) } answers { firstArg() }
    }

    private fun stubDownload(key: String, bytes: ByteArray) {
        every { s3Client.getObject(match<GetObjectRequest> { it.key() == key }) } answers {
            ResponseInputStream(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(bytes.inputStream())
            )
        }
    }

    @Test
    fun `podmiana logo odklada warianty pod prefiksem studia i sprzata poprzednie obiekty`() {
        val settings = StudioSettingsEntity(studioId = studioId).apply {
            logoS3Key = "$studioId/logo/logo.png"
        }
        every { repository.findById(studioId) } returns Optional.of(settings)

        val saved = service.replaceLogo(studioId, LogoTestImages.transparentPngWithBox(1200, 300, 20))

        assertEquals(2, uploaded.size)
        assertTrue(uploaded.keys.all { it.startsWith("$studioId/logo/") }, "klucze poza prefiksem studia: ${uploaded.keys}")
        assertEquals(saved.logoS3Key, uploaded.keys.first { it.endsWith("/app.png") })
        assertEquals(saved.logoPrintS3Key, uploaded.keys.first { it.endsWith("/print.png") })
        assertNull(saved.logoVectorS3Key)
        assertEquals(listOf("$studioId/logo/logo.png"), deleted)
    }

    @Test
    fun `SVG dostaje trzeci wariant - oczyszczony wektor`() {
        every { repository.findById(studioId) } returns Optional.empty()

        val saved = service.replaceLogo(studioId, LogoTestImages.WIDE_SVG.toByteArray())

        assertNotNull(saved.logoVectorS3Key)
        assertTrue(saved.logoVectorS3Key!!.endsWith("/vector.svg"))
        assertEquals(3, uploaded.size)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `usuniecie logo czysci klucze i obiekty`() {
        val settings = StudioSettingsEntity(studioId = studioId).apply {
            logoS3Key = "$studioId/logo/abc/app.png"
            logoPrintS3Key = "$studioId/logo/abc/print.png"
            logoVectorS3Key = "$studioId/logo/abc/vector.svg"
        }
        every { repository.findById(studioId) } returns Optional.of(settings)

        service.deleteLogo(studioId)

        assertNull(settings.logoS3Key)
        assertNull(settings.logoPrintS3Key)
        assertNull(settings.logoVectorS3Key)
        assertEquals(3, deleted.size)
        verify(exactly = 1) { repository.save(settings) }
    }

    @Test
    fun `logo na dokumentach respektuje przelacznik`() {
        val settings = StudioSettingsEntity(studioId = studioId).apply {
            logoS3Key = "$studioId/logo/abc/app.png"
            logoPrintS3Key = "$studioId/logo/abc/print.png"
            logoOnDocuments = false
        }
        every { repository.findById(studioId) } returns Optional.of(settings)

        assertNull(service.loadDocumentLogo(studioId))
        verify(exactly = 0) { s3Client.getObject(any<GetObjectRequest>()) }
    }

    @Test
    fun `logo wgrane przed wariantami jest przetwarzane w locie przy pierwszym dokumencie`() {
        val legacyKey = "$studioId/logo/logo.png"
        val settings = StudioSettingsEntity(studioId = studioId).apply { logoS3Key = legacyKey }
        every { repository.findById(studioId) } returns Optional.of(settings)
        stubDownload(legacyKey, LogoTestImages.transparentPngWithBox(1000, 400, 0))
        // Świeżo wgrane warianty czytamy z tego, co przed chwilą poszło do S3.
        every { s3Client.getObject(match<GetObjectRequest> { it.key().endsWith("/print.png") }) } answers {
            ResponseInputStream(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(uploaded.keys.first { it.endsWith("/print.png") }.let { uploaded[it]!! }.inputStream())
            )
        }

        val logo = service.loadDocumentLogo(studioId)

        assertNotNull(logo)
        assertNull(logo!!.vectorSvg)
        assertEquals(LogoSourceFormat.PNG, CompanyLogoProcessor().detectFormat(logo.printPng))
        assertNotNull(settings.logoPrintS3Key, "warianty zostają zapisane — następny dokument nie przetwarza ponownie")
        assertEquals(listOf(legacyKey), deleted)
    }

    @Test
    fun `nowe logo ma staly publiczny adres z hashem, stare podpisany link S3`() {
        val fresh = StudioSettingsEntity(studioId = studioId).apply {
            logoS3Key = "$studioId/logo/0123456789abcdef/app.png"
        }
        assertEquals("/api/public/branding/$studioId/logo/0123456789abcdef/app.png", service.appLogoUrl(fresh))
        assertNull(service.appLogoUrl(StudioSettingsEntity(studioId = studioId)))
        assertNull(service.appLogoUrl(null))

        val presigned = mockk<PresignedGetObjectRequest>()
        every { presigned.url() } returns URL("https://s3.example/legacy?X-Amz-Signature=abc")
        every { s3Presigner.presignGetObject(any<GetObjectPresignRequest>()) } returns presigned
        val legacy = StudioSettingsEntity(studioId = studioId).apply { logoS3Key = "$studioId/logo/logo.png" }

        assertEquals("https://s3.example/legacy?X-Amz-Signature=abc", service.appLogoUrl(legacy))
    }

    @Test
    fun `publiczny adres odpowiada tylko dla aktualnego hasha logo`() {
        val settings = StudioSettingsEntity(studioId = studioId).apply {
            logoS3Key = "$studioId/logo/0123456789abcdef/app.png"
        }
        every { repository.findById(studioId) } returns Optional.of(settings)
        stubDownload("$studioId/logo/0123456789abcdef/app.png", byteArrayOf(1, 2, 3))

        assertEquals(3, service.loadAppLogo(studioId, "0123456789abcdef")!!.size)
        assertNull(service.loadAppLogo(studioId, "fedcba9876543210"), "stary hash po podmianie logo")
        assertNull(service.loadAppLogo(studioId, "../etc/passwd"), "hash spoza formatu")

        val otherStudio = UUID.randomUUID()
        every { repository.findById(otherStudio) } returns Optional.empty()
        assertNull(service.loadAppLogo(otherStudio, "0123456789abcdef"), "cudze studio")
    }

    @Test
    fun `znacznik img w HTML osadza wektor, a bez niego raster, jako data URI`() {
        val svg = DocumentLogo(printPng = byteArrayOf(1), vectorSvg = "<svg/>".toByteArray()).toHtmlImg()
        assertTrue(svg.startsWith("""<img class="company-logo-img" alt="" src="data:image/svg+xml;base64,"""), svg)

        val png = DocumentLogo(printPng = byteArrayOf(1), vectorSvg = null).toHtmlImg()
        assertTrue("data:image/png;base64," in png, png)
    }
}

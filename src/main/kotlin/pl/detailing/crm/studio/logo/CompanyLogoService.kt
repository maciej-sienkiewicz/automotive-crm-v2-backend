package pl.detailing.crm.studio.logo

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Logo gotowe do wstawienia w dokument.
 *
 * @property printPng  PNG do stempla w PDF (PDFBox nie osadza wektorów)
 * @property vectorSvg oczyszczony SVG — w HTML ma pierwszeństwo, bo wektor jest ostry na każdym DPI
 */
class DocumentLogo(
    val printPng: ByteArray,
    val vectorSvg: ByteArray?
) {
    companion object {
        /** `data-field` w szablonach HTML, w które trafia [toHtmlImg]. */
        const val HTML_FIELD_NAME = "companylogo"
    }

    /**
     * Znacznik `<img>` z obrazem osadzonym jako data URI — dokument HTML ma być
     * samowystarczalny, tak jak fonty w szablonach (base64), a nie zależeć od
     * podpisanych linków S3, które wygasają. `<img>` zamiast inline `<svg>`:
     * przeglądarka nie wykonuje skryptów z SVG w `<img>`, a `<style>` z wnętrza
     * logo nie wycieka na resztę dokumentu.
     */
    fun toHtmlImg(): String {
        val (mime, bytes) = if (vectorSvg != null) "image/svg+xml" to vectorSvg else "image/png" to printPng
        val base64 = Base64.getEncoder().encodeToString(bytes)
        return """<img class="company-logo-img" alt="" src="data:$mime;base64,$base64">"""
    }
}

/**
 * Przechowywanie wariantów logo studia w S3 i ich odczyt na potrzeby dokumentów.
 *
 * Klucze: `{studioId}/logo/{hash}/app.png|print.png|vector.svg`. Prefiks `{studioId}/`
 * jest konwencją całego bucketu (S3StudioPurger kasuje studio po prefiksie), a katalog
 * z hashem oryginału daje nowy adres po każdej podmianie — cache przeglądarki i CDN
 * nie pokażą starego logo pod nowym linkiem.
 */
@Service
class CompanyLogoService(
    private val s3Client: S3Client,
    private val s3Presigner: S3Presigner,
    private val processor: CompanyLogoProcessor,
    private val studioSettingsRepository: StudioSettingsRepository,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {
    companion object {
        private val logger = LoggerFactory.getLogger(CompanyLogoService::class.java)
        private const val CACHE_CONTROL = "public, max-age=31536000, immutable"
        /** Prefiks stałego, publicznego adresu wariantu do aplikacji (PublicBrandingController). */
        const val PUBLIC_LOGO_PATH_PREFIX = "/api/public/branding"
        /** Podpisany link tylko dla logo sprzed wariantów; nowe logo ma stały adres publiczny. */
        private val LEGACY_LOGO_URL_TTL = Duration.ofHours(24)
        private val APP_LOGO_KEY = Regex("""^([0-9a-f-]{36})/logo/([0-9a-f]{16})/app\.png$""")
        private val CONTENT_HASH = Regex("""^[0-9a-f]{16}$""")
    }

    /**
     * Adres wariantu do aplikacji (menu, ustawienia, Karta Wizyty).
     *
     * Nowe logo dostaje stały, publiczny adres z hashem treści:
     * `/api/public/branding/{studioId}/logo/{hash}/app.png`. Adres jest identyczny przy
     * każdym odświeżeniu, więc przeglądarka rysuje obrazek z pamięci podręcznej od
     * pierwszej klatki, bez mrugania; nowy plik ma nowy hash, więc nic nie zostaje
     * nieświeże. Podpisany link S3 (inny przy każdym żądaniu, więc nigdy nie trafiający
     * w cache) zostaje tylko dla logo sprzed wariantów.
     */
    fun appLogoUrl(settings: StudioSettingsEntity?): String? {
        val key = settings?.logoS3Key ?: return null
        val match = APP_LOGO_KEY.matchEntire(key) ?: return presign(key)
        val (studioId, hash) = match.destructured
        return "$PUBLIC_LOGO_PATH_PREFIX/$studioId/logo/$hash/app.png"
    }

    /**
     * Bajty wariantu do aplikacji spod publicznego adresu; `null`, gdy [hash] nie jest
     * aktualnym logo studia (stary adres po podmianie, zgadywanie).
     */
    fun loadAppLogo(studioId: UUID, hash: String): ByteArray? {
        if (!CONTENT_HASH.matches(hash)) return null
        val settings = studioSettingsRepository.findById(studioId).orElse(null) ?: return null
        val expected = "$studioId/logo/$hash/app.png"
        if (settings.logoS3Key != expected) return null
        return download(expected)
    }

    /**
     * Przetwarza wgrany plik, odkłada warianty do S3, podmienia klucze w ustawieniach
     * studia i sprząta poprzednie obiekty. Zwraca zapisane ustawienia.
     */
    fun replaceLogo(studioId: UUID, originalBytes: ByteArray): StudioSettingsEntity {
        val processed = processor.process(originalBytes)
        val settings = studioSettingsRepository.findById(studioId).orElse(null)
            ?: StudioSettingsEntity(studioId = studioId)
        val previousKeys = currentKeys(settings)

        val prefix = "$studioId/logo/${contentHash(originalBytes)}"
        val appKey = "$prefix/app.png"
        val printKey = "$prefix/print.png"
        val vectorKey = processed.vectorSvg?.let { "$prefix/vector.svg" }

        upload(appKey, processed.appPng, "image/png")
        upload(printKey, processed.printPng, "image/png")
        if (vectorKey != null) upload(vectorKey, processed.vectorSvg!!, "image/svg+xml")

        settings.logoS3Key = appKey
        settings.logoPrintS3Key = printKey
        settings.logoVectorS3Key = vectorKey
        settings.logoNeedsLightPlate = processed.needsLightPlate
        settings.logoAspectRatio = processed.aspectRatio
        settings.updatedAt = Instant.now()
        val saved = studioSettingsRepository.save(settings)

        deleteQuietly(previousKeys - currentKeys(saved))
        logger.info(
            "Logo replaced for studio {}: format={} print={}x{} keys={}",
            studioId, processed.sourceFormat, processed.printWidth, processed.printHeight, currentKeys(saved)
        )
        return saved
    }

    fun deleteLogo(studioId: UUID) {
        val settings = studioSettingsRepository.findById(studioId).orElse(null) ?: return
        val keys = currentKeys(settings)
        if (keys.isEmpty()) return

        settings.logoS3Key = null
        settings.logoPrintS3Key = null
        settings.logoVectorS3Key = null
        settings.logoNeedsLightPlate = true
        settings.logoAspectRatio = null
        settings.updatedAt = Instant.now()
        studioSettingsRepository.save(settings)

        deleteQuietly(keys)
        logger.info("Logo deleted for studio {}: {}", studioId, keys)
    }

    /**
     * Logo do nagłówka dokumentu albo `null`, gdy studio nie ma logo lub wyłączyło je
     * na dokumentach. Logo wgrane przed wprowadzeniem wariantów (jest `logoS3Key`, nie ma
     * `logoPrintS3Key`) jest przetwarzane w locie i od tej pory ma już komplet wariantów —
     * bez osobnego backfillu.
     */
    fun loadDocumentLogo(studioId: UUID): DocumentLogo? {
        val settings = studioSettingsRepository.findById(studioId).orElse(null) ?: return null
        if (!settings.logoOnDocuments) return null
        val legacyKey = settings.logoS3Key ?: return null

        val effective = if (settings.logoPrintS3Key == null) {
            logger.info("Studio {} has a pre-variant logo at {} — generating variants on the fly", studioId, legacyKey)
            replaceLogo(studioId, download(legacyKey))
        } else {
            settings
        }

        val printKey = effective.logoPrintS3Key ?: return null
        return DocumentLogo(
            printPng = download(printKey),
            vectorSvg = effective.logoVectorS3Key?.let { download(it) }
        )
    }

    private fun currentKeys(settings: StudioSettingsEntity): Set<String> =
        listOfNotNull(settings.logoS3Key, settings.logoPrintS3Key, settings.logoVectorS3Key).toSet()

    private fun contentHash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun upload(key: String, bytes: ByteArray, contentType: String) {
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(contentType)
                .contentLength(bytes.size.toLong())
                .cacheControl(CACHE_CONTROL)
                .build(),
            RequestBody.fromBytes(bytes)
        )
    }

    private fun download(key: String): ByteArray =
        s3Client.getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build())
            .use { it.readAllBytes() }

    private fun presign(key: String): String {
        val request = GetObjectPresignRequest.builder()
            .signatureDuration(LEGACY_LOGO_URL_TTL)
            .getObjectRequest(GetObjectRequest.builder().bucket(bucketName).key(key).build())
            .build()
        return s3Presigner.presignGetObject(request).url().toString()
    }

    /** Sprzątanie starych obiektów nie może przewrócić zapisu nowego logo. */
    private fun deleteQuietly(keys: Set<String>) {
        keys.forEach { key ->
            try {
                s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build())
            } catch (e: Exception) {
                logger.warn("Could not delete stale logo object {}: {}", key, e.message)
            }
        }
    }
}

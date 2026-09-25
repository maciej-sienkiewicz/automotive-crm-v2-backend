package pl.detailing.crm.comms.signature

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class StoredSignatureImage(val bytes: ByteArray, val contentType: String)

/**
 * Obrazki stopki pod stałymi, publicznymi adresami.
 *
 * Serwis zwraca ŚCIEŻKĘ (`/api/public/mail-signature/...`), a adres absolutny składa
 * frontend z domeny, na której działa aplikacja - tej samej, przez którą rozmawia z API
 * (reverse proxy kieruje `/api` do backendu). Pierwsza wersja składała adres tutaj,
 * z BACKEND_BASE_URL, którego wdrożenie nie ustawia: obrazki szły pod domyślny
 * api.detailboost.pl i nie wyświetlały się ani w podglądzie, ani u odbiorcy. Domena
 * aplikacji jest jedynym adresem, o którym wiemy na pewno, że prowadzi do tego backendu
 * (tak samo budowane są linki do Karty Wizyty i podpisu zdalnego). Wyprowadzanie go tu
 * z żądania (PublicBaseUrl) zależałoby od nagłówków X-Forwarded-* ustawionych przez
 * proxy; adres z paska przeglądarki takiej zależności nie ma.
 *
 * Adres musi być publiczny, bo obrazek pobiera klient poczty odbiorcy (Gmail, Outlook),
 * który nie zna sesji. Musi też być STAŁY:
 * wysłana wiadomość żyje w cudzej skrzynce latami, więc podmiana zdjęcia w stopce nie
 * może zepsuć obrazka w listach sprzed tygodnia. Dlatego:
 *  - klucz niesie hash treści (`{studioId}/mail-signature/{hash}.jpg|png`) — nowy plik to
 *    nowy adres, stary adres nadal odpowiada starym plikiem,
 *  - obiektów nie kasujemy przy podmianie; znikają dopiero z całym studiem (S3StudioPurger
 *    czyści bucket po prefiksie `{studioId}/`),
 *  - logo studia KOPIUJEMY do stopki zamiast linkować `/api/public/branding/...` — tamten
 *    adres po zmianie logo celowo zwraca 404, co w wysłanych mailach dałoby pustą ramkę.
 */
@Service
class MailSignatureImageService(
    private val s3Client: S3Client,
    private val processor: MailSignatureImageProcessor,
    private val studioSettingsRepository: StudioSettingsRepository,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {
    companion object {
        private val logger = LoggerFactory.getLogger(MailSignatureImageService::class.java)
        private const val CACHE_CONTROL = "public, max-age=31536000, immutable"
        const val PUBLIC_PATH = "/api/public/mail-signature"
        private val CONTENT_HASH = Regex("""^[0-9a-f]{16}$""")
        private val EXTENSIONS = mapOf("jpg" to "image/jpeg", "png" to "image/png")
    }

    /** Ścieżka katalogu ikon (telefon, e-mail, social) — renderer frontu dokleja `{zestaw}/{nazwa}.png`. */
    val iconsPath: String get() = "$PUBLIC_PATH/icons/${MailSignatureIcons.VERSION}"

    fun upload(studioId: StudioId, kind: MailSignatureImageKind, bytes: ByteArray): String =
        store(studioId.value, processor.process(bytes, kind))

    /**
     * Kopia aktualnego logo studia jako logo stopki. Wariant drukowy ma najwięcej
     * szczegółów (procesor i tak zmniejsza go do rozmiaru stopki); logo sprzed wariantów
     * ma tylko oryginał.
     */
    fun copyCompanyLogo(studioId: StudioId): String {
        val settings = studioSettingsRepository.findById(studioId.value).orElse(null)
        val key = settings?.logoPrintS3Key ?: settings?.logoS3Key
            ?: throw NotFoundException("Studio nie ma jeszcze logo. Dodaj je w ustawieniach firmy")
        val original = download(key) ?: throw NotFoundException("Nie znaleziono pliku logo studia")
        return store(studioId.value, processor.process(original, MailSignatureImageKind.LOGO))
    }

    /** Bajty spod publicznego adresu; `null` dla adresu, który nie wskazuje naszego obrazka. */
    fun load(studioId: UUID, hash: String, extension: String): StoredSignatureImage? {
        if (!CONTENT_HASH.matches(hash)) return null
        val contentType = EXTENSIONS[extension] ?: return null
        val bytes = download(key(studioId, hash, extension)) ?: return null
        return StoredSignatureImage(bytes, contentType)
    }

    private fun store(studioId: UUID, image: ProcessedSignatureImage): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(image.bytes)
            .joinToString("") { "%02x".format(it) }
            .take(16)
        val key = key(studioId, hash, image.extension)
        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(bucketName)
                .key(key)
                .contentType(image.contentType)
                .contentLength(image.bytes.size.toLong())
                .cacheControl(CACHE_CONTROL)
                .build(),
            RequestBody.fromBytes(image.bytes)
        )
        logger.info("Mail signature image stored for studio {}: {}", studioId, key)
        return "$PUBLIC_PATH/$studioId/$hash.${image.extension}"
    }

    private fun key(studioId: UUID, hash: String, extension: String) = "$studioId/mail-signature/$hash.$extension"

    /**
     * `null`, gdy obiektu nie ma. Adres jest publiczny i bez bazy danych po drodze, więc
     * zgadywane hashe trafiają prosto do S3 — a S3 bez uprawnienia `s3:ListBucket` odpowiada
     * na brak obiektu 403 AccessDenied zamiast 404. Bez tej gałęzi każdy skaner dostawałby
     * 500 i zaśmiecał log. Inne błędy (np. złe klucze) zostają błędami: to awaria, nie brak.
     */
    private fun download(key: String): ByteArray? = try {
        s3Client.getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build()).use { it.readAllBytes() }
    } catch (e: NoSuchKeyException) {
        null
    } catch (e: S3Exception) {
        if (e.statusCode() == 404 || e.awsErrorDetails()?.errorCode() == "AccessDenied") null else throw e
    }
}

/**
 * Ikony stopki (kontakt i social media) jako PNG z zasobów aplikacji.
 *
 * PNG, a nie SVG ani font ikon: Gmail i Outlook nie wyświetlają SVG w poczcie, a fontów
 * webowych nie ładuje większość klientów. Pliki mają 56 px, w stopce 14–22 px — zapas
 * na ekrany retina. Źródła: glify marek z Simple Icons (CC0), ikony kontaktu z Lucide
 * (ISC), LinkedIn narysowany własnoręcznie (Simple Icons go nie publikuje);
 * generator: `scripts/mail-signature-icons/generate.mjs`.
 *
 * [VERSION] jest częścią adresu, bo ikony idą z nagłówkiem `immutable` — poprawiona
 * grafika musi dostać nowy katalog, inaczej skrzynki odbiorców pokażą starą z cache.
 */
@Service
class MailSignatureIcons {
    companion object {
        const val VERSION = "v1"
        val SOCIAL = setOf("linkedin", "facebook", "instagram", "youtube", "tiktok")
        val CONTACT = setOf("phone", "mail", "web", "pin")
        val SETS: Map<String, Set<String>> = mapOf(
            "mono" to SOCIAL,
            "color" to SOCIAL,
            "color-sq" to SOCIAL,
            "contact" to CONTACT
        )
    }

    private val cache = ConcurrentHashMap<String, ByteArray>()

    /** `null` dla nazwy spoza listy — ścieżka z adresu nigdy nie trafia wprost do classpath. */
    fun load(set: String, name: String): ByteArray? {
        if (SETS[set]?.contains(name) != true) return null
        return cache.computeIfAbsent("$set/$name") { key ->
            ClassPathResource("mail-signature/icons/$VERSION/$key.png").inputStream.use { it.readAllBytes() }
        }
    }
}

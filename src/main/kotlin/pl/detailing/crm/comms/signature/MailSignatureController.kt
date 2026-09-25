package pl.detailing.crm.comms.signature

import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.util.UUID
import java.util.concurrent.TimeUnit

data class MailSignatureResponse(
    val bodyHtml: String?,
    val enabledByDefault: Boolean,
    /** Projekt z konfiguratora; `null` dla stopki tekstowej i przy braku stopki. */
    val design: MailSignatureDesign?,
    /**
     * Ścieżka katalogu ikon stopki. Adres absolutny (trafia do HTML-a wysyłanych maili)
     * składa frontend z domeny aplikacji — patrz [MailSignatureImageService].
     */
    val iconsPath: String,
    /** Podpowiedzi do pierwszego uruchomienia kreatora: dane z konta i ze studia. */
    val defaults: MailSignatureDefaults
)

/**
 * Dane do wstępnego wypełnienia kreatora. To wizytówka ZALOGOWANEGO użytkownika i jego
 * studia, a nie dane klienta, dlatego telefon i e-mail nie są maskowane
 * (PiiResponseSurfaceScanTest ma na to wyjątek z uzasadnieniem).
 */
data class MailSignatureDefaults(
    val fullName: String?,
    val email: String?,
    val phone: String?,
    val company: String?,
    val website: String?,
    val address: String?,
    val hasCompanyLogo: Boolean
)

data class SaveMailSignatureRequest(
    val bodyHtml: String,
    val enabledByDefault: Boolean = true,
    val design: MailSignatureDesign? = null
)

/** Ścieżka obrazka (`/api/public/mail-signature/...`) — adres absolutny składa frontend. */
data class MailSignatureImageResponse(val path: String)

/**
 * Stopka nadawcy i jej konfigurator. Stopka należy do zalogowanego użytkownika, nie do
 * studia: dwie osoby odpisujące z tej samej skrzynki podpisują się własnym nazwiskiem
 * i telefonem. Uprawnienie jak w reszcie poczty (CommsController).
 */
@RestController
@RequestMapping("/api/v1/comms/signature")
@RequiresPermission(Permission.LEADS_MANAGE)
class MailSignatureController(
    private val signatureService: UserMailSignatureService,
    private val imageService: MailSignatureImageService,
    private val studioSettingsRepository: StudioSettingsRepository
) {

    @GetMapping
    fun getSignature(): ResponseEntity<MailSignatureResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val signature = signatureService.get(principal.studioId, principal.userId)
        val settings = studioSettingsRepository.findById(principal.studioId.value).orElse(null)
        val cityLine = listOfNotNull(settings?.postalCode, settings?.city)
            .map(String::trim).filter(String::isNotEmpty).joinToString(" ")
        val address = listOfNotNull(settings?.street?.trim(), cityLine)
            .filter(String::isNotEmpty).joinToString(", ")
        return ResponseEntity.ok(
            MailSignatureResponse(
                bodyHtml = signature.bodyHtml,
                enabledByDefault = signature.enabledByDefault,
                design = signature.design,
                iconsPath = imageService.iconsPath,
                defaults = MailSignatureDefaults(
                    fullName = principal.fullName.trim().ifEmpty { null },
                    email = principal.email.trim().ifEmpty { null },
                    phone = principal.phoneNumber.trim().ifEmpty { null },
                    company = settings?.name?.trim()?.ifEmpty { null },
                    website = settings?.website?.trim()?.ifEmpty { null },
                    address = address.ifEmpty { null },
                    hasCompanyLogo = settings?.logoS3Key != null
                )
            )
        )
    }

    @PutMapping
    fun saveSignature(@RequestBody request: SaveMailSignatureRequest): ResponseEntity<MailSignatureResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        signatureService.save(
            principal.studioId,
            principal.userId,
            request.bodyHtml,
            request.enabledByDefault,
            request.design
        )
        return getSignature()
    }

    @DeleteMapping
    fun deleteSignature(): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        signatureService.delete(principal.studioId, principal.userId)
        return ResponseEntity.noContent().build()
    }

    /** Zdjęcie albo logo do stopki; zwraca ścieżkę stałego, publicznego adresu. */
    @PostMapping("/images", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadImage(
        @RequestPart("file") file: MultipartFile,
        @RequestParam("kind") kind: String
    ): ResponseEntity<MailSignatureImageResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val imageKind = MailSignatureImageKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) }
            ?: throw ValidationException("Nieznany rodzaj obrazka stopki")
        return ResponseEntity.ok(MailSignatureImageResponse(imageService.upload(principal.studioId, imageKind, file.bytes)))
    }

    /** Logo studia z ustawień firmy jako logo stopki — kopia, patrz [MailSignatureImageService]. */
    @PostMapping("/images/company-logo")
    fun useCompanyLogo(): ResponseEntity<MailSignatureImageResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(MailSignatureImageResponse(imageService.copyCompanyLogo(principal.studioId)))
    }
}

/**
 * Publiczne obrazki stopek: zdjęcia i logo użytkowników oraz ikony.
 *
 * Zarejestrowane jako permitAll w SecurityConfig — pobiera je klient poczty odbiorcy,
 * bez sesji. Nic tu nie jest tajne: obrazek i tak widzi każdy adresat maila, a adres
 * niesie hash treści, więc nie da się wyliczać cudzych plików. Treść pod adresem nigdy
 * się nie zmienia, stąd cache na rok z `immutable`.
 */
@RestController
@RequestMapping(MailSignatureImageService.PUBLIC_PATH)
class PublicMailSignatureController(
    private val imageService: MailSignatureImageService,
    private val icons: MailSignatureIcons
) {
    private val cacheControl = CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable()

    @GetMapping("/{studioId}/{hash}.{extension}")
    fun image(
        @PathVariable studioId: UUID,
        @PathVariable hash: String,
        @PathVariable extension: String
    ): ResponseEntity<ByteArray> {
        val image = imageService.load(studioId, hash, extension) ?: throw NotFoundException("Obrazek nie istnieje")
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.contentType))
            .eTag("\"$hash\"")
            .cacheControl(cacheControl)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .header("X-Content-Type-Options", "nosniff")
            .body(image.bytes)
    }

    @GetMapping("/icons/{version}/{set}/{name}.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun icon(
        @PathVariable version: String,
        @PathVariable set: String,
        @PathVariable name: String
    ): ResponseEntity<ByteArray> {
        if (version != MailSignatureIcons.VERSION) throw NotFoundException("Ikona nie istnieje")
        val bytes = icons.load(set, name) ?: throw NotFoundException("Ikona nie istnieje")
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .cacheControl(cacheControl)
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes)
    }
}

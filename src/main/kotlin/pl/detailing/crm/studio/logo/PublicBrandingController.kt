package pl.detailing.crm.studio.logo

import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.shared.NotFoundException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Publiczny, stały adres logo studia do aplikacji.
 *
 * Zarejestrowany jako permitAll w SecurityConfig. Logo nie jest tajne (widnieje na
 * publicznej Karcie Wizyty i na dokumentach klienta), a adres niesie hash treści:
 * odpowiada tylko dla aktualnego logo studia, stary hash po podmianie daje 404.
 * Nagłówki cache są maksymalne (rok, immutable), bo treść pod danym adresem nigdy
 * się nie zmienia: nowy plik to nowy adres. Dzięki temu menu boczne rysuje logo
 * z pamięci podręcznej przeglądarki od pierwszej klatki po odświeżeniu.
 */
@RestController
@RequestMapping("/api/public/branding")
class PublicBrandingController(
    private val companyLogoService: CompanyLogoService
) {

    @GetMapping("/{studioId}/logo/{hash}/app.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun appLogo(
        @PathVariable studioId: UUID,
        @PathVariable hash: String,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?
    ): ResponseEntity<ByteArray> {
        val etag = "\"$hash\""
        val cacheControl = CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable()
        if (ifNoneMatch == etag) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).cacheControl(cacheControl).build()
        }
        val bytes = companyLogoService.loadAppLogo(studioId, hash)
            ?: throw NotFoundException("Logo nie istnieje")
        return ResponseEntity.ok()
            .contentType(MediaType.IMAGE_PNG)
            .eTag(etag)
            .cacheControl(cacheControl)
            .body(bytes)
    }
}

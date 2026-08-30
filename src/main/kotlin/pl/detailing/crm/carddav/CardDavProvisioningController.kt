package pl.detailing.crm.carddav

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import java.time.Instant
import java.util.UUID

data class CreateProvisioningRequest(val deviceName: String?)

data class ProvisioningResponse(
    val provisioningId: UUID,
    val installUrl: String,
    val expiresAt: Instant,
)

data class CardDavAccountResponse(
    val accountId: UUID,
    val deviceName: String,
    val createdAt: Instant,
    val lastSyncAt: Instant?,
)

/**
 * Zarządzanie automatyczną konfiguracją kontaktów na iPhonie — od strony
 * zalogowanego CRM (sesja), więc CELOWO poza poddrzewem serwera CardDAV:
 * tamten podgraf ma własny, bezstanowy łańcuch Basic auth dla klientów
 * CardDAV i sesji nie widzi.
 */
@RestController
@RequestMapping("/api/v1/carddav-setup")
class CardDavProvisioningController(
    private val provisioningService: CardDavProvisioningService,
) {

    @PostMapping("/provisionings")
    fun createProvisioning(@RequestBody request: CreateProvisioningRequest): ResponseEntity<ProvisioningResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val link = provisioningService.createProvisioning(principal, request.deviceName ?: "iPhone")
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ProvisioningResponse(link.provisioningId, link.installUrl, link.expiresAt)
        )
    }

    @GetMapping("/accounts")
    fun listAccounts(): List<CardDavAccountResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return provisioningService.listAccounts(principal).map {
            CardDavAccountResponse(
                accountId = it.id,
                deviceName = it.deviceName,
                createdAt = it.createdAt,
                lastSyncAt = it.lastUsedAt,
            )
        }
    }

    @DeleteMapping("/accounts/{accountId}")
    fun revokeAccount(@PathVariable accountId: UUID): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        provisioningService.revokeAccount(principal, accountId)
        return ResponseEntity.noContent().build()
    }
}

/**
 * Pobranie profilu spod jednorazowego linku — bez sesji, jak pozostałe
 * publiczne ścieżki tokenowe: profil bywa pobierany inną przeglądarką niż CRM
 * (skan QR z komputera otwiera Safari na telefonie). Uwierzytelnia
 * nieodgadywalny, krótkotrwały token w adresie; link spala się przy pobraniu.
 */
@RestController
@RequestMapping("/api/public/carddav-profile")
class CardDavProfileDownloadController(
    private val provisioningService: CardDavProvisioningService,
) {

    @GetMapping("/{token}")
    fun downloadProfile(@PathVariable token: String): ResponseEntity<ByteArray> {
        val profile = provisioningService.redeemProfile(token)
        return ResponseEntity.ok()
            // Ten Content-Type każe iOS-owi przechwycić plik jako profil
            // konfiguracyjny („Pobrano profil") zamiast pokazać go jako tekst.
            .header(HttpHeaders.CONTENT_TYPE, "application/x-apple-aspen-config")
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${profile.fileName}\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(profile.xml.toByteArray(Charsets.UTF_8))
    }
}

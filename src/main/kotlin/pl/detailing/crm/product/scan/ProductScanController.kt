package pl.detailing.crm.product.scan

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.product.MAX_BARCODE_IMAGE_BYTES
import pl.detailing.crm.product.adapter.ai.BarcodeImageExtractionService
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.rolepreview.SimulatedEffectChannel
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability

/**
 * Sesja skanowania od strony KOMPUTERA (zalogowana). Otwiera sesję (→ kod QR), odpytuje
 * jej stan (zapas wobec WebSocketa) i zamyka.
 */
@RestController
@RequestMapping("/api/v1/products/scan-sessions")
@RequiresCapability(CapabilityKey.PRODUCTS_ACCESS)
class ProductScanController(
    private val scanSessionService: ProductScanSessionService
) {
    @PostMapping
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun open(): ResponseEntity<ScanSessionResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val session = scanSessionService.open(principal.studioId, principal.userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(session.toDesktopResponse())
    }

    @GetMapping("/{sessionId}")
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun get(@PathVariable sessionId: String): ResponseEntity<ScanSessionResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(scanSessionService.getForStudio(principal.studioId, sessionId).toDesktopResponse())
    }

    @DeleteMapping("/{sessionId}")
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun close(@PathVariable sessionId: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        scanSessionService.close(principal.studioId, sessionId)
        return ResponseEntity.noContent().build()
    }
}

/**
 * Sesja skanowania od strony TELEFONU. Trasa PUBLICZNA (bez logowania), autoryzacja
 * wyłącznie po jednorazowym `handoffToken` z kodu QR. Endpointy przyjmują wyłącznie kody
 * kreskowe (albo zdjęcie, z którego czytamy same cyfry kodu) — nie ma tu żadnej ścieżki
 * do danych studia poza jego nazwą na ekranie.
 */
@RestController
@RequestMapping("/api/mobile/products/scan")
class MobileProductScanController(
    private val scanSessionService: ProductScanSessionService,
    private val barcodeImageExtractionService: BarcodeImageExtractionService,
    private val rolePreviewGuard: RolePreviewOutboundGuard
) {
    @GetMapping("/{handoffToken}")
    fun context(@PathVariable handoffToken: String): ResponseEntity<MobileScanContext> {
        val session = scanSessionService.resolveByToken(handoffToken)
        return ResponseEntity.ok(
            MobileScanContext(
                status = session.status,
                scannedCount = session.scannedCodes.size,
                expiresAt = session.expiresAt
            )
        )
    }

    @PostMapping("/{handoffToken}")
    fun submit(
        @PathVariable handoffToken: String,
        @RequestBody req: SubmitCodesRequest
    ): ResponseEntity<MobileScanContext> {
        val session = scanSessionService.submitCodes(handoffToken, req.codes)
        return ResponseEntity.ok(
            MobileScanContext(
                status = session.status,
                scannedCount = session.scannedCodes.size,
                expiresAt = session.expiresAt
            )
        )
    }

    /**
     * Zapas: telefon nie odczytał kodu dekoderem w przeglądarce, więc wysyła ZDJĘCIE, a
     * cyfry czyta model wizyjny (jak przy VIN). Kod przechodzi tę samą walidację i ten
     * sam `submitCodes`, co skan na żywo. Sesja musi być OTWARTA, zanim zapłacimy za
     * wywołanie modelu — token z wygasłej sesji nie może generować kosztów.
     */
    @PostMapping("/{handoffToken}/photo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submitPhoto(
        @PathVariable handoffToken: String,
        @RequestParam("image") image: MultipartFile
    ): ResponseEntity<MobilePhotoScanResponse> = runBlocking {
        val session = scanSessionService.resolveByToken(handoffToken)
        if (session.status != "OPEN") throw ValidationException("Sesja skanowania jest już zamknięta.")
        if (image.isEmpty || image.size > MAX_BARCODE_IMAGE_BYTES) {
            throw ValidationException("Zdjęcie jest puste albo za duże.")
        }
        val contentType = image.contentType?.takeIf { it.startsWith("image/") }
            ?: throw ValidationException("Oczekiwano pliku obrazu.")
        rolePreviewGuard.requireOutsideSandbox(
            runCatching { java.util.UUID.fromString(session.studioId) }.getOrNull(), SimulatedEffectChannel.AI,
            "zdjęcie trafiłoby do modelu AI, który odczytałby z niego kod kreskowy"
        )

        val gtin = barcodeImageExtractionService.extractGtin(image.bytes, contentType)
        val updated = if (gtin != null) scanSessionService.submitCodes(handoffToken, listOf(gtin.value)) else session
        ResponseEntity.ok(
            MobilePhotoScanResponse(
                gtin = gtin?.value,
                status = updated.status,
                scannedCount = updated.scannedCodes.size,
                expiresAt = updated.expiresAt
            )
        )
    }
}

data class MobilePhotoScanResponse(
    val gtin: String?,
    val status: String,
    val scannedCount: Int,
    val expiresAt: String
)

data class ScanSessionResponse(
    val sessionId: String,
    val handoffToken: String,
    val handoffPath: String,
    val status: String,
    val scannedCodes: List<String>,
    val expiresAt: String
)

/**
 * Jedyne miejsce, w którym sesja zamienia się w kształt dla klienta. NIE jest prywatne
 * celowo: ten sam DTO leci REST-em i WebSocketem. Wysyłanie po WS surowej `ScanSession`
 * dawało `scannedCodes` jako obiekty `{code, scannedAt}`, podczas gdy REST oddawał same
 * ciągi — front robił z tego `"[object Object]"` i walidacja kodu odrzucała skan.
 */
internal fun ScanSession.toDesktopResponse() = ScanSessionResponse(
    sessionId = sessionId,
    handoffToken = handoffToken,
    handoffPath = "/m/scan?s=$handoffToken",
    status = status,
    scannedCodes = scannedCodes.map { it.code },
    expiresAt = expiresAt
)

data class MobileScanContext(
    val status: String,
    val scannedCount: Int,
    val expiresAt: String
)

data class SubmitCodesRequest(val codes: List<String>)

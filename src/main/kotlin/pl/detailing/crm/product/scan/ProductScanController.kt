package pl.detailing.crm.product.scan

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
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
 * kreskowe — nie ma tu żadnej ścieżki do danych studia poza jego nazwą na ekranie.
 */
@RestController
@RequestMapping("/api/mobile/products/scan")
class MobileProductScanController(
    private val scanSessionService: ProductScanSessionService
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
}

data class ScanSessionResponse(
    val sessionId: String,
    val handoffToken: String,
    val handoffPath: String,
    val status: String,
    val scannedCodes: List<String>,
    val expiresAt: String
)

private fun ScanSession.toDesktopResponse() = ScanSessionResponse(
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

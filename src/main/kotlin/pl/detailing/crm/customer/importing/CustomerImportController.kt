package pl.detailing.crm.customer.importing

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

/**
 * Import kontaktów — strona panelu.
 *
 * Dwa wejścia, bo systemy mobilne dają różne możliwości: Android potrafi oddać kontakty
 * bezpośrednio z telefonu (sesja z kodem QR), iPhone nie ma takiej drogi w przeglądarce
 * i idzie przez plik. Od momentu, w którym kontakty są w sesji, obie ścieżki są tym
 * samym: podgląd z wykrytymi duplikatami i zatwierdzenie wybranych.
 *
 * Wymagane uprawnienie to [Permission.CUSTOMERS_VIEW] (dostęp do danych osobowych) —
 * zakładanie kartotek idzie tą samą ścieżką co ręczne dodanie klienta.
 */
@RestController
@RequestMapping("/api/v1/customers/import")
@RequiresPermission(Permission.CUSTOMERS_VIEW)
class CustomerImportController(
    private val importService: CustomerImportService,
    private val properties: CustomerImportProperties
) {

    /**
     * Zakłada sesję dla telefonu i zwraca sekret do zaszycia w kodzie QR.
     * POST /api/v1/customers/import/sessions
     */
    @PostMapping("/sessions")
    fun openHandoffSession(): ResponseEntity<ImportSessionHandoffResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val session = importService.openHandoffSession(principal.studioId, principal.userId)

        return ResponseEntity.ok(ImportSessionHandoffResponse(
            sessionId = session.id.toString(),
            handoffToken = session.handoffToken!!,
            expiresAt = session.expiresAt.toString()
        ))
    }

    /**
     * Wgranie pliku `.vcf`. Sesja powstaje od razu gotowa do podglądu.
     * POST /api/v1/customers/import/sessions/vcard
     */
    @PostMapping("/sessions/vcard", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadVCard(
        @RequestPart("file") file: MultipartFile
    ): ResponseEntity<ImportPreviewResponse> {
        val principal = SecurityContextHelper.getCurrentUser()

        if (file.isEmpty) throw ValidationException("Plik jest pusty.")
        if (file.size > properties.maxFileSizeBytes) {
            throw ValidationException(
                "Plik jest za duży (limit: ${properties.maxFileSizeBytes / (1024 * 1024)} MB). " +
                    "Najczęściej to zdjęcia w wizytówkach — wyeksportuj kontakty bez zdjęć."
            )
        }

        // vCard jest ustalony jako UTF-8 od wersji 3.0; starsze pliki z Androida bywają
        // w innym kodowaniu, ale nieczytelny znak w imieniu jest problemem mniejszym niż
        // odrzucenie całego importu, więc dekodujemy pobłażliwie.
        val content = String(file.bytes, Charsets.UTF_8)
        val session = importService.openFileSession(
            studioId = principal.studioId,
            userId = principal.userId,
            fileName = file.originalFilename,
            content = content
        )

        return ResponseEntity.ok(
            importService.preview(session.id, principal.studioId).toResponse()
        )
    }

    /**
     * Stan sesji wraz z podglądem. Panel odpytuje to, czekając aż telefon prześle
     * kontakty — i tym samym zapytaniem dostaje gotową listę, gdy już są.
     * GET /api/v1/customers/import/sessions/{sessionId}
     */
    @GetMapping("/sessions/{sessionId}")
    fun getSession(@PathVariable sessionId: String): ResponseEntity<ImportPreviewResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val preview = importService.preview(UUID.fromString(sessionId), principal.studioId)
        return ResponseEntity.ok(preview.toResponse())
    }

    /**
     * Zapisuje zaznaczone kontakty jako klientów.
     * POST /api/v1/customers/import/sessions/{sessionId}/commit
     */
    @PostMapping("/sessions/{sessionId}/commit")
    fun commit(
        @PathVariable sessionId: String,
        @RequestBody request: CommitImportRequest
    ): ResponseEntity<CommitImportResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = importService.commit(
            sessionId = UUID.fromString(sessionId),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            selectedIndexes = request.selectedIndexes.toSet()
        )
        return ResponseEntity.ok(CommitImportResponse(
            imported = result.imported,
            skipped = result.skipped
        ))
    }
}

// ── DTO ──────────────────────────────────────────────────────────────────────

data class ImportSessionHandoffResponse(
    val sessionId: String,
    /** Do zaszycia w kodzie QR; działa raz i wygasa. */
    val handoffToken: String,
    val expiresAt: String
)

data class CommitImportRequest(
    /** Pozycje wierszy z podglądu, które użytkownik zostawił zaznaczone. */
    val selectedIndexes: List<Int> = emptyList()
)

data class CommitImportResponse(
    val imported: Int,
    /**
     * Zaznaczone, ale niezapisane — bo w międzyczasie przestały być nowe albo nigdy
     * nie nadawały się do importu. Pokazujemy to wprost: „zapisano 30 z 34" jest
     * uczciwsze niż milczenie o czterech.
     */
    val skipped: Int
)

data class ImportPreviewRowResponse(
    val index: Int,
    val firstName: String?,
    val lastName: String?,
    val displayName: String?,
    val phone: String?,
    val email: String?,
    val companyName: String?,
    val status: String,
    val matchedCustomerId: String?,
    val matchedCustomerName: String?,
    val matchedBy: String?,
    val selectedByDefault: Boolean
)

data class ImportPreviewResponse(
    val sessionId: String,
    val status: String,
    val source: String,
    val deviceLabel: String?,
    val rows: List<ImportPreviewRowResponse>,
    val newCount: Int,
    val existingCount: Int,
    val duplicateCount: Int,
    val notImportableCount: Int
)

internal fun ImportPreview.toResponse() = ImportPreviewResponse(
    sessionId = sessionId.toString(),
    status = status.name,
    source = source.name,
    deviceLabel = deviceLabel,
    rows = rows.map {
        ImportPreviewRowResponse(
            index = it.index,
            firstName = it.firstName,
            lastName = it.lastName,
            displayName = it.displayName,
            phone = it.phone,
            email = it.email,
            companyName = it.companyName,
            status = it.status.name,
            matchedCustomerId = it.matchedCustomerId?.toString(),
            matchedCustomerName = it.matchedCustomerName,
            matchedBy = it.matchedBy,
            selectedByDefault = it.selectedByDefault
        )
    },
    newCount = newCount,
    existingCount = existingCount,
    duplicateCount = duplicateCount,
    notImportableCount = notImportableCount
)

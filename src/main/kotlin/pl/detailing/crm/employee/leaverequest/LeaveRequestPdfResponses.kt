package pl.detailing.crm.employee.leaverequest

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.leaverequest.pdf.LeaveRequestDocumentService
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessions
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.NotFoundException

/**
 * Odpowiedzi z plikiem wniosku — wspólne dla samoobsługi i rozpatrujących.
 *
 * Dokument do podpisu idzie z nagłówkiem `X-Document-Sha256` i dopiero po sprawdzeniu, że
 * bajty z magazynu mają skrót zapisany przy ich utworzeniu: pokazanie do podpisu pliku,
 * który zmienił się po drodze, byłoby dokładnie tym, przed czym WYSIWYS ma chronić.
 */
@Component
class LeaveRequestPdfResponses(private val documents: LeaveRequestDocumentService) {

    /** Konkretna wersja do podpisu: H1 dla pracownika, H2 dla rozpatrującego. */
    suspend fun forSigning(request: LeaveRequestEntity, s3Key: String?, sha256: String?): ResponseEntity<ByteArray> {
        if (s3Key == null || sha256 == null) throw NotFoundException("Ta wersja wniosku jeszcze nie istnieje")
        val bytes = withContext(Dispatchers.IO) { documents.download(s3Key) }
        val actual = documents.sha256(bytes)
        if (!actual.equals(sha256, ignoreCase = true)) throw ConflictException(LeaveSigningSessions.DOCUMENT_CHANGED)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("X-Document-Sha256", actual)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"${fileName(request)}\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(bytes)
    }

    /** Aktualny PDF do pobrania: final, a przed decyzją wersja podpisana przez pracownika. */
    suspend fun current(request: LeaveRequestEntity): ResponseEntity<ByteArray> {
        val key = request.currentFileKey() ?: throw NotFoundException("Wniosek nie został jeszcze podpisany")
        val bytes = withContext(Dispatchers.IO) { documents.download(key) }
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${fileName(request)}\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(bytes)
    }

    private fun fileName(request: LeaveRequestEntity): String =
        "wniosek-urlopowy-${request.number.replace('/', '-')}.pdf"
}

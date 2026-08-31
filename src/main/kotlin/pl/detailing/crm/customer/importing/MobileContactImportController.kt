package pl.detailing.crm.customer.importing

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant

/**
 * Strona telefonu: odbiór kontaktów wybranych w systemowym oknie Androida.
 *
 * ## Uwierzytelnienie
 *
 * Leży pod `/api/mobile/**`, czyli poza logowaniem (patrz SecurityConfig) — bo telefon,
 * który zeskanował kod QR, nie jest zalogowany i nie ma być. Całym uprawnieniem jest
 * `handoffToken` z kodu: sekret jednej sesji, ważny kilkanaście minut i **zużywany przy
 * pierwszym przesłaniu**. Nie jest to stały `users.mobile_token`, bo zdjęcie ekranu
 * z kodem nie może dawać komuś bezterminowego prawa wysyłania danych do studia.
 *
 * Token nie pozwala niczego odczytać z CRM-a — poza nazwą studia, żeby człowiek widział
 * na ekranie telefonu, komu właśnie udostępnia książkę adresową. To celowo jedyna
 * informacja, jaką ta ścieżka wypuszcza na zewnątrz.
 */
@RestController
@RequestMapping("/api/mobile/contacts")
class MobileContactImportController(
    private val importService: CustomerImportService,
    private val sessionRepository: CustomerImportSessionRepository,
    private val studioRepository: StudioRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Kontekst dla ekranu na telefonie: czy kod jest jeszcze ważny i do jakiego studia
     * prowadzi. GET /api/mobile/contacts/{handoffToken}
     */
    @GetMapping("/{handoffToken}")
    fun getContext(@PathVariable handoffToken: String): ResponseEntity<Any> {
        val session = sessionRepository.findByHandoffToken(handoffToken)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(MobileImportError("Kod jest nieprawidłowy lub został już użyty."))

        if (session.isExpired()) {
            return ResponseEntity.status(HttpStatus.GONE)
                .body(MobileImportError("Kod wygasł. Wygeneruj nowy na komputerze."))
        }

        val studioName = studioRepository.findByStudioId(session.studioId)?.name ?: ""
        return ResponseEntity.ok(MobileImportContextResponse(
            studioName = studioName,
            expiresAt = session.expiresAt.toString()
        ))
    }

    /**
     * Przesłanie wybranych kontaktów.
     * POST /api/mobile/contacts/{handoffToken}
     */
    @PostMapping("/{handoffToken}")
    fun submit(
        @PathVariable handoffToken: String,
        @RequestBody request: SubmitContactsRequest
    ): ResponseEntity<Any> {
        return try {
            val contacts = request.contacts.map(MobileContact::toStored)
            val session = importService.submitFromDevice(
                handoffToken = handoffToken,
                contacts = contacts,
                deviceLabel = request.deviceLabel
            )
            logger.info(
                "Kontakty z telefonu przyjęte: sesja {}, {} pozycji",
                session.id, contacts.size
            )
            ResponseEntity.ok(SubmitContactsResponse(
                received = contacts.size,
                receivedAt = Instant.now().toString()
            ))
        } catch (e: EntityNotFoundException) {
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(MobileImportError(e.message ?: "Sesja nie została znaleziona."))
        } catch (e: ValidationException) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(MobileImportError(e.message ?: "Nie udało się przyjąć kontaktów."))
        }
    }
}

// ── DTO ──────────────────────────────────────────────────────────────────────

data class MobileImportContextResponse(
    val studioName: String,
    val expiresAt: String
)

data class MobileImportError(val message: String)

/**
 * Kontakt w postaci, w jakiej oddaje go przeglądarkowe API wyboru kontaktów: nazwy,
 * telefony i e-maile jako listy, bez rozbicia na imię i nazwisko.
 */
data class MobileContact(
    val name: List<String> = emptyList(),
    val tel: List<String> = emptyList(),
    val email: List<String> = emptyList()
) {
    /**
     * Rozbicie nazwy na imię i nazwisko po pierwszej spacji.
     *
     * Android oddaje wyłącznie nazwę wyświetlaną — nie ma w niej rozróżnienia na imię
     * i nazwisko, więc każde rozstrzygnięcie jest zgadywanką. Ta jest zgadywanką
     * najczęściej trafną („Jan Kowalski") i zawsze odwracalną: cały oryginał zostaje
     * w `displayName`, a człowiek i tak zobaczy wiersz przed zapisem.
     */
    fun toStored(): StoredContact {
        val display = name.firstOrNull { it.isNotBlank() }?.trim()
        val words = display?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()

        return StoredContact(
            firstName = words.takeIf { it.size >= 2 }?.first(),
            lastName = when {
                words.size >= 2 -> words.drop(1).joinToString(" ")
                words.size == 1 -> words.first()
                else -> null
            },
            displayName = display,
            phones = tel.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            emails = email.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
            companyName = null
        )
    }
}

data class SubmitContactsRequest(
    val contacts: List<MobileContact> = emptyList(),
    /** „Pixel 8" — pokazywane na komputerze, żeby było wiadomo, z czego przyszła lista. */
    val deviceLabel: String? = null
)

data class SubmitContactsResponse(
    val received: Int,
    val receivedAt: String
)

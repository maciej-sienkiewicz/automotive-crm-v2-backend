package pl.detailing.crm.leads.intake

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.BusinessException
import java.util.UUID

data class CreateIntakeWebhookRequest(
    val name: String,
    val defaultTagCodes: List<String> = emptyList()
)

data class UpdateIntakeWebhookRequest(
    val name: String? = null,
    val active: Boolean? = null,
    /** JSON: {"phone": ["numer-do-kontaktu"]}. Pusty string kasuje nadpisania. */
    val fieldMapping: String? = null,
    val defaultTagCodes: List<String>? = null
)

/** Odpowiedź dla wtyczki formularza. Krótka i zawsze taka sama. */
data class FormSubmissionResponse(val ok: Boolean, val leadId: String? = null, val message: String? = null)

/**
 * PUBLICZNE wejście formularzy ze stron studiów.
 *
 * Adres wygląda tak:
 *
 *     POST https://…/api/public/lead-forms/{token}
 *
 * i to jest cała integracja po stronie studia: wkleić w pole „URL webhooka" we
 * wtyczce (Elementor, WPForms, Contact Form 7, Tally, Make/Zapier — obojętne)
 * i skończyć. Nie definiujemy własnego formatu, bo każdy z tych narzędzi ma swój
 * i żaden nie da się przekonać; zamiast tego czytamy to, co przyszło.
 *
 * Uwaga na kody odpowiedzi: poza nieznanym tokenem odpowiadamy 200 nawet wtedy,
 * gdy zgłoszenia nie dało się zamienić na leada. Wtyczki formularzy traktują błąd
 * HTTP jako awarię — pokazują go klientowi na stronie albo wyłączają integrację po
 * kilku próbach. Klient, który właśnie wysłał zapytanie, nie ma prawa oglądać
 * naszego problemu z mapowaniem pól; my go zobaczymy w dzienniku doręczeń.
 */
@RestController
@RequestMapping("/api/public/lead-forms")
class PublicLeadFormController(
    private val intakeService: LeadIntakeService,
    private val payloadReader: FormPayloadReader,
    private val fieldMapper: LeadFieldMapper,
    private val submissionHandler: HandleFormSubmissionHandler
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/{token}")
    fun submit(
        @PathVariable token: String,
        request: HttpServletRequest
    ): ResponseEntity<FormSubmissionResponse> {
        val webhook = intakeService.resolveByToken(token)
        // Ciało czytamy sami, a nie przez @RequestBody: nagłówek Content-Type bywa
        // czymkolwiek (Elementor wysyła form-encoded, Tally JSON, część wtyczek
        // text/plain), a konwerter dobrany po typie potrafi wtedy odrzucić żądanie,
        // zanim je zobaczymy. Surowy tekst zawsze da się przeczytać.
        val payload = readBody(request)
        val contentType = request.contentType
        val remoteIp = request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()
            ?: request.remoteAddr

        if (payload.isBlank()) {
            intakeService.recordDelivery(
                webhook, LeadIntakeService.STATUS_REJECTED, payload,
                reason = "Puste zgłoszenie", remoteIp = remoteIp
            )
            return ResponseEntity.ok(FormSubmissionResponse(ok = false, message = "Puste zgłoszenie"))
        }

        if (intakeService.isDuplicate(webhook, payload)) {
            intakeService.recordDelivery(
                webhook, LeadIntakeService.STATUS_DUPLICATE, payload,
                reason = "Identyczne zgłoszenie przyszło chwilę wcześniej", remoteIp = remoteIp
            )
            return ResponseEntity.ok(FormSubmissionResponse(ok = true, message = "Zgłoszenie już przyjęte"))
        }

        val fields = payloadReader.read(payload, contentType)
        val form = fieldMapper.map(fields, webhook.fieldMapping)

        val result = runCatching {
            submissionHandler.handle(webhook, form, intakeService.defaultTagsOf(webhook))
        }.getOrElse { error ->
            log.error("[LEAD_INTAKE] Formularz {} — zgłoszenie odrzucone", webhook.name, error)
            FormSubmissionResult.Rejected(
                (error as? BusinessException)?.message ?: "Błąd po naszej stronie"
            )
        }

        return when (result) {
            is FormSubmissionResult.Created -> {
                intakeService.recordDelivery(
                    webhook, LeadIntakeService.STATUS_CREATED, payload,
                    leadId = result.leadId, remoteIp = remoteIp
                )
                ResponseEntity.ok(FormSubmissionResponse(ok = true, leadId = result.leadId.toString()))
            }
            is FormSubmissionResult.Rejected -> {
                intakeService.recordDelivery(
                    webhook, LeadIntakeService.STATUS_REJECTED, payload,
                    reason = result.reason, remoteIp = remoteIp
                )
                ResponseEntity.ok(FormSubmissionResponse(ok = false, message = result.reason))
            }
        }
    }

    /**
     * Podgląd mapowania bez tworzenia leada — „wyślij testowo i zobacz, co z tego
     * zrozumieliśmy". Bez tego pierwsze podłączenie formularza jest zgadywanką.
     */
    @PostMapping("/{token}/test")
    fun test(
        @PathVariable token: String,
        request: HttpServletRequest
    ): ResponseEntity<Map<String, Any?>> {
        val webhook = intakeService.resolveByToken(token)
        val fields = payloadReader.read(readBody(request), request.contentType)
        val form = fieldMapper.map(fields, webhook.fieldMapping)
        return ResponseEntity.ok(
            mapOf(
                "recognised" to form.values.mapKeys { it.key.name },
                "leftovers" to form.leftovers.map { mapOf("label" to it.label, "value" to it.value) }
            )
        )
    }

    private fun readBody(request: HttpServletRequest): String {
        val raw = runCatching {
            request.inputStream.readNBytes(MAX_BODY).toString(Charsets.UTF_8).trim()
        }.getOrDefault("")
        if (raw.isNotBlank()) return raw

        // Strumień bywa już pusty, bo kontener rozparsował ciało form-encoded do
        // parametrów (wystarczy, że coś po drodze zajrzy w getParameter). Odtwarzamy
        // je wtedy z powrotem — inaczej zgłoszenie z Elementora znikałoby bez śladu.
        return request.parameterMap.entries
            .flatMap { (key, values) -> values.map { key to it } }
            .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
            .joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value)}"
            }
    }

    private fun encode(raw: String): String =
        java.net.URLEncoder.encode(raw, Charsets.UTF_8)

    private companion object {
        const val MAX_BODY = 20_000
    }
}

/** Zarządzanie webhookami — już za sesją, w ustawieniach studia. */
@RestController
@RequestMapping("/api/v1/leads/intake-webhooks")
@RequiresPermission(Permission.LEADS_MANAGE)
class LeadIntakeWebhookController(private val intakeService: LeadIntakeService) {

    @GetMapping
    fun list(): ResponseEntity<List<LeadIntakeWebhookDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(intakeService.list(principal.studioId))
    }

    @PostMapping
    fun create(@RequestBody request: CreateIntakeWebhookRequest): ResponseEntity<CreatedWebhookDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(intakeService.create(principal.studioId, request.name, request.defaultTagCodes))
    }

    @PutMapping("/{id}")
    fun update(
        @PathVariable id: String,
        @RequestBody request: UpdateIntakeWebhookRequest
    ): ResponseEntity<LeadIntakeWebhookDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            intakeService.update(
                studioId = principal.studioId,
                webhookId = UUID.fromString(id),
                name = request.name,
                active = request.active,
                fieldMapping = request.fieldMapping,
                defaultTagCodes = request.defaultTagCodes
            )
        )
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        intakeService.delete(principal.studioId, UUID.fromString(id))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{id}/deliveries")
    fun deliveries(@PathVariable id: String): ResponseEntity<List<LeadIntakeDeliveryDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(intakeService.deliveries(principal.studioId, UUID.fromString(id)))
    }
}

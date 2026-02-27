package pl.detailing.crm.audit

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.entity.GetEntityAuditLogsCommand
import pl.detailing.crm.audit.entity.GetEntityAuditLogsHandler
import pl.detailing.crm.audit.list.AuditLogListItem
import pl.detailing.crm.audit.list.AuditLogListResult
import pl.detailing.crm.audit.list.ListAuditLogsCommand
import pl.detailing.crm.audit.list.ListAuditLogsHandler
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.UserId
import java.time.Instant

@RestController
@RequestMapping("/api/v1/audit")
class AuditController(
    private val listAuditLogsHandler: ListAuditLogsHandler,
    private val getEntityAuditLogsHandler: GetEntityAuditLogsHandler
) {

    /**
     * Global activity history endpoint.
     * Returns all audit logs for the current studio with optional filters.
     *
     * GET /api/v1/audit/activity?page=1&size=20&modules=CUSTOMER,VEHICLE&actions=CREATE,UPDATE&userId=...&from=...&to=...
     */
    @GetMapping("/activity")
    fun getActivityHistory(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) modules: String?,
        @RequestParam(required = false) actions: String?,
        @RequestParam(required = false) userId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?
    ): ResponseEntity<AuditActivityResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val parsedModules = modules?.split(",")?.mapNotNull { module ->
            try { AuditModule.valueOf(module.trim().uppercase()) } catch (e: Exception) { null }
        }

        val parsedActions = actions?.split(",")?.mapNotNull { action ->
            try { AuditAction.valueOf(action.trim().uppercase()) } catch (e: Exception) { null }
        }

        val parsedUserId = userId?.let {
            try { UserId.fromString(it) } catch (e: Exception) { null }
        }

        val parsedFrom = from?.let {
            try { Instant.parse(it) } catch (e: Exception) { null }
        }

        val parsedTo = to?.let {
            try { Instant.parse(it) } catch (e: Exception) { null }
        }

        val command = ListAuditLogsCommand(
            studioId = principal.studioId,
            page = maxOf(1, page),
            pageSize = maxOf(1, minOf(100, size)),
            modules = parsedModules?.takeIf { it.isNotEmpty() },
            actions = parsedActions?.takeIf { it.isNotEmpty() },
            userId = parsedUserId,
            from = parsedFrom,
            to = parsedTo
        )

        val result = listAuditLogsHandler.handle(command)

        ResponseEntity.ok(mapToResponse(result))
    }

    /**
     * Entity-specific audit log endpoint.
     * Returns all audit logs for a specific entity in a specific module.
     *
     * GET /api/v1/audit/{module}/{entityId}?page=1&size=50
     *
     * Example: GET /api/v1/audit/VEHICLE/550e8400-e29b-41d4-a716-446655440000
     */
    @GetMapping("/{module}/{entityId}")
    fun getEntityAuditLogs(
        @PathVariable module: String,
        @PathVariable entityId: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int
    ): ResponseEntity<AuditActivityResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val parsedModule = try {
            AuditModule.valueOf(module.trim().uppercase())
        } catch (e: Exception) {
            return@runBlocking ResponseEntity.badRequest().build()
        }

        val command = GetEntityAuditLogsCommand(
            studioId = principal.studioId,
            module = parsedModule,
            entityId = entityId,
            page = maxOf(1, page),
            pageSize = maxOf(1, minOf(100, size))
        )

        val result = getEntityAuditLogsHandler.handle(command)

        ResponseEntity.ok(mapToResponse(result))
    }

    /**
     * Get available filter options (modules and actions) for the UI.
     *
     * GET /api/v1/audit/filters
     */
    @GetMapping("/filters")
    fun getFilterOptions(): ResponseEntity<AuditFilterOptionsResponse> {
        val modules = AuditModule.entries.map { AuditFilterOption(it.name, moduleDisplayName(it)) }
        val actions = AuditAction.entries.map { AuditFilterOption(it.name, actionDisplayName(it)) }

        return ResponseEntity.ok(AuditFilterOptionsResponse(
            modules = modules,
            actions = actions
        ))
    }

    private fun mapToResponse(result: AuditLogListResult): AuditActivityResponse {
        return AuditActivityResponse(
            items = result.items.map { item ->
                AuditActivityItem(
                    id = item.id,
                    userId = item.userId,
                    userDisplayName = item.userDisplayName,
                    module = item.module,
                    entityId = item.entityId,
                    entityDisplayName = item.entityDisplayName,
                    action = item.action,
                    changes = item.changes.map { change ->
                        AuditFieldChangeResponse(
                            field = change.field,
                            oldValue = change.oldValue,
                            newValue = change.newValue
                        )
                    },
                    metadata = item.metadata,
                    createdAt = item.createdAt
                )
            },
            pagination = AuditPaginationResponse(
                total = result.total,
                page = result.page,
                pageSize = result.pageSize,
                totalPages = result.totalPages
            )
        )
    }

    private fun moduleDisplayName(module: AuditModule): String = when (module) {
        AuditModule.CUSTOMER -> "Klienci"
        AuditModule.VEHICLE -> "Pojazdy"
        AuditModule.VISIT -> "Wizyty"
        AuditModule.APPOINTMENT -> "Rezerwacje"
        AuditModule.SERVICE -> "Uslugi"
        AuditModule.LEAD -> "Leady"
        AuditModule.PROTOCOL -> "Protokoly"
        AuditModule.CONSENT -> "Zgody"
        AuditModule.INBOUND_CALL -> "Polaczenia przychodzace"
        AuditModule.APPOINTMENT_COLOR -> "Kolory rezerwacji"
        AuditModule.STUDIO -> "Studio"
        AuditModule.USER -> "Uzytkownicy"
        AuditModule.CASH_REGISTER -> "Kasa"
        AuditModule.FINANCE -> "Finanse"
    }

    private fun actionDisplayName(action: AuditAction): String = when (action) {
        AuditAction.CREATE -> "Utworzenie"
        AuditAction.UPDATE -> "Aktualizacja"
        AuditAction.DELETE -> "Usuniecie"
        AuditAction.STATUS_CHANGE -> "Zmiana statusu"
        AuditAction.PHOTO_ADDED -> "Dodanie zdjecia"
        AuditAction.PHOTO_DELETED -> "Usuniecie zdjecia"
        AuditAction.DOCUMENT_ADDED -> "Dodanie dokumentu"
        AuditAction.DOCUMENT_DELETED -> "Usuniecie dokumentu"
        AuditAction.COMMENT_ADDED -> "Dodanie komentarza"
        AuditAction.COMMENT_UPDATED -> "Edycja komentarza"
        AuditAction.COMMENT_DELETED -> "Usuniecie komentarza"
        AuditAction.NOTE_ADDED -> "Dodanie notatki"
        AuditAction.NOTE_UPDATED -> "Edycja notatki"
        AuditAction.NOTE_DELETED -> "Usuniecie notatki"
        AuditAction.SERVICE_ADDED -> "Dodanie uslugi"
        AuditAction.SERVICE_UPDATED -> "Aktualizacja uslugi"
        AuditAction.SERVICE_REMOVED -> "Usuniecie uslugi"
        AuditAction.SERVICES_UPDATED -> "Aktualizacja listy uslug"
        AuditAction.VISIT_CONFIRMED -> "Potwierdzenie wizyty"
        AuditAction.VISIT_CANCELLED -> "Anulowanie wizyty"
        AuditAction.VISIT_COMPLETED -> "Zakonczenie wizyty"
        AuditAction.VISIT_REJECTED -> "Odrzucenie wizyty"
        AuditAction.VISIT_MARKED_READY -> "Oznaczenie jako gotowe"
        AuditAction.VISIT_ARCHIVED -> "Archiwizacja wizyty"
        AuditAction.APPOINTMENT_CANCELLED -> "Anulowanie rezerwacji"
        AuditAction.APPOINTMENT_CONVERTED -> "Konwersja rezerwacji"
        AuditAction.PROTOCOL_GENERATED -> "Wygenerowanie protokolu"
        AuditAction.PROTOCOL_SIGNED -> "Podpisanie protokolu"
        AuditAction.CONSENT_GRANTED -> "Udzielenie zgody"
        AuditAction.CONSENT_REVOKED -> "Cofniecie zgody"
        AuditAction.LEAD_CONVERTED -> "Konwersja leada"
        AuditAction.LEAD_ABANDONED -> "Porzucenie leada"
        AuditAction.CALL_ACCEPTED -> "Przyjecie polaczenia"
        AuditAction.CALL_REJECTED -> "Odrzucenie polaczenia"
        AuditAction.OWNER_ADDED -> "Dodanie wlasciciela"
        AuditAction.OWNER_REMOVED -> "Usuniecie wlasciciela"
        AuditAction.APPOINTMENT_ADDED -> "Dodanie rezerwacji"
        AuditAction.VISIT_ADDED -> "Dodanie wizyty"
        AuditAction.COMPANY_UPDATED -> "Aktualizacja danych firmy"
        AuditAction.COMPANY_DELETED -> "Usuniecie danych firmy"
        AuditAction.APPOINTMENT_DELETED -> "Usunięcie rezerwacji"
        AuditAction.APPOINTMENT_RESTORED -> "Przywrócenie rezerwacji"
        AuditAction.DOCUMENT_ISSUED -> "Wydanie dokumentu"
        AuditAction.DOCUMENT_STATUS_CHANGED -> "Zmiana statusu dokumentu"
        AuditAction.CASH_ADJUSTED -> "Dostosowanie kasy"
        AuditAction.APPOINTMENT_ABANDONED -> "Porzucenie rezerwacji"
    }
}

// Response DTOs

data class AuditActivityResponse(
    val items: List<AuditActivityItem>,
    val pagination: AuditPaginationResponse
)

data class AuditActivityItem(
    val id: String,
    val userId: String,
    val userDisplayName: String,
    val module: String,
    val entityId: String,
    val entityDisplayName: String?,
    val action: String,
    val changes: List<AuditFieldChangeResponse>,
    val metadata: Map<String, String>,
    val createdAt: Instant
)

data class AuditFieldChangeResponse(
    val field: String,
    val oldValue: String?,
    val newValue: String?
)

data class AuditPaginationResponse(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

data class AuditFilterOptionsResponse(
    val modules: List<AuditFilterOption>,
    val actions: List<AuditFilterOption>
)

data class AuditFilterOption(
    val value: String,
    val label: String
)

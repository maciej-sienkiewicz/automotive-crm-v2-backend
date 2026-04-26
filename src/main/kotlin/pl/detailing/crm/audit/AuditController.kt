package pl.detailing.crm.audit

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditAction.*
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditModule.*
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
        CUSTOMER -> "Klienci"
        VEHICLE -> "Pojazdy"
        VISIT -> "Wizyty"
        APPOINTMENT -> "Rezerwacje"
        SERVICE -> "Uslugi"
        LEAD -> "Leady"
        PROTOCOL -> "Protokoly"
        CONSENT -> "Zgody"
        INBOUND_CALL -> "Polaczenia przychodzace"
        APPOINTMENT_COLOR -> "Kolory rezerwacji"
        STUDIO -> "Studio"
        USER -> "Uzytkownicy"
        CASH_REGISTER -> "Kasa"
        FINANCE -> "Finanse"
        EMPLOYEE -> TODO()
        AuditModule.TASK -> TODO()
    }

    private fun actionDisplayName(action: AuditAction): String = when (action) {
        CREATE -> "Utworzenie"
        UPDATE -> "Aktualizacja"
        DELETE -> "Usuniecie"
        STATUS_CHANGE -> "Zmiana statusu"
        PHOTO_ADDED -> "Dodanie zdjecia"
        PHOTO_DELETED -> "Usuniecie zdjecia"
        DOCUMENT_ADDED -> "Dodanie dokumentu"
        DOCUMENT_DELETED -> "Usuniecie dokumentu"
        COMMENT_ADDED -> "Dodanie komentarza"
        COMMENT_UPDATED -> "Edycja komentarza"
        COMMENT_DELETED -> "Usuniecie komentarza"
        NOTE_ADDED -> "Dodanie notatki"
        NOTE_UPDATED -> "Edycja notatki"
        NOTE_DELETED -> "Usuniecie notatki"
        SERVICE_ADDED -> "Dodanie uslugi"
        SERVICE_UPDATED -> "Aktualizacja uslugi"
        SERVICE_REMOVED -> "Usuniecie uslugi"
        SERVICES_UPDATED -> "Aktualizacja listy uslug"
        VISIT_CONFIRMED -> "Potwierdzenie wizyty"
        VISIT_CANCELLED -> "Anulowanie wizyty"
        VISIT_COMPLETED -> "Zakonczenie wizyty"
        VISIT_REJECTED -> "Odrzucenie wizyty"
        VISIT_MARKED_READY -> "Oznaczenie jako gotowe"
        VISIT_ARCHIVED -> "Archiwizacja wizyty"
        APPOINTMENT_CANCELLED -> "Anulowanie rezerwacji"
        APPOINTMENT_CONVERTED -> "Konwersja rezerwacji"
        PROTOCOL_GENERATED -> "Wygenerowanie protokolu"
        PROTOCOL_SIGNED -> "Podpisanie protokolu"
        CONSENT_GRANTED -> "Udzielenie zgody"
        CONSENT_REVOKED -> "Cofniecie zgody"
        LEAD_CONVERTED -> "Konwersja leada"
        LEAD_ABANDONED -> "Porzucenie leada"
        CALL_ACCEPTED -> "Przyjecie polaczenia"
        CALL_REJECTED -> "Odrzucenie polaczenia"
        OWNER_ADDED -> "Dodanie wlasciciela"
        OWNER_REMOVED -> "Usuniecie wlasciciela"
        APPOINTMENT_ADDED -> "Dodanie rezerwacji"
        VISIT_ADDED -> "Dodanie wizyty"
        COMPANY_UPDATED -> "Aktualizacja danych firmy"
        COMPANY_DELETED -> "Usuniecie danych firmy"
        APPOINTMENT_DELETED -> "Usunięcie rezerwacji"
        APPOINTMENT_RESTORED -> "Przywrócenie rezerwacji"
        DOCUMENT_ISSUED -> "Wydanie dokumentu"
        DOCUMENT_STATUS_CHANGED -> "Zmiana statusu dokumentu"
        DOCUMENT_NUMBER_UPDATED -> "Aktualizacja numeru dokumentu"
        DOCUMENT_DELETED -> "Usunięcie dokumentu"
        DOCUMENT_RESTORED -> "Przywrócenie dokumentu"
        CASH_ADJUSTED -> "Dostosowanie kasy"
        APPOINTMENT_ABANDONED -> "Porzucenie rezerwacji"
        EMPLOYEE_TERMINATED -> TODO()
        CONTRACT_CREATED -> TODO()
        CONTRACT_ENDED -> TODO()
        COMPENSATION_SET -> TODO()
        WORK_TIME_LOGGED -> TODO()
        WORK_TIME_APPROVED -> TODO()
        WORK_TIME_REJECTED -> TODO()
        LEAVE_REQUESTED -> TODO()
        LEAVE_APPROVED -> TODO()
        LEAVE_REJECTED -> TODO()
        LEAVE_CANCELLED -> TODO()
        PAYROLL_GENERATED -> TODO()
        PAYROLL_CONFIRMED -> TODO()
        PAYROLL_PAID -> TODO()
        BONUS_ADDED -> "Dodanie bonusu/dodatku"
        BONUS_DELETED -> "Usunięcie bonusu/dodatku"
        WORK_TIME_PERIOD_SAVED -> TODO()
        WORK_TIME_ENTRY_DELETED -> TODO()
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

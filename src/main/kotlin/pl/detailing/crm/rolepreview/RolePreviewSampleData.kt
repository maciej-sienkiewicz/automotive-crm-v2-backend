package pl.detailing.crm.rolepreview

import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.employee.infrastructure.EmployeeEntity
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentSource
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.task.domain.TaskVisibilityType
import pl.detailing.crm.task.infrastructure.TaskEntity
import pl.detailing.crm.task.infrastructure.TaskRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryEntity
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Dane przykładowe piaskownicy ponad to, co daje konto DEMO: zespół, zadania w każdym
 * trybie widoczności, czas pracy pracownika i dokumenty przychodowe.
 *
 * Każda z tych rzeczy odpowiada na któreś z pytań, dla których podgląd istnieje:
 * „czy zobaczy innych pracowników?", „czy zobaczy zadanie przypisane szefowi?",
 * „czy zobaczy obroty?".
 */
@Service
class RolePreviewSampleData(
    private val employeeRepository: EmployeeRepository,
    private val taskRepository: TaskRepository,
    private val workTimeEntryRepository: WorkTimeEntryRepository,
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val financialDocumentRepository: FinancialDocumentRepository
) {

    fun seed(sandbox: SandboxAccounts) {
        seedTeam(sandbox)
        seedTasks(sandbox)
        seedWorkTime(sandbox)
        seedIncomeDocuments(sandbox)
    }

    private fun seedTeam(sandbox: SandboxAccounts) {
        val now = Instant.now()
        val employees = listOf(
            // Karta pracownika podglądanej roli - ta, która ma konto.
            Triple(sandbox.employeeFirstName, sandbox.employeeLastName, sandbox.employeeUserId),
            Triple("Anna", "Przykładowa", null),
            Triple("Tomasz", "Testowy", null),
            Triple("Ewa", "Pokazowa", null)
        )
        employeeRepository.saveAll(
            employees.map { (firstName, lastName, userId) ->
                EmployeeEntity(
                    id = UUID.randomUUID(),
                    studioId = sandbox.studioId,
                    userId = userId,
                    firstName = firstName,
                    lastName = lastName,
                    phone = null,
                    email = null,
                    createdBy = sandbox.ownerUserId,
                    updatedBy = sandbox.ownerUserId,
                    createdAt = now,
                    updatedAt = now
                )
            }
        )
    }

    /**
     * Po jednym zadaniu na każdy tryb widoczności - dzięki temu podgląd pokazuje, że rola
     * widzi zadania wspólne, zadania swojej roli i swoje własne, a nie widzi cudzych.
     */
    private fun seedTasks(sandbox: SandboxAccounts) {
        val now = Instant.now()
        data class TaskSpec(
            val title: String,
            val meta: String?,
            val visibility: TaskVisibilityType,
            val users: List<UUID> = emptyList(),
            val role: UUID? = null,
            val done: Boolean = false,
            val daysAgo: Long
        )
        val specs = listOf(
            TaskSpec("Zamówić pady polerskie i mikrofibry", "Dla całego zespołu", TaskVisibilityType.ALL, daysAgo = 1),
            TaskSpec("Przygotować stanowisko do korekty lakieru", "Zadanie dla roli", TaskVisibilityType.ROLE, role = sandbox.roleId, daysAgo = 2),
            TaskSpec("Oddzwonić do klienta w sprawie ceramiki", "Przypisane do Ciebie", TaskVisibilityType.USERS, users = listOf(sandbox.employeeUserId), daysAgo = 0),
            TaskSpec("Rozliczyć premie za ostatni miesiąc", "Widoczne tylko dla właściciela", TaskVisibilityType.USERS, users = listOf(sandbox.ownerUserId), daysAgo = 3),
            TaskSpec("Uzupełnić zapas szamponu", null, TaskVisibilityType.ALL, done = true, daysAgo = 5)
        )
        taskRepository.saveAll(
            specs.map { spec ->
                val createdAt = now.minus(spec.daysAgo, ChronoUnit.DAYS)
                TaskEntity(
                    id = UUID.randomUUID(),
                    studioId = sandbox.studioId,
                    createdByUserId = sandbox.ownerUserId,
                    title = spec.title,
                    meta = spec.meta,
                    done = spec.done,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                    completedAt = if (spec.done) createdAt.plus(1, ChronoUnit.DAYS) else null,
                    completedByUserId = if (spec.done) sandbox.ownerUserId else null,
                    visibilityType = spec.visibility.name,
                    visibleToUserIds = spec.users.takeIf { it.isNotEmpty() }?.joinToString(","),
                    visibleToRoleId = spec.role
                )
            }
        )
    }

    /** Ostatnie dni robocze pracownika - widok „Czas pracy" nie startuje pusty. */
    private fun seedWorkTime(sandbox: SandboxAccounts) {
        val today = LocalDate.now(ZONE)
        val minutesByDay = listOf(480, 510, 465, 495, 480, 525, 450, 480)
        val workdays = generateSequence(today.minusDays(1)) { it.minusDays(1) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            .take(minutesByDay.size)
            .toList()
        workTimeEntryRepository.saveAll(
            workdays.zip(minutesByDay).map { (date, minutes) ->
                WorkTimeEntryEntity(
                    userId = sandbox.employeeUserId,
                    studioId = sandbox.studioId,
                    date = date,
                    minutes = minutes
                )
            }
        )
    }

    /**
     * Dokumenty przychodowe zakończonych wizyt - żeby rola z uprawnieniami finansowymi
     * zobaczyła listę dokumentów, a rola bez nich przekonała się, że jej nie ma.
     *
     * Kwoty są sumą pozycji wizyty, a VAT to różnica brutto - netto (nie osobne mnożenie),
     * tak jak dokument przychodowy powstaje z prawdziwej wizyty.
     */
    private fun seedIncomeDocuments(sandbox: SandboxAccounts) {
        val completed = visitRepository.findByStudioId(sandbox.studioId)
            .filter { it.status == VisitStatus.COMPLETED }
            .sortedBy { it.scheduledDate }
            .takeLast(MAX_INCOME_DOCUMENTS)
        if (completed.isEmpty()) return

        val customers = customerRepository.findAllById(completed.map { it.customerId }.toSet())
            .associateBy { it.id }
        val now = Instant.now()

        val documents = completed.mapIndexed { index, visit ->
            val totalNet = visit.serviceItems.sumOf { it.finalPriceNet }
            val totalGross = visit.serviceItems.sumOf { it.finalPriceGross }
            val paidAt = visit.pickupDate ?: visit.scheduledDate
            val issueDate = paidAt.atZone(ZONE).toLocalDate()
            val customer = customers[visit.customerId]
            val paymentMethod = PAYMENT_ROTATION[index % PAYMENT_ROTATION.size]
            val type = if (customer?.companyName != null) DocumentType.INVOICE else DocumentType.RECEIPT
            FinancialDocumentEntity(
                id = UUID.randomUUID(),
                studioId = sandbox.studioId,
                source = DocumentSource.VISIT,
                visitId = visit.id,
                vehicleBrand = visit.brandSnapshot,
                vehicleModel = visit.modelSnapshot,
                customerFirstName = customer?.firstName,
                customerLastName = customer?.lastName,
                documentNumber = "${type.prefix}/${issueDate.year}/${(index + 1).toString().padStart(4, '0')}",
                documentType = type,
                direction = DocumentDirection.INCOME,
                status = DocumentStatus.PAID,
                paymentMethod = paymentMethod,
                totalNet = totalNet,
                totalVat = totalGross - totalNet,
                totalGross = totalGross,
                issueDate = issueDate,
                dueDate = null,
                paidAt = paidAt,
                description = "Wizyta ${visit.visitNumber}",
                counterpartyName = customer?.companyName,
                counterpartyNip = customer?.companyNip,
                createdBy = sandbox.ownerUserId,
                updatedBy = sandbox.ownerUserId,
                createdAt = now,
                updatedAt = now
            )
        }
        financialDocumentRepository.saveAll(documents)
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
        const val MAX_INCOME_DOCUMENTS = 12
        val PAYMENT_ROTATION = listOf(PaymentMethod.CARD, PaymentMethod.CASH, PaymentMethod.BLIK_TERMINAL, PaymentMethod.TRANSFER)
    }
}

/** Konta i rola świeżo zakładanej piaskownicy - to, czego potrzebują dane przykładowe. */
data class SandboxAccounts(
    val studioId: UUID,
    val ownerUserId: UUID,
    val employeeUserId: UUID,
    val employeeFirstName: String,
    val employeeLastName: String,
    val roleId: UUID
)

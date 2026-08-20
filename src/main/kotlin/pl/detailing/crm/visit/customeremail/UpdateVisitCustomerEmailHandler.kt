package pl.detailing.crm.visit.customeremail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

/**
 * Uzupełnia adres e-mail klienta wizyty bez ruszania pozostałych jego danych.
 *
 * Modal "Dokumentacja i Podpisy" pojawia się już po utworzeniu wizyty roboczej,
 * więc front nie zna id klienta (mógł zostać właśnie założony) — adresujemy go
 * przez wizytę. Ogólny PATCH /api/v1/customers/{id} nadpisuje cały profil
 * (imię, nazwisko, adres), dlatego nie nadaje się do punktowej poprawki.
 */
@Service
class UpdateVisitCustomerEmailHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val auditService: AuditService
) {
    suspend fun handle(command: UpdateVisitCustomerEmailCommand): UpdateVisitCustomerEmailResult =
        withContext(Dispatchers.IO) {
            val normalizedEmail = command.email.trim().lowercase()
            if (!EMAIL_REGEX.matches(normalizedEmail)) {
                throw ValidationException("Podaj poprawny adres e-mail.")
            }

            val visit = visitRepository.findByIdAndStudioId(
                id = command.visitId.value,
                studioId = command.studioId.value
            ) ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

            val customer = customerRepository.findByIdAndStudioId(
                id = visit.customerId,
                studioId = command.studioId.value
            ) ?: throw EntityNotFoundException("Klient wizyty nie został znaleziony")

            val oldEmail = customer.email
            if (oldEmail == normalizedEmail) {
                return@withContext UpdateVisitCustomerEmailResult(
                    customerId = customer.id.toString(),
                    email = normalizedEmail
                )
            }

            val existingWithEmail = customerRepository.findActiveByStudioIdAndEmail(
                studioId = command.studioId.value,
                email = normalizedEmail
            )
            if (existingWithEmail != null && existingWithEmail.id != customer.id) {
                throw ConflictException("Ten adres e-mail jest już przypisany do innego klienta.")
            }

            customer.email = normalizedEmail
            customer.updatedBy = command.userId.value
            customer.updatedAt = Instant.now()
            val saved = customerRepository.save(customer)

            val displayName = listOfNotNull(saved.firstName, saved.lastName)
                .joinToString(" ")
                .ifBlank { saved.email ?: saved.phone ?: "" }

            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName,
                module = AuditModule.CUSTOMER,
                entityId = saved.id.toString(),
                entityDisplayName = displayName,
                action = AuditAction.UPDATE,
                changes = listOf(FieldChange("email", oldEmail, saved.email)),
                metadata = mapOf("visitId" to command.visitId.value.toString())
            ))

            UpdateVisitCustomerEmailResult(
                customerId = saved.id.toString(),
                email = saved.email ?: normalizedEmail
            )
        }

    private companion object {
        val EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$".toRegex()
    }
}

data class UpdateVisitCustomerEmailCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val email: String
)

data class UpdateVisitCustomerEmailResult(
    val customerId: String,
    val email: String
)

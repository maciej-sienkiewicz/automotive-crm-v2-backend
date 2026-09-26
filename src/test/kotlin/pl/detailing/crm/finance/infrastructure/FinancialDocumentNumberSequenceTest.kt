package pl.detailing.crm.finance.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Licznik numeracji dokumentów przeciwko prawdziwemu Postgresowi: upsert z RETURNING
 * i SUBSTRING ... FROM z grupą w regexie to składnia, której mock nie sprawdzi.
 *
 * `@Tag("testcontainers")`, jak pozostałe testy bazodanowe: poza domyślnym
 * `./gradlew test`, uruchamiany przez `./gradlew test -PrunTestcontainers`. W środowisku,
 * w którym powstał, Docker był niedostępny — plik jest skompilowany, ale NIE uruchomiony.
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class FinancialDocumentNumberSequenceTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired lateinit var sequences: FinancialDocumentNumberSequenceRepository
    @Autowired lateinit var documents: FinancialDocumentRepository

    private val studioId = UUID.randomUUID()

    private fun document(number: String, deleted: Boolean = false) = documents.save(
        FinancialDocumentEntity(
            id = UUID.randomUUID(), studioId = studioId, visitId = null, vehicleBrand = null, vehicleModel = null,
            customerFirstName = null, customerLastName = null, documentNumber = number,
            documentType = DocumentType.RECEIPT, direction = DocumentDirection.INCOME, status = DocumentStatus.PAID,
            paymentMethod = PaymentMethod.CARD, totalNet = 100, totalVat = 23, totalGross = 123,
            issueDate = LocalDate.of(2026, 9, 1), dueDate = null, paidAt = Instant.now(), description = null,
            counterpartyName = null, counterpartyNip = null, createdBy = UUID.randomUUID(), updatedBy = UUID.randomUUID(),
            deletedAt = if (deleted) Instant.now() else null
        )
    )

    @Test
    fun `najwyzszy wydany numer liczy takze dokumenty usuniete`() {
        document("PAR/2026/0002")
        document("PAR/2026/0007", deleted = true)
        document("PAR/2025/0042")
        document("PAR/2026/ręczny")

        assertEquals(7L, sequences.maxIssuedSequence(studioId, "^PAR/2026/([0-9]+)$"))
    }

    @Test
    fun `licznik startuje od ziarna i dalej rosnie o jeden`() {
        assertEquals(8L, sequences.nextValue(studioId, "PAR", 2026, 8))
        assertEquals(9L, sequences.nextValue(studioId, "PAR", 2026, 8))
        assertEquals(1L, sequences.nextValue(studioId, "FAK", 2026, 1), "inna seria ma własny licznik")
    }
}

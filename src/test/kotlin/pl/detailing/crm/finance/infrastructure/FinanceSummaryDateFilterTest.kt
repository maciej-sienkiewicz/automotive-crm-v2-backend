package pl.detailing.crm.finance.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.domain.PageRequest
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
 * Sumy do kafli „Podsumowanie finansowe" - przeciwko prawdziwemu Postgresowi.
 *
 * Zgłoszenie z produkcji: przy zakresie „Cały czas" (obie daty puste) endpoint
 * wywracał się na `could not determine data type of parameter $4` i sekcja nie
 * ładowała się wcale. Powód był w SQL, nie w Kotlinie: warunek
 * „(:dateFrom IS NULL OR issue_date >= :dateFrom)" zostawiał parametr samotnie
 * przy IS NULL, a stamtąd serwer nie ma jak wywnioskować typu NULL-a.
 *
 * Dlatego ten test musi UDERZYĆ W BAZĘ - kompilator i mock takiego błędu nie
 * zobaczą, a jest on niewidoczny do chwili, gdy ktoś wybierze pusty zakres.
 *
 * Tak jak pozostałe testy bazodanowe w repo jest oznaczony `@Tag("testcontainers")`
 * i wyłączony z domyślnego `./gradlew test`; uruchamia się go przez
 * `./gradlew test -PrunTestcontainers`. W środowisku, w którym powstał, Docker
 * był niedostępny (`docker info` failed), więc plik jest sprawdzony pod kątem
 * kompilacji, ale NIE uruchomiony.
 */
@Tag("testcontainers")
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class FinanceSummaryDateFilterTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired lateinit var repository: FinancialDocumentRepository

    private val studioId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val paid = listOf(DocumentStatus.PAID)

    private fun document(netCents: Long, issued: LocalDate, direction: DocumentDirection = DocumentDirection.INCOME) =
        repository.save(
            FinancialDocumentEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                visitId = null,
                vehicleBrand = null,
                vehicleModel = null,
                customerFirstName = null,
                customerLastName = null,
                documentNumber = "FV/${UUID.randomUUID()}",
                documentType = DocumentType.INVOICE,
                direction = direction,
                status = DocumentStatus.PAID,
                paymentMethod = PaymentMethod.TRANSFER,
                totalNet = netCents,
                totalVat = 0,
                totalGross = netCents,
                issueDate = issued,
                dueDate = null,
                paidAt = Instant.now(),
                description = null,
                counterpartyName = null,
                counterpartyNip = null,
                createdBy = userId,
                updatedBy = userId
            )
        )

    @Test
    fun `pusty zakres sumuje wszystko, zamiast wywracac zapytanie`() {
        document(10_000, LocalDate.of(2024, 3, 1))
        document(5_000, LocalDate.of(2026, 9, 18))

        val sum = repository.sumNet(studioId, DocumentDirection.INCOME, paid, null, null)

        assertEquals(15_000L, sum)
    }

    @Test
    fun `sama data od obcina starsze dokumenty`() {
        document(10_000, LocalDate.of(2024, 3, 1))
        document(5_000, LocalDate.of(2026, 9, 18))

        val sum = repository.sumNet(studioId, DocumentDirection.INCOME, paid, LocalDate.of(2026, 1, 1), null)

        assertEquals(5_000L, sum)
    }

    @Test
    fun `sama data do obcina nowsze dokumenty`() {
        document(10_000, LocalDate.of(2024, 3, 1))
        document(5_000, LocalDate.of(2026, 9, 18))

        val sum = repository.sumNet(studioId, DocumentDirection.INCOME, paid, null, LocalDate.of(2025, 1, 1))

        assertEquals(10_000L, sum)
    }

    @Test
    fun `granice zakresu sa domkniete z obu stron`() {
        document(1_000, LocalDate.of(2026, 9, 1))
        document(2_000, LocalDate.of(2026, 9, 30))
        document(4_000, LocalDate.of(2026, 10, 1))

        val sum = repository.sumNet(
            studioId, DocumentDirection.INCOME, paid,
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)
        )

        assertEquals(3_000L, sum)
    }

    @Test
    fun `pusty zakres w liscie dokumentow tez nie wywraca zapytania`() {
        document(10_000, LocalDate.of(2024, 3, 1))
        document(7_000, LocalDate.of(2026, 9, 18), direction = DocumentDirection.EXPENSE)

        val all = repository.findWithFilters(
            studioId = studioId,
            documentType = null,
            direction = null,
            status = null,
            visitId = null,
            dateFrom = null,
            dateTo = null,
            includeDeleted = false,
            pageable = PageRequest.of(0, 50)
        )

        assertEquals(2, all.totalElements.toInt())
    }
}

package pl.detailing.crm.finance.document

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentNumberSequenceRepository
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.LocalDate

/**
 * Numer paragonu liczony jako „liczba dokumentów w roku + 1" wracał po usunięciu
 * dokumentu: PAR/2026/0003 usunięty → następny paragon znowu PAR/2026/0003.
 * Teraz numer pochodzi z licznika, który zna tylko kierunek „w górę".
 */
class DocumentNumberingTest {

    private val studioId = StudioId.random()
    private val sequences: FinancialDocumentNumberSequenceRepository = mockk()
    private val documents: FinancialDocumentRepository = mockk {
        every { save(any()) } answers { firstArg() }
    }

    private val handler = CreateFinancialDocumentHandler(
        documents, mockk(relaxed = true), mockk(relaxed = true), sequences,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true)
    )

    private fun issueReceipt(issueDate: LocalDate = LocalDate.of(2026, 9, 26)) = handler.handle(
        CreateFinancialDocumentCommand(
            studioId = studioId, userId = UserId.random(), userDisplayName = "Anna",
            visitId = null, documentType = DocumentType.RECEIPT, direction = DocumentDirection.INCOME,
            paymentMethod = PaymentMethod.CARD, totalNet = 15_447, totalVat = 3_553, totalGross = 19_000,
            issueDate = issueDate, dueDate = issueDate, description = null,
            counterpartyName = null, counterpartyNip = null
        )
    )

    @Test
    fun `numer pochodzi z licznika serii, a nie z liczby dokumentow`() {
        every { sequences.maxIssuedSequence(studioId.value, "^PAR/2026/([0-9]+)$") } returns 3
        val seed = slot<Long>()
        every { sequences.nextValue(studioId.value, "PAR", 2026, capture(seed)) } returns 4

        val document = issueReceipt()

        assertEquals("PAR/2026/0004", document.documentNumber)
        assertEquals(4L, seed.captured, "seria sprzed licznika startuje za najwyższym wydanym numerem")
    }

    @Test
    fun `seria roku bierze rok z daty wystawienia`() {
        every { sequences.maxIssuedSequence(studioId.value, "^PAR/2025/([0-9]+)$") } returns 0
        every { sequences.nextValue(studioId.value, "PAR", 2025, 1) } returns 1

        assertEquals("PAR/2025/0001", issueReceipt(LocalDate.of(2025, 12, 31)).documentNumber)
    }
}

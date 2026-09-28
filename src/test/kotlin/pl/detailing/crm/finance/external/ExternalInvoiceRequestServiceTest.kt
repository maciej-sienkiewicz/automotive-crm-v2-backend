package pl.detailing.crm.finance.external

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Lista „Do zafakturowania": odhacza człowiek, nic nie łączy się samo z fakturą z KSeF.
 */
class ExternalInvoiceRequestServiceTest {

    private val studioId = StudioId.random()
    private val userId = UserId.random()
    private val visitId = UUID.randomUUID()
    private val requests = mutableListOf<ExternalInvoiceRequestEntity>()

    private val repository: ExternalInvoiceRequestRepository = mockk {
        every { save(any()) } answers {
            firstArg<ExternalInvoiceRequestEntity>().also { r -> if (requests.none { it.id == r.id }) requests += r }
        }
        every { findByIdAndStudioId(any(), any()) } answers {
            requests.firstOrNull { it.id == firstArg<UUID>() && it.studioId == secondArg<UUID>() }
        }
        every { findByStudioIdAndVisitIdOrderByCreatedAtAsc(any(), any()) } answers {
            requests.filter { it.visitId == secondArg<UUID>() }
        }
        every { findActiveByDocuments(any(), any()) } answers {
            requests.filter { it.financialDocumentId in secondArg<Collection<UUID>>() && it.isActive }
        }
        every { findWithdrawnByDocument(any(), any()) } answers {
            requests.filter { it.financialDocumentId == secondArg<UUID>() && !it.isActive }
        }
    }
    private val service = ExternalInvoiceRequestService(repository, mockk<AuditService>(relaxed = true))

    private fun request(status: ExternalInvoiceStatus = ExternalInvoiceStatus.PENDING) = ExternalInvoiceRequestEntity(
        studioId = studioId.value, visitId = visitId, financialDocumentId = UUID.randomUUID(),
        kind = ExternalInvoiceKind.INVOICE, status = status, buyerNip = "5261040828", buyerName = "Auto Serwis",
        buyerAddressLine1 = null, buyerAddressLine2 = null, buyerEmail = null,
        totalNet = 154_472, totalVat = 35_528, totalGross = 190_000, createdBy = userId.value
    ).also { requests += it }

    @Test
    fun `faktura wystawiona z numerem i bez`() {
        val withNumber = request()
        val withoutNumber = request()

        service.markIssued(studioId, withNumber.id, "  FV 12/09/2026 ", userId, "Anna Nowak")
        service.markIssued(studioId, withoutNumber.id, null, userId, "Anna Nowak")

        assertEquals(ExternalInvoiceStatus.ISSUED, withNumber.status)
        assertEquals("FV 12/09/2026", withNumber.externalInvoiceNumber)
        assertEquals("Anna Nowak", withNumber.issuedByName)
        assertNotNull(withNumber.issuedAt)
        assertEquals(ExternalInvoiceStatus.ISSUED, withoutNumber.status)
        assertNull(withoutNumber.externalInvoiceNumber)
    }

    @Test
    fun `poprawienie numeru nie zmienia daty odhaczenia`() {
        val r = request()
        service.markIssued(studioId, r.id, null, userId, "Anna")
        val first = r.issuedAt

        service.markIssued(studioId, r.id, "FV 1/2026", userId, "Anna")

        assertEquals(first, r.issuedAt)
        assertEquals("FV 1/2026", r.externalInvoiceNumber)
    }

    @Test
    fun `wycofanego nie da sie odhaczyc`() {
        val r = request(ExternalInvoiceStatus.WITHDRAWN)

        assertThrows<ConflictException> { service.markIssued(studioId, r.id, null, userId, "Anna") }
    }

    @Test
    fun `cofniecie odhaczenia wraca do listy czekajacych`() {
        val r = request()
        service.markIssued(studioId, r.id, "FV 1/2026", userId, "Anna")

        service.unmarkIssued(studioId, r.id, userId, "Anna")

        assertEquals(ExternalInvoiceStatus.PENDING, r.status)
        assertNull(r.externalInvoiceNumber)
        assertNull(r.issuedAt)
    }

    @Test
    fun `czekajace zgloszenie przestaje obowiazywac - wycofanie bez korekty`() {
        val r = request()

        val correction = service.retire(r, UUID.randomUUID(), userId.value, UUID.randomUUID(), Instant.now())

        assertNull(correction)
        assertEquals(ExternalInvoiceStatus.WITHDRAWN, r.status)
        assertNotNull(r.withdrawnAt)
    }

    @Test
    fun `wystawione zgloszenie przestaje obowiazywac - korekta z kwotami ujemnymi co do grosza`() {
        val r = request(ExternalInvoiceStatus.ISSUED)
        val storno = UUID.randomUUID()

        val correction = service.retire(r, storno, userId.value, UUID.randomUUID(), Instant.now())!!

        assertEquals(ExternalInvoiceKind.CORRECTION, correction.kind)
        assertEquals(r.id, correction.correctsRequestId)
        assertEquals(storno, correction.financialDocumentId)
        assertEquals(-190_000, correction.totalGross, "brutto faktury, nie odtworzone z netto")
        assertEquals(-154_472, correction.totalNet)
        assertEquals(-35_528, correction.totalVat)
        assertEquals("5261040828", correction.buyerNip)
        assertEquals(ExternalInvoiceStatus.ISSUED, r.status)
    }

    @Test
    fun `cofniecie odhaczenia faktury z korekta jest blokowane`() {
        val r = request(ExternalInvoiceStatus.ISSUED)
        service.retire(r, UUID.randomUUID(), userId.value, UUID.randomUUID(), Instant.now())

        assertThrows<ConflictException> { service.unmarkIssued(studioId, r.id, userId, "Anna") }
    }

    @Test
    fun `usuniecie dokumentu wycofuje czekajace, przywrocenie je przywraca`() {
        val r = request()

        service.withdrawForDeletedDocument(studioId.value, r.financialDocumentId)
        assertEquals(ExternalInvoiceStatus.WITHDRAWN, r.status)

        service.reopenForRestoredDocument(studioId.value, r.financialDocumentId)
        assertEquals(ExternalInvoiceStatus.PENDING, r.status)
    }

    @Test
    fun `dokumentu z wystawiona faktura ksiegowosci nie da sie usunac`() {
        val r = request(ExternalInvoiceStatus.ISSUED)

        assertThrows<ConflictException> { service.withdrawForDeletedDocument(studioId.value, r.financialDocumentId) }
    }
}

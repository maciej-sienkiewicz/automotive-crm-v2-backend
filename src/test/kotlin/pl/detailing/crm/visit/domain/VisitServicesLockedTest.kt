package pl.detailing.crm.visit.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitStatus

/**
 * Parameter Tampering — maszyna stanów wizyty.
 *
 * Luka: pozycje usług (a więc kwota wizyty) dało się zmieniać w dowolnym statusie,
 * także po zamknięciu — gdy paragon / faktura KSeF były już wystawione z innej kwoty.
 */
class VisitServicesLockedTest {

    private val user = UserId.random()

    @Test
    fun `a COMPLETED visit refuses service changes, approvals and rejections`() {
        listOf(VisitStatus.COMPLETED, VisitStatus.REJECTED, VisitStatus.ARCHIVED).forEach { status ->
            val visit = VisitFixtures.visit(status = status)
            val item = visit.serviceItems.first()

            assertThrows<IllegalStateTransitionException>("saveServicesChanges in $status") {
                visit.saveServicesChanges(added = listOf(VisitFixtures.serviceItem(1)), updated = emptyList(), deletedIds = emptyList(), updatedBy = user)
            }
            assertThrows<IllegalStateTransitionException>("approveService in $status") {
                visit.approveService(item.id, user)
            }
            assertThrows<IllegalStateTransitionException>("rejectService in $status") {
                visit.rejectService(item.id, user)
            }
        }
    }

    @Test
    fun `an open visit still accepts service changes`() {
        listOf(VisitStatus.DRAFT, VisitStatus.IN_PROGRESS, VisitStatus.READY_FOR_PICKUP).forEach { status ->
            val visit = VisitFixtures.visit(status = status)
            val updated = visit.saveServicesChanges(
                added = listOf(VisitFixtures.serviceItem(500)), updated = emptyList(), deletedIds = emptyList(), updatedBy = user
            )
            assertEquals(2, updated.serviceItems.size, "in $status")
        }
    }
}

package pl.detailing.crm.audit.feed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/**
 * Kopia zdarzenia po stronie drugiego obiektu ("Dodano rezerwację" na pojeździe obok
 * "Utworzono rezerwację") była w firmowej Aktywności drugim wierszem tego samego
 * kliknięcia. Te testy pilnują, gdzie znika, a gdzie musi zostać.
 */
class MirrorActionFilterTest {

    private val studioId = StudioId(UUID.randomUUID())

    private fun command(
        actions: List<AuditAction>? = null,
        customerId: UUID? = null,
        vehicleId: UUID? = null,
        visitId: UUID? = null,
        correlationId: UUID? = null,
        entityId: String? = null
    ) = GetAuditFeedCommand(
        studioId = studioId,
        actions = actions,
        customerId = customerId,
        vehicleId = vehicleId,
        visitId = visitId,
        correlationId = correlationId,
        entityId = entityId
    )

    @Test
    fun `firmowa Aktywnosc nie pokazuje kopii zdarzen`() {
        val hidden = mirrorActionsHiddenFrom(command())

        assertTrue(hidden.isNotEmpty()) { "Nie odsiano żadnej kopii — feed pokaże duplikaty" }
        assertTrue(hidden.all { it.entityMirror }) { "Odsiano akcję, która kopią nie jest: $hidden" }
        assertEquals(AuditAction.entries.filter { it.entityMirror }.toSet(), hidden.toSet())
        assertTrue(AuditAction.APPOINTMENT_ADDED in hidden)
    }

    @Test
    fun `historia jednego obiektu kopie zostawia`() {
        // Wpisy sprzed AuditContextResolver nie mają kolumny kontekstu — dla nich kopia
        // jest jedynym śladem rezerwacji na karcie pojazdu, więc karta musi ją widzieć.
        listOf(
            command(vehicleId = UUID.randomUUID()),
            command(customerId = UUID.randomUUID()),
            command(visitId = UUID.randomUUID()),
            command(correlationId = UUID.randomUUID()),
            command(entityId = UUID.randomUUID().toString())
        ).forEach { scoped ->
            assertTrue(mirrorActionsHiddenFrom(scoped).isEmpty()) {
                "Historia obiektu straciła kopie zdarzeń: $scoped"
            }
        }
    }

    @Test
    fun `jawny filtr akcji wygrywa z odsiewem`() {
        // Filtr, który po zaznaczeniu nie zwraca nic, czyta się jak zepsuty.
        val explicit = command(actions = listOf(AuditAction.APPOINTMENT_ADDED))

        assertTrue(mirrorActionsHiddenFrom(explicit).isEmpty())
    }
}

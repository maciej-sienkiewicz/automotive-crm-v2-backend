package pl.detailing.crm.communication.rehearsal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.communication.template.MessageTemplateKind
import java.time.LocalDate

class RehearsalFixtureTest {

    @Test
    fun `fixture covers every placeholder of every template kind`() {
        MessageTemplateKind.entries.forEach { kind ->
            val missing = kind.allowedPlaceholders - RehearsalFixture.values(kind, 1).keys
            assertEquals(emptySet<String>(), missing, "brak danych przykładowych dla $kind")
        }
    }

    @Test
    fun `fixture hands the renderer nothing the kind does not allow`() {
        MessageTemplateKind.entries.forEach { kind ->
            val extra = RehearsalFixture.values(kind, 1).keys - kind.allowedPlaceholders
            assertEquals(emptySet<String>(), extra, kind.name)
        }
    }

    @Test
    fun `the appointment is tomorrow at ten in Warsaw`() {
        val values = RehearsalFixture.allValues(1, RehearsalFixture.tomorrowTen(LocalDate.of(2026, 9, 3)))
        assertEquals("04.09.2026", values["data"])
        assertEquals("10:00", values["godzina"])
    }
}

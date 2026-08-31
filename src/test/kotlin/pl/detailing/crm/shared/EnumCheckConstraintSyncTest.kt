package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.audit.domain.AuditModule
import java.io.File

/**
 * Pilnuje, żeby CHECK-i w bazie nadążały za enumami w kodzie.
 *
 * ## Dlaczego to jest test, a nie komentarz w migracji
 *
 * Lista dozwolonych wartości w `CHECK (... IN (...))` jest ręcznie utrzymywaną kopią
 * enuma — w innym języku, w innym pliku, dopisywaną przy innej okazji. Nic ich ze sobą
 * nie łączy, więc rozjazd nie jest ryzykiem teoretycznym, tylko kwestią czasu. I nie
 * daje się zauważyć: kod się kompiluje, testy przechodzą, a pierwszym objawem jest
 * naruszenie CHECK-a na produkcji — w najlepszym razie po cichu zgubiony wiersz,
 * w najgorszym rollback operacji biznesowej PO wysłaniu SMS-a do klienta (dokładnie to
 * spotkało SIGNATURE_LINK_SMS, patrz V100__sync_enum_check_constraints.sql).
 *
 * Ten test zamienia awarię produkcyjną w czerwony build.
 *
 * ## Jak działa
 *
 * Czyta wszystkie migracje po kolei i zapamiętuje OSTATNIĄ definicję każdego CHECK-a —
 * bo migracje przedefiniowują je przez DROP + ADD, więc obowiązuje ta z najwyższym
 * numerem. Potem sprawdza, że każda stała enuma jest w tej liście.
 *
 * Kierunek sprawdzenia jest jednostronny i celowo: enum ⊆ CHECK. Wartość, która została
 * w bazie po usuniętej stałej, nikomu nie szkodzi (stare wiersze muszą się nadal
 * odczytywać), a brakująca — psuje zapis.
 */
class EnumCheckConstraintSyncTest {

    private val migrations = File("src/main/resources/db/migration")

    @Test
    fun `communication_log message_type CHECK pokrywa cały enum`() {
        assertConstraintCovers(
            constraintName = "communication_log_message_type_check",
            expected = CommunicationMessageType.entries.map { it.name }
        )
    }

    @Test
    fun `audit_logs module CHECK pokrywa cały enum`() {
        assertConstraintCovers(
            constraintName = "audit_logs_module_check",
            expected = AuditModule.entries.map { it.name }
        )
    }

    @Test
    fun `visits status CHECK pokrywa cały enum`() {
        assertConstraintCovers(
            constraintName = "visits_status_check",
            expected = VisitStatus.entries.map { it.name }
        )
    }

    @Test
    fun `communication_log status CHECK pokrywa cały enum`() {
        assertConstraintCovers(
            constraintName = "communication_log_status_check",
            expected = CommunicationStatus.entries.map { it.name }
        )
    }

    private fun assertConstraintCovers(constraintName: String, expected: List<String>) {
        val allowed = latestAllowedValues(constraintName)
            ?: error(
                "Nie znaleziono definicji CHECK-a '$constraintName' w migracjach. " +
                    "Jeśli został celowo usunięty, usuń też ten przypadek testowy."
            )

        val missing = expected - allowed
        assertTrue(
            missing.isEmpty(),
            "CHECK '$constraintName' nie zna wartości: ${missing.joinToString()}. " +
                "Dopisz je nową migracją (DROP + ADD CONSTRAINT), inaczej zapis z tą " +
                "wartością wywali się naruszeniem ograniczenia dopiero na produkcji."
        )
    }

    /**
     * Wartości z ostatniej w kolejności migracji, która definiuje ten CHECK.
     *
     * Migracje sortujemy po numerze wersji, a nie alfabetycznie — inaczej `V9` wypadłoby
     * po `V100` i test czytałby przedostatnią definicję.
     */
    private fun latestAllowedValues(constraintName: String): Set<String>? {
        val pattern = Regex(
            """ADD\s+CONSTRAINT\s+$constraintName\s+CHECK\s*\([^(]*IN\s*\((.*?)\)\s*\)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        return migrations.listFiles { file -> file.extension == "sql" }
            .orEmpty()
            .sortedBy { versionOf(it.name) }
            .mapNotNull { file -> pattern.find(file.readText())?.groupValues?.get(1) }
            .lastOrNull()
            ?.let { list ->
                Regex("'([^']+)'").findAll(list).map { it.groupValues[1] }.toSet()
            }
    }

    /** `V100__sync_enum_check_constraints.sql` → 100. */
    private fun versionOf(fileName: String): Int =
        fileName.removePrefix("V").substringBefore("__").toIntOrNull() ?: Int.MAX_VALUE
}

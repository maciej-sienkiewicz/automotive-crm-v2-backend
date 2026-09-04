package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Każdy numer migracji występuje najwyżej raz.
 *
 * Flyway odmawia startu, gdy dwa pliki niosą tę samą wersję — aplikacja nie wstaje
 * w ogóle, a błąd wychodzi dopiero przy wdrożeniu, nie przy pisaniu kodu.
 *
 * Kolizja jest łatwiejsza do popełnienia, niż wygląda, i to z jednego konkretnego
 * powodu: `ls` sortuje leksykograficznie, więc „V100" stoi PRZED „V95", a końcówka
 * listingu pokazuje V99 jako rzekomo ostatnią migrację. Kto weźmie stamtąd kolejny
 * numer, trafia w środek istniejącej numeracji. Dokładnie tak powstał duplikat V100.
 *
 * Drugie źródło to dwie gałęzie rozwijane równolegle, które sięgnęły po ten sam
 * wolny numer i zderzyły się dopiero przy scalaniu.
 *
 * Test kosztuje jedno przejście po katalogu i zamienia awarię wdrożenia w czerwony
 * build u autora zmiany.
 */
class UniqueMigrationVersionsTest {

    private val migrations = File("src/main/resources/db/migration")

    @Test
    fun `numery migracji sie nie powtarzaja`() {
        val duplicates = migrations.listFiles { file -> file.extension == "sql" }
            .orEmpty()
            .groupBy { versionOf(it.name) }
            .filterValues { it.size > 1 }
            .toSortedMap()

        assertTrue(
            duplicates.isEmpty(),
            buildString {
                appendLine("Zduplikowane wersje migracji — Flyway nie wystartuje:")
                duplicates.forEach { (version, files) ->
                    appendLine("  V$version: ${files.joinToString(", ") { it.name }}")
                }
                appendLine()
                appendLine("Kolejny wolny numer sprawdzaj sortowaniem wersji, nie zwykłym `ls`:")
                appendLine("  ls src/main/resources/db/migration | sort -V | tail -1")
            }
        )
    }

    /** `V109__auto_lead_classification.sql` → 109; nazwa spoza konwencji → -1. */
    private fun versionOf(fileName: String): Int =
        Regex("^V(\\d+)__").find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    @Test
    fun `kazda migracja trzyma sie konwencji nazw`() {
        // Plik spoza konwencji Flyway po prostu nie zostanie uruchomiony — po cichu,
        // bez błędu. Schemat rozjeżdża się wtedy z kodem dopiero na produkcji.
        val malformed = migrations.listFiles { file -> file.extension == "sql" }
            .orEmpty()
            .filter { versionOf(it.name) < 0 }
            .map { it.name }

        assertTrue(
            malformed.isEmpty(),
            "Pliki spoza konwencji V<numer>__<opis>.sql nie zostaną uruchomione: $malformed"
        )
    }
}

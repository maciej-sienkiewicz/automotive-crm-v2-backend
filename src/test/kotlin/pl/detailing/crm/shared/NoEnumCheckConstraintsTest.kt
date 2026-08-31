package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pilnuje, żeby enumowe CHECK-i nie wróciły do schematu.
 *
 * V101 usunęła je świadomie: lista dozwolonych wartości powstawała raz, przy zakładaniu
 * tabeli przez `ddl-auto=update`, i nigdy się nie aktualizowała. Enum w Kotlinie rósł,
 * baza zostawała w tyle, a rozjazd wychodził dopiero na produkcji — raz kosztem SMS-a
 * wysłanego do klienta z linkiem, który już nie działał, raz kosztem czterech modułów
 * milcząco nieobecnych w dzienniku.
 *
 * Odruch „dopiszę brakującą wartość do CHECK-a" jest naturalny i dokładnie tak wracały
 * poprzednie wersje tego problemu. Ten test zamienia go w czerwony build i odsyła do
 * decyzji, zamiast pozwolić cicho odbudować mechanizm.
 *
 * Ograniczenia biznesowe (`minutes >= 0`, `end_date >= start_date`) są całkowicie w
 * porządku i test ich nie dotyczy — chodzi wyłącznie o wyliczanie stałych enuma.
 */
class NoEnumCheckConstraintsTest {

    /** Numer migracji, od której obowiązuje zakaz. Wcześniejsze są historią. */
    private val firstForbiddenVersion = 102

    private val migrations = File("src/main/resources/db/migration")

    @Test
    fun `zadna nowa migracja nie dodaje enumowego CHECK-a`() {
        val offenders = migrations.listFiles { file -> file.extension == "sql" }
            .orEmpty()
            .filter { versionOf(it.name) >= firstForbiddenVersion }
            .filter { it.readText().containsEnumCheckConstraint() }
            .map { it.name }

        assertTrue(
            offenders.isEmpty(),
            """
            Migracje dodają CHECK wyliczający wartości enuma: ${offenders.joinToString()}

            Te ograniczenia zostały usunięte w V101 i nie mają wracać. Lista wartości
            w bazie jest ręczną kopią enuma z kodu — rozjeżdża się przy każdej nowej
            stałej i wychodzi dopiero na produkcji, przy pierwszym jej użyciu.
            Poprawności pilnuje typ w Kotlinie; Hibernate nie ma jak zapisać wartości
            spoza enuma.
            """.trimIndent()
        )
    }

    /**
     * `CHECK (kolumna IN ('A', 'B'))` w dowolnym formatowaniu, także rozbite na wiele
     * linii. Świadomie nie łapie `CHECK (x IN (SELECT ...))` ani porównań liczbowych.
     */
    private fun String.containsEnumCheckConstraint(): Boolean =
        Regex(
            """CHECK\s*\([^()]*\bIN\s*\(\s*'""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).containsMatchIn(this)

    /** `V101__drop_enum_check_constraints.sql` → 101. */
    private fun versionOf(fileName: String): Int =
        fileName.removePrefix("V").substringBefore("__").toIntOrNull() ?: Int.MAX_VALUE
}

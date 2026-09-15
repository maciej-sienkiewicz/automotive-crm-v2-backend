package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Pilnuje typów kolumn, na których Hibernate wywraca start aplikacji.
 *
 * Na wdrożonych środowiskach działa `ddl-auto=validate`: Hibernate porównuje każdą
 * kolumnę z tym, co wynika z encji, i przy pierwszej różnicy nie buduje w ogóle
 * EntityManagerFactory. Objaw jest przy tym mylący — w logu leci kaskada
 * UnsatisfiedDependencyException po pierwszym lepszym beanie, który poprosił o
 * EntityManagera (u nas: piiAccessFilter), a prawdziwa przyczyna to jedno zdanie
 * gdzieś na dole stosu.
 *
 * Porównanie idzie przez Dialect.equivalentTypes, więc „prawie ten sam typ" nie
 * przechodzi. Dwie pary wracały u nas najczęściej:
 *
 *   CHAR(n)  przy `@Column(length = n)` na Stringu — Hibernate oczekuje varchar,
 *            baza zgłasza bpchar. V70 naprawiona przez V73, V91 przez V92,
 *            V133 przez V134. Trzy razy ten sam hasz SHA-256 w CHAR(64).
 *
 *   SMALLINT przy polu `Int` — Hibernate oczekuje int4, baza zgłasza int2.
 *            Osiem kolumn z V130-V133 naraz, wszystkie naprawione w V134.
 *
 * Dlatego w nowych migracjach nie używamy ani CHAR(n), ani SMALLINT. Oszczędność
 * jest żadna (bajty na wiersz), a koszt pomyłki to wdrożenie, które nie wstaje.
 * Gdy int2 jest naprawdę potrzebny, pole w encji musi być `Short` — wtedy typy
 * się zgadzają i ten test nie ma nic do roboty, bo SMALLINT i tak nie padnie
 * w SQL-u bez świadomej decyzji.
 *
 * Test kosztuje jedno przejście po katalogu migracji i nie potrzebuje bazy.
 */
class NoNarrowColumnTypesTest {

    /** Numer migracji, od której obowiązuje zakaz. Wcześniejsze są historią. */
    private val firstForbiddenVersion = 134

    private val migrations = File("src/main/resources/db/migration")

    /** CHAR(64), ale nie VARCHAR(64) i nie NCHAR(64). */
    private val charDeclaration = Regex("(?<![A-Za-z_])CHAR\\s*\\(", RegexOption.IGNORE_CASE)

    private val smallintDeclaration = Regex("(?<![A-Za-z_])SMALLINT(?![A-Za-z_])", RegexOption.IGNORE_CASE)

    @Test
    fun `zadna nowa migracja nie zaklada kolumny CHAR ani SMALLINT`() {
        val offenders = migrations.listFiles { file -> file.extension == "sql" }
            .orEmpty()
            .filter { versionOf(it.name) >= firstForbiddenVersion }
            .mapNotNull { file ->
                val sql = file.readText().withoutComments()
                val found = buildList {
                    if (charDeclaration.containsMatchIn(sql)) add("CHAR(n) → użyj VARCHAR(n)")
                    if (smallintDeclaration.containsMatchIn(sql)) add("SMALLINT → użyj INTEGER")
                }
                if (found.isEmpty()) null else "${file.name}: ${found.joinToString("; ")}"
            }

        assertTrue(
            offenders.isEmpty(),
            """
            Migracje zakładają kolumny typu, którego encja nie oczekuje:
            ${offenders.joinToString("\n            ")}

            Na wdrożeniu działa ddl-auto=validate i aplikacja po prostu nie wstanie:
              found [bpchar (Types#CHAR)], but expecting [varchar(64) (Types#VARCHAR)]
              found [int2 (Types#SMALLINT)], but expecting [integer (Types#INTEGER)]

            Historia tej pomyłki: V70→V73, V91→V92, V130-V133→V134.
            Jeśli int2 jest naprawdę potrzebny, pole w encji musi być Short.
            """.trimIndent()
        )
    }

    /** Komentarze opisują właśnie te typy — skanujemy sam SQL, nie prozę o nim. */
    private fun String.withoutComments(): String =
        lineSequence().joinToString("\n") { it.substringBefore("--") }

    /** `V134__column_type_fixes.sql` → 134; nazwa spoza konwencji → -1. */
    private fun versionOf(fileName: String): Int =
        Regex("^V(\\d+)__").find(fileName)?.groupValues?.get(1)?.toIntOrNull() ?: -1
}

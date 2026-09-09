package pl.detailing.crm.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Dowodzi, że [EnumCheckConstraintDropper] faktycznie usuwa enumowy CHECK w postaci, jaką
 * Postgres realnie zwraca dla kolumny `varchar`: `= ANY ((ARRAY[...])::text[])` — z PODWÓJNYM
 * nawiasem, bo tablica jest rzutowana na `text[]`.
 *
 * Wcześniejszy wzorzec `'%= ANY (ARRAY[%'` (jeden nawias) tej formy nie łapał, więc drop
 * nie ruszał niczego. Tak przetrwał m.in. `communication_log_status_check` bez wartości
 * QUEUED i wywracał wieczorne wysyłki (Karta Wizyty poza oknem 12:00–18:00 → status QUEUED).
 * Statyczny [pl.detailing.crm.shared.NoEnumCheckConstraintsTest] czyta tylko treść migracji
 * i tego nie wykrył — tu drop leci na PRAWDZIWYM Postgresie i porównuje wynik.
 *
 * `@Tag("testcontainers")`: wymaga demona Dockera, wyłączony z domyślnego `./gradlew test`
 * (patrz build.gradle.kts oraz UpdateAppointmentHandlerPersistenceTest). W środowisku
 * agentowym Docker był niedostępny, więc plik jest sprawdzony pod kątem kompilacji, ale
 * NIE uruchomiony — odpal `./gradlew test -PrunTestcontainers` na agencie z Dockerem.
 */
@Tag("testcontainers")
@Testcontainers
class EnumCheckConstraintDropperTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    private fun jdbcTemplate(): JdbcTemplate {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        dataSource.setDriverClassName("org.postgresql.Driver")
        return JdbcTemplate(dataSource)
    }

    private fun checkConstraintCount(jdbc: JdbcTemplate, table: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'c'",
            Int::class.java,
            table,
        ) ?: 0

    @Test
    fun `usuwa enumowy CHECK w formie z rzutowaniem na text array`() {
        val jdbc = jdbcTemplate()
        jdbc.execute("DROP TABLE IF EXISTS sample_enum_check")
        jdbc.execute(
            """
            CREATE TABLE sample_enum_check (
                id     uuid PRIMARY KEY,
                status varchar(10) NOT NULL
                    CONSTRAINT sample_enum_check_status_check CHECK (status IN ('SENT', 'RECEIVED', 'FAILED'))
            )
            """.trimIndent(),
        )

        // Zabezpieczenie testu: potwierdzamy, że Postgres faktycznie normalizuje listę stałych
        // do formy z podwójnym nawiasem — dokładnie tej, której stary wzorzec nie łapał.
        val def = jdbc.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint " +
                "WHERE conrelid = 'sample_enum_check'::regclass AND contype = 'c'",
            String::class.java,
        )
        assertTrue(
            def != null && def.contains("= ANY ((ARRAY["),
            "Zmieniła się normalizacja Postgresa — zaktualizuj wzorzec i ten test. Otrzymano: $def",
        )

        EnumCheckConstraintDropper(jdbc, enabled = true).dropEnumCheckConstraints()

        assertEquals(
            0,
            checkConstraintCount(jdbc, "sample_enum_check"),
            "Dropper nie usunął enumowego CHECK-a w formie z rzutowaniem na text[]",
        )
    }

    @Test
    fun `nie rusza ograniczenia biznesowego`() {
        val jdbc = jdbcTemplate()
        jdbc.execute("DROP TABLE IF EXISTS sample_business_check")
        jdbc.execute(
            """
            CREATE TABLE sample_business_check (
                id      uuid PRIMARY KEY,
                minutes integer NOT NULL
                    CONSTRAINT sample_business_minutes_check CHECK (minutes >= 0 AND minutes <= 1440)
            )
            """.trimIndent(),
        )

        EnumCheckConstraintDropper(jdbc, enabled = true).dropEnumCheckConstraints()

        assertEquals(
            1,
            checkConstraintCount(jdbc, "sample_business_check"),
            "Dropper skasował ograniczenie biznesowe (zakres liczbowy), którego nie powinien ruszać",
        )
    }
}

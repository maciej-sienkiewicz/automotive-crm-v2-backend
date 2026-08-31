package pl.detailing.crm.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.DependsOn
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Usuwa ze schematu CHECK-i wyliczające dozwolone wartości enumów.
 *
 * ## Dlaczego one w ogóle powstają
 *
 * `spring.jpa.hibernate.ddl-auto=update`. Hibernate przy PIERWSZYM tworzeniu tabeli
 * dokłada do kolumny `@Enumerated(EnumType.STRING)` ograniczenie `CHECK (kol IN (...))`
 * z listą stałych, jakie enum miał w tamtej chwili. I na tym koniec — tryb `update`
 * nigdy nie wraca do istniejącego CHECK-a. Każda nowa stała dodana w Kotlinie
 * natychmiast rozjeżdża się z bazą, a kod, który ją zapisuje, kompiluje się i przechodzi
 * testy, bo problem żyje wyłącznie w schemacie.
 *
 * ## Dlaczego to jest gorsze niż brak walidacji
 *
 * Objaw pojawia się na produkcji, przy pierwszym użyciu nowej wartości, i bywa dotkliwy
 * nieproporcjonalnie do przewinienia:
 *
 *  - `SIGNATURE_LINK_SMS` — INSERT do `communication_log` wykonuje się przy commicie,
 *    czyli PO wysłaniu SMS-a. Klient dostawał wiadomość, a żądanie podpisu znikało wraz
 *    z rollbackiem: „Link jest nieprawidłowy lub wygasł".
 *  - `COMMUNICATION`, `VISIT_CARD`, `CAMPAIGN`, `WORK_TIME` w `audit_logs.module` —
 *    audyt pisze we własnej transakcji i łyka wyjątki, więc wpisy ginęły bez śladu.
 *    Cztery moduły nie istniały w Aktywności i nikt tego nie zauważył.
 *
 * Wartość ochronna tych CHECK-ów jest przy tym bliska zeru: jedynym pisarzem tych kolumn
 * jest Hibernate, który mapuje enuma na tekst — nie ma jak wstawić wartości spoza enuma.
 * Chronią przed czymś, co i tak nie może się zdarzyć, kosztem awarii, gdy enum urośnie.
 *
 * ## Dlaczego to robi kod aplikacji, a nie tylko migracja
 *
 * Migracja naprawia bazę, która JUŻ istnieje. Nie powstrzyma Hibernate'a przed
 * wygenerowaniem CHECK-ów przy zakładaniu nowej tabeli — a każda nowa encja z enumem
 * zakłada nową tabelę i wraca do punktu wyjścia. Do tego `spring.flyway.enabled=false`:
 * migracje są w tym projekcie uruchamiane ręcznie, więc sama migracja nie daje
 * gwarancji, że ktokolwiek ją odpali.
 *
 * Ten komponent startuje po eksporcie schematu przez Hibernate i sprząta po nim.
 * Na czystej bazie jedno zapytanie do katalogu systemowego i zero operacji.
 *
 * ## Co jest, a co nie jest kasowane
 *
 * Wyłącznie CHECK-i, których treść ma postać `... = ANY (ARRAY['A', 'B', ...])` — tak
 * Postgres normalizuje `IN (lista stałych)`. Ograniczenia biznesowe (`minutes >= 0`,
 * `end_date >= start_date`) nie mają tej postaci i zostają nietknięte. Każde usunięte
 * ograniczenie ląduje w logu z nazwą, więc decyzja jest odtwarzalna.
 *
 * Wyłącznik: `crm.schema.drop-enum-check-constraints=false`.
 */
@Component
@DependsOn("entityManagerFactory")
class EnumCheckConstraintDropper(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${crm.schema.drop-enum-check-constraints:true}") private val enabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun dropEnumCheckConstraints() {
        if (!enabled) {
            logger.info("Usuwanie enumowych CHECK-ów wyłączone konfiguracją")
            return
        }

        val found = try {
            jdbcTemplate.query(FIND_ENUM_CHECKS) { rs, _ ->
                rs.getString("table_name") to rs.getString("constraint_name")
            }
        } catch (e: Exception) {
            // Sprzątanie schematu nie może zablokować startu aplikacji. Brak uprawnień do
            // ALTER TABLE na środowisku z ograniczoną rolą jest sytuacją do zalogowania,
            // a nie do wywrócenia wdrożenia.
            logger.error("Nie udało się odczytać ograniczeń CHECK ze schematu: {}", e.message, e)
            return
        }

        if (found.isEmpty()) return

        var dropped = 0
        found.forEach { (table, constraint) ->
            try {
                jdbcTemplate.execute("""ALTER TABLE $table DROP CONSTRAINT IF EXISTS "$constraint"""")
                logger.info("Usunięto enumowy CHECK {} z tabeli {}", constraint, table)
                dropped++
            } catch (e: Exception) {
                logger.error("Nie udało się usunąć ograniczenia {} z {}: {}", constraint, table, e.message)
            }
        }

        logger.info("Enumowe CHECK-i: usunięto {} z {} znalezionych", dropped, found.size)
    }

    private companion object {
        /**
         * Nazwa tabeli w postaci `regclass` jest już poprawnie cytowana przez Postgresa,
         * a nazwa ograniczenia trafia do zapytania w cudzysłowach — obie pochodzą
         * z katalogu systemowego, nie od użytkownika.
         */
        const val FIND_ENUM_CHECKS = """
            SELECT c.conrelid::regclass::text AS table_name,
                   c.conname                  AS constraint_name
            FROM pg_constraint c
            JOIN pg_namespace n ON n.oid = c.connamespace
            WHERE c.contype = 'c'
              AND n.nspname = current_schema()
              AND pg_get_constraintdef(c.oid) LIKE '%= ANY (ARRAY[%'
            ORDER BY 1, 2
        """
    }
}

package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Każda tabela z @Table musi być zakładana przez którąś migrację Flyway.
 *
 * ── Skąd ten test ────────────────────────────────────────────────────────────
 *
 * Z awarii produkcji 21 września. Nowa encja `MailFolderCursorEntity` pojechała na
 * serwer, a plik `V147__mail_folder_cursors.sql` nie — był NIEŚLEDZONY przez gita,
 * więc `git commit -a` zabrał zmienione źródła Kotlina i zostawił nowy plik SQL.
 * Flyway zameldował „schema up to date", Hibernate odmówił startu na
 * `Schema-validation: missing table [mail_folder_cursors]`, a backend wszedł w pętlę
 * restartów. Aplikacja nie wstała w ogóle — to nie była usterka jednej funkcji.
 *
 * Dlatego test porównuje źródła z KATALOGIEM MIGRACJI, a nie z bazą: CI buduje ze
 * świeżego klona, w którym nieśledzonego pliku po prostu nie ma. Lokalnie przejdzie
 * (plik leży na dysku), w CI zapali się na czerwono — i o to chodzi, bo to dokładnie
 * ta różnica, która położyła produkcję.
 *
 * ── Dlaczego jest lista wyjątków ─────────────────────────────────────────────
 *
 * Bo `V1__baseline.sql` jest CELOWO PUSTY: schemat sprzed wprowadzenia Flyway zakładano
 * ręcznie przez psql, więc tamte tabele nie mają i nigdy nie będą miały swojego
 * `CREATE TABLE` w migracjach. [PRE_FLYWAY_TABLES] to ich zamknięta lista — zamknięta,
 * bo przeszłości nie przybywa. Dopisanie do niej czegokolwiek nowego znaczy „obchodzę
 * ten test", a nie „to jest stara tabela".
 *
 * Krewny [NoEnumCheckConstraintsTest] i
 * [pl.detailing.crm.studio.reset.StudioResetCoverageTest]: skanuje źródła, nie wymaga bazy.
 */
class EntityTableHasMigrationTest {

    private val mainSources = File("src/main/kotlin")
    private val migrations = File("src/main/resources/db/migration")

    @Test
    fun `kazda tabela encji jest zakladana przez migracje`() {
        val tables = scanTableNames()
        check(tables.size > 100) {
            "Skan @Table znalazł tylko ${tables.size} tabel — parser się rozjechał ze stylem kodu"
        }

        val sql = migrations.listFiles { f -> f.extension == "sql" }
            .orEmpty()
            .joinToString("\n") { it.readText() }
            .lowercase()
        check(sql.isNotBlank()) { "Nie znaleziono żadnej migracji w ${migrations.path}" }

        val missing = tables
            .filterNot { it.lowercase() in PRE_FLYWAY_TABLES }
            // \b po obu stronach: tabela "services" nie może zaliczyć "manual_services".
            .filterNot { Regex("""\b${Regex.escape(it.lowercase())}\b""").containsMatchIn(sql) }
            .sorted()

        assertTrue(
            missing.isEmpty(),
            """
            Tabele encji, których nie zakłada żadna migracja: ${missing.joinToString()}

            Najczęstsza przyczyna: plik migracji istnieje na dysku, ale jest nieśledzony
            przez gita (git status pokazuje go jako "??"), więc nie wejdzie do builda.
            Dopisz go: git add src/main/resources/db/migration/
            """.trimIndent()
        )
    }

    private fun scanTableNames(): Set<String> =
        mainSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> TABLE_NAME_REGEX.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

    private companion object {
        /** `@Table(name = "foo"` oraz `@Table(\n    name = "foo"` — oba style w repo. */
        val TABLE_NAME_REGEX = Regex("""@Table\s*\(\s*name\s*=\s*"([^"]+)"""")

        /**
         * Tabele sprzed Flyway, zakładane ręcznie przez psql (patrz `V1__baseline.sql`).
         * Lista jest ZAMKNIĘTA — nowa tabela nigdy nie należy tutaj, tylko do migracji.
         */
        val PRE_FLYWAY_TABLES = setOf(
            "appointment_recurrence_series", "call_logs", "cash_registers",
            "category_service_assignments", "cost_item_assignments", "customer_documents",
            "customer_notes", "demo_accounts", "employee_leaves",
            "manual_service_category_assignments", "manual_services", "pending_plan_changes",
            "photo_tags", "protocol_rules", "scheduled_sms_reminders", "service_categories",
            "service_package_items", "signature_audit_events", "sms_consent_requests",
            "sms_credit_balances", "sms_credit_packages", "sms_credit_transactions",
            "sms_send_log", "studio_subscription_add_ons", "subscription_add_ons",
            "subscription_features", "supplier_auto_rules", "temporary_photos",
            "upsell_reservation_consents", "vehicle_documents", "vehicle_notes",
            "vehicle_owners", "vehicles", "visit_card_tokens", "visit_comment_revisions",
            "visit_upsell_suggestions"
        )
    }
}

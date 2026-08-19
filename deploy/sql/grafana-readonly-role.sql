-- ═══════════════════════════════════════════════════════════════════════════════
-- Rola read-only dla Grafany — KROK OPERACYJNY, NIE MIGRACJA
--
-- Uruchamiane RĘCZNIE, raz, przez kogoś z uprawnieniami administratora bazy:
--
--     psql "$DB_URL_ADMIN" -f deploy/sql/grafana-readonly-role.sql
--     psql "$DB_URL_ADMIN" -c "ALTER ROLE grafana_ro WITH PASSWORD '<wygenerowane>';"
--
-- ## Dlaczego to nie jest migracja Flyway
--
-- W profilu produkcyjnym (docker-props) Flyway jest włączony i uruchamia migracje przy
-- starcie aplikacji. CREATE ROLE wymaga CREATEROLE albo superusera, a użytkownik
-- aplikacji zwykle ich nie ma. Migracja, która padnie na braku uprawnień, zatrzymuje
-- Flywaya, a Flyway zatrzymuje start CRM-a — całe wdrożenie systemu zablokowane przez
-- wygodę jednego dashboardu. Uprawnienia to praca administratora bazy i tak mają zostać
-- opisane.
--
-- ## Kolejność
--
-- NAJPIERW wdrożenie aplikacji (Flyway tworzy tabele metric_* i widoki z V66),
-- POTEM ten skrypt. Odwrotnie GRANT-y padną na nieistniejących relacjach.
--
-- ## Zasada: Grafana dostaje metryki, nigdy klientów
--
-- Granty są wypisane tabela po tabeli celowo. Zbiorcze GRANT SELECT ON ALL TABLES
-- oddałoby narzędziu do dashboardów — dostępnemu z przeglądarki, z włączonym anonimowym
-- podglądem w tym wdrożeniu — każde nazwisko klienta, numer telefonu, fakturę i podpisany
-- protokół na platformie.
--
-- Rola powstaje BEZ HASŁA, więc nie da się nią połączyć, dopóki ktoś go nie ustawi.
-- Hasło zacommitowane do repozytorium to hasło, które wyciekło.
--
-- ## Dodanie nowej tabeli metryk
--
-- NIE jest objęte automatycznie. To celowe: ALTER DEFAULT PRIVILEGES objęłoby też każdą
-- przyszłą tabelę biznesową tworzoną przez użytkownika aplikacji, czyli dokładnie ten
-- zbiorczy grant, którego ten plik unika. Dopisz jawny GRANT poniżej — jednolinijkowy
-- koszt przeglądu jest tu sensem, nie niedogodnością.
-- ═══════════════════════════════════════════════════════════════════════════════

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'grafana_ro') THEN
        CREATE ROLE grafana_ro WITH LOGIN;
        RAISE NOTICE 'Utworzono rolę grafana_ro (bez hasła — ustaw je przez ALTER ROLE).';
    ELSE
        RAISE NOTICE 'Rola grafana_ro już istnieje — aktualizuję wyłącznie uprawnienia.';
    END IF;
END
$$;

-- Połączenie i widoczność schematu. Bez CREATE, bez TEMP.
-- GRANT ... ON DATABASE wymaga literalnego identyfikatora, więc nazwa bazy idzie przez
-- format(%I), a nie jako CURRENT_CATALOG, którego Postgres w tym miejscu nie przyjmuje.
DO $$
BEGIN
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO grafana_ro', current_database());
END
$$;

GRANT USAGE ON SCHEMA public TO grafana_ro;
REVOKE CREATE ON SCHEMA public FROM grafana_ro;

-- ── Widoki raportowe (tworzone przez V66) ─────────────────────────────────────
GRANT SELECT ON metric_studio_directory TO grafana_ro;
GRANT SELECT ON metric_endpoint_usage   TO grafana_ro;

-- ── Tabele metryk ─────────────────────────────────────────────────────────────
GRANT SELECT ON metric_daily_studio_snapshots   TO grafana_ro;
GRANT SELECT ON metric_daily_platform_snapshots TO grafana_ro;
GRANT SELECT ON metric_user_sessions            TO grafana_ro;
GRANT SELECT ON metric_events                   TO grafana_ro;
GRANT SELECT ON metric_api_endpoints            TO grafana_ro;
GRANT SELECT ON metric_api_endpoint_daily       TO grafana_ro;
GRANT SELECT ON metric_studio_api_daily         TO grafana_ro;
GRANT SELECT ON metric_error_groups             TO grafana_ro;
GRANT SELECT ON metric_error_group_impacts      TO grafana_ro;

-- Świadomie NIE nadane: metric_error_events.
-- Pojedyncze wystąpienia niosą ścieżki żądań, identyfikatory użytkowników i stack trace'y,
-- a komunikat wyjątku potrafi zacytować dane klienta. Tabele zgrupowane powyżej odpowiadają
-- na każde pytanie dashboardu („jaki defekt, jak często, które studia”); stack trace zostaje
-- za uwierzytelnionym /api/internal/metrics/errors, gdzie jest potrzebny do debugowania.

-- ── Kontrola ──────────────────────────────────────────────────────────────────
-- Powinno zwrócić 11 relacji i ani jednej tabeli biznesowej.
SELECT table_name, privilege_type
FROM information_schema.table_privileges
WHERE grantee = 'grafana_ro'
ORDER BY table_name;

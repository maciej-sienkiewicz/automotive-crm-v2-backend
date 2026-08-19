-- ═══════════════════════════════════════════════════════════════════════════════
-- Widoki raportowe dla dashboardów metryk.
--
-- Ten plik zawiera WYŁĄCZNIE obiekty, które potrafi utworzyć zwykły użytkownik
-- aplikacji. Rola `grafana_ro` wraz z uprawnieniami celowo tu NIE jest — CREATE ROLE
-- wymaga uprawnienia CREATEROLE albo superusera, a w profilu produkcyjnym
-- (docker-props) Flyway jest włączony i uruchamia migracje przy starcie aplikacji.
-- Migracja, która padnie na braku uprawnień, zatrzymuje Flywaya, a Flyway zatrzymuje
-- start CRM-a: całe wdrożenie systemu zablokowane przez wygodę jednego dashboardu.
--
-- Rola i granty żyją w deploy/sql/grafana-readonly-role.sql i są krokiem operacyjnym,
-- wykonywanym raz, przez kogoś z uprawnieniami administratora bazy.
-- ═══════════════════════════════════════════════════════════════════════════════

-- ── Katalog studiów ───────────────────────────────────────────────────────────
-- Wykres potrzebuje nazwy studia; nie potrzebuje jego statusu rozliczeniowego, aliasu
-- e-mail ani dat triala. Ten widok to CAŁOŚĆ tego, co Grafana może wiedzieć o tenancie.

CREATE OR REPLACE VIEW metric_studio_directory AS
SELECT s.id, s.name
FROM studios s;

-- ── Wykorzystanie endpointów ──────────────────────────────────────────────────
-- Same fakty: dni ciszy i świeży wolumen. Klasyfikacja DEAD / DORMANT / NEVER_CALLED
-- NIE jest tu powtórzona — zależy od zabezpieczenia okna obserwacji, które nie pozwala
-- nazwać martwym endpointu raportu kwartalnego po trzech dniach danych, a druga kopia
-- tej reguły w SQL rozjechałaby się z tą w GetDeadEndpointsHandler. Grafana sortuje po
-- ciszy; wiążącą odpowiedź, co wolno usunąć, daje API.

CREATE OR REPLACE VIEW metric_endpoint_usage AS
SELECT
    e.id,
    e.http_method,
    e.path_template,
    e.controller,
    e.module,
    e.requires_auth,
    e.is_retention_exempt,
    e.first_seen_at,
    e.last_called_at,
    e.total_calls,
    CASE WHEN e.last_called_at IS NULL THEN NULL
         ELSE EXTRACT(DAY FROM (now() - e.last_called_at))::int
    END AS days_silent,
    COALESCE((SELECT SUM(d.call_count) FROM metric_api_endpoint_daily d
              WHERE d.endpoint_id = e.id
                AND d.usage_date >= CURRENT_DATE - 30), 0) AS calls_30d,
    COALESCE((SELECT MAX(d.distinct_studios) FROM metric_api_endpoint_daily d
              WHERE d.endpoint_id = e.id
                AND d.usage_date >= CURRENT_DATE - 30), 0) AS studios_30d
FROM metric_api_endpoints e
WHERE e.is_active_in_code = true;

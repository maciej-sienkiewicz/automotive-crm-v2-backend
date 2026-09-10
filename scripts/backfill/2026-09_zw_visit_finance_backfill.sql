-- ============================================================================
-- Backfill: pozycje ZW z odwróconą ceną + brakujące dokumenty finansowe wizyt
-- ============================================================================
--
-- KONTEKST
--   Błąd w PriceCalculator (stawka ZW, wartownik rate = -1) liczył netto z brutta
--   wzorem "w stu" brutto*100/(100+rate) = brutto*100/99, więc dla zwolnienia
--   NETTO wychodziło WYŻSZE niż BRUTTO (np. 250,00 -> netto 252,53). Przy
--   zakończeniu wizyty Visit.calculateTotalVat() = brutto - netto = -253 gr
--   wysadzało niezmiennik Money(>=0). Ponieważ CompleteVisitHandler wykonuje
--   pracę w withContext(Dispatchers.IO) (poza transakcją @Transactional),
--   wizyta zapisywała się jako COMPLETED, a dokument finansowy nigdy nie
--   powstawał. Skutek: wizyta zakończona, zero rekordów w Finansach.
--
--   Poprawka w kodzie (VatRate.netCentsFromGrossCents) usuwa źródło problemu.
--   Ten skrypt naprawia dane, które już się rozjechały:
--     SEKCJA 1 — prostuje ceny pozycji ZW (netto = brutto),
--     SEKCJA 2 — odtwarza brakujące paragony (RECEIPT) dla wizyt COMPLETED,
--                dokładnie tak, jak zrobiłby to CreateFinancialDocumentHandler,
--     SEKCJA 3 — (OPCJONALNIE) dokłada wpis do kasy dla wizyt gotówkowych.
--
-- ZAŁOŻENIE, KTÓREGO NIE DA SIĘ ODTWORZYĆ Z DANYCH
--   Metoda płatności wybrana przy "Wydaj pojazd" NIE jest nigdzie zapisywana na
--   wizycie. Domyślną metodą zakończenia jest GOTÓWKA (PaymentMethod.CASH ->
--   status PAID + wpis do kasy), więc skrypt zakłada CASH/PAID. Jeśli studio
--   brało kartą/przelewem, po backfillu należy skorygować payment_method (i, gdy
--   uruchomiono SEKCJĘ 3, saldo kasy). Wszystko inne (kwoty, numer, daty,
--   nabywca, opis) jest odtwarzane wiernie.
--
-- BEZPIECZEŃSTWO
--   * Całość w jednej transakcji — na końcu jest ROLLBACK. Po weryfikacji
--     podglądów zamień ostatni ROLLBACK na COMMIT.
--   * Idempotentne: SEKCJA 2 pomija wizyty, które już mają dokument
--     (NOT EXISTS ... deleted_at IS NULL); SEKCJA 1 rusza tylko rozjechane ZW.
--   * Zakres: domyślnie jedno studio (:studio_id). Najpierw uruchom PODGLĄD
--     globalny (SEKCJA 0), żeby zobaczyć pełną skalę, potem rób per studio.
--
-- URUCHOMIENIE
--   Baza działa w kontenerze (detailing_database_prod), a plik leży na hoście,
--   więc podajemy go przez stdin (-f -). UWAGA: przy przekierowaniu wejścia
--   używamy `docker exec -i` (BEZ -t; TTY kłóci się z pipe'em).
--
--     docker exec -i detailing_database_prod \
--       psql -U detailinguserihja54ba34 -d detailing_database \
--       -v studio_id="'48cf5ba1-e849-46fb-8baf-ae4c463d9de0'" \
--       -f - < scripts/backfill/2026-09_zw_visit_finance_backfill.sql
--
--   Alternatywnie: skopiuj plik do kontenera i uruchom z konkretnej ścieżki:
--     docker cp scripts/backfill/2026-09_zw_visit_finance_backfill.sql \
--       detailing_database_prod:/tmp/backfill.sql
--     docker exec -i detailing_database_prod \
--       psql -U detailinguserihja54ba34 -d detailing_database \
--       -v studio_id="'48cf5ba1-e849-46fb-8baf-ae4c463d9de0'" \
--       -f /tmp/backfill.sql
--
--   Bezpośrednio z hosta (gdy masz klienta psql i dostęp sieciowy do bazy):
--     psql -h <DB_ADDR> -p <DB_PORT> -U <DB_USER> -d detailing_database \
--       -v studio_id="'48cf5ba1-e849-46fb-8baf-ae4c463d9de0'" \
--       -f scripts/backfill/2026-09_zw_visit_finance_backfill.sql
-- ============================================================================

\set ON_ERROR_STOP on

-- ── SEKCJA 0 — PODGLĄD (bez zmian; uruchom najpierw, także globalnie) ─────────

-- 0a. Skala rozjechanych pozycji ZW w całej bazie (bez filtra studia):
SELECT count(*) AS bad_zw_items,
       count(DISTINCT visit_id) AS affected_visits
FROM visit_service_items
WHERE vat_rate = -1
  AND final_price_net <> final_price_gross;

-- 0b. Zakończone wizyty bez dokumentu, które POWINNY go mieć (globalnie).
--     Mirror strażników z issueFinancialDocument: studio z modułem FINANCE,
--     są pozycje rozliczane, suma brutto > 0, brak żywego dokumentu.
SELECT v.studio_id, count(*) AS visits_missing_document
FROM visits v
JOIN LATERAL (
    SELECT COALESCE(SUM(vsi.final_price_gross), 0) AS gross
    FROM visit_service_items vsi
    WHERE vsi.visit_id = v.id AND vsi.status IN ('CONFIRMED', 'APPROVED')
) s ON true
WHERE v.status = 'COMPLETED'
  AND v.deleted_at IS NULL
  AND s.gross > 0
  AND NOT EXISTS (
      SELECT 1 FROM financial_documents fd
      WHERE fd.visit_id = v.id AND fd.deleted_at IS NULL
  )
  AND EXISTS (
      SELECT 1
      FROM studio_subscription_plans ssp
      LEFT JOIN subscription_plan_features pf ON pf.plan_id = ssp.plan_id
      LEFT JOIN subscription_features f1 ON f1.id = pf.feature_id AND f1.feature_key = 'FINANCE'
      LEFT JOIN studio_subscription_add_ons sao ON sao.studio_subscription_plan_id = ssp.id
      LEFT JOIN subscription_add_on_features aof ON aof.add_on_id = sao.add_on_id
      LEFT JOIN subscription_features f2 ON f2.id = aof.feature_id AND f2.feature_key = 'FINANCE'
      WHERE ssp.studio_id = v.studio_id
        AND (f1.feature_key IS NOT NULL OR f2.feature_key IS NOT NULL)
  )
GROUP BY v.studio_id
ORDER BY visits_missing_document DESC;

-- ── Transakcja właściwa (kończy się ROLLBACK — zamień na COMMIT po podglądzie) ─
BEGIN;

-- ── SEKCJA 1 — prostowanie cen pozycji ZW (netto = brutto) ───────────────────
-- Dla zwolnienia z VAT nie ma podatku, więc netto musi równać się brutto.
-- Rozjazd bierze się wyłącznie z błędnego netta liczonego od brutta; brutto
-- (wartość ustawiona przez użytkownika) jest prawidłowe i zostaje źródłem prawdy.
WITH fixed AS (
    UPDATE visit_service_items vsi
    SET final_price_net = final_price_gross
    FROM visits v
    WHERE v.id = vsi.visit_id
      AND v.studio_id = :studio_id
      AND vsi.vat_rate = -1
      AND vsi.final_price_net <> vsi.final_price_gross
    RETURNING vsi.id
)
SELECT count(*) AS zw_items_fixed FROM fixed;

-- ── SEKCJA 2 — odtworzenie brakujących paragonów (RECEIPT) ───────────────────
-- Kwoty liczone z pozycji PO korekcie z SEKCJI 1. Numeracja: PAR/<rok>/<NNNN>,
-- kontynuacja licznika per studio+rok+typ (count istniejących + kolejny numer),
-- dokładnie jak generateDocumentNumber. Daty i autor z chwili zakończenia wizyty.
WITH candidates AS (
    SELECT
        v.id                    AS visit_id,
        v.studio_id             AS studio_id,
        v.visit_number          AS visit_number,
        v.brand_snapshot        AS brand,
        v.model_snapshot        AS model,
        v.license_plate_snapshot AS plate,
        v.pickup_date           AS completed_at,
        v.updated_by            AS actor,
        c.first_name            AS first_name,
        c.last_name             AS last_name,
        c.company_name          AS company_name,
        c.company_nip           AS company_nip,
        s.net                   AS net,
        s.gross                 AS gross,
        extract(year FROM v.pickup_date)::int AS yr
    FROM visits v
    JOIN customers c ON c.id = v.customer_id
    JOIN LATERAL (
        SELECT COALESCE(SUM(vsi.final_price_net), 0)   AS net,
               COALESCE(SUM(vsi.final_price_gross), 0) AS gross
        FROM visit_service_items vsi
        WHERE vsi.visit_id = v.id AND vsi.status IN ('CONFIRMED', 'APPROVED')
    ) s ON true
    WHERE v.status = 'COMPLETED'
      AND v.deleted_at IS NULL
      AND v.studio_id = :studio_id
      AND s.gross > 0
      AND NOT EXISTS (
          SELECT 1 FROM financial_documents fd
          WHERE fd.visit_id = v.id AND fd.deleted_at IS NULL
      )
      AND EXISTS (
          SELECT 1
          FROM studio_subscription_plans ssp
          LEFT JOIN subscription_plan_features pf ON pf.plan_id = ssp.plan_id
          LEFT JOIN subscription_features f1 ON f1.id = pf.feature_id AND f1.feature_key = 'FINANCE'
          LEFT JOIN studio_subscription_add_ons sao ON sao.studio_subscription_plan_id = ssp.id
          LEFT JOIN subscription_add_on_features aof ON aof.add_on_id = sao.add_on_id
          LEFT JOIN subscription_features f2 ON f2.id = aof.feature_id AND f2.feature_key = 'FINANCE'
          WHERE ssp.studio_id = v.studio_id
            AND (f1.feature_key IS NOT NULL OR f2.feature_key IS NOT NULL)
      )
),
numbered AS (
    SELECT c.*,
        (
            SELECT count(*) FROM financial_documents fd
            WHERE fd.studio_id = c.studio_id
              AND fd.document_type = 'RECEIPT'
              AND fd.issue_date >= make_date(c.yr, 1, 1)
              AND fd.issue_date <  make_date(c.yr + 1, 1, 1)
        )
        + row_number() OVER (PARTITION BY c.studio_id, c.yr ORDER BY c.completed_at, c.visit_id)
        AS seq
    FROM candidates c
),
inserted AS (
    INSERT INTO financial_documents (
        id, studio_id, source, visit_id, vehicle_brand, vehicle_model,
        customer_first_name, customer_last_name, document_number, document_type,
        direction, status, payment_method, total_net, total_vat, total_gross,
        currency, issue_date, due_date, paid_at, description,
        counterparty_name, counterparty_nip, created_by, updated_by,
        created_at, updated_at, deleted_at, ksef_revenue_invoice_id
    )
    SELECT
        gen_random_uuid(), n.studio_id, 'VISIT', n.visit_id, n.brand, n.model,
        n.first_name, n.last_name,
        'PAR/' || n.yr || '/' || lpad(n.seq::text, 4, '0'),
        'RECEIPT', 'INCOME',
        'PAID',            -- ZAŁOŻENIE: CASH -> PAID (patrz nagłówek)
        'CASH',            -- ZAŁOŻENIE: metoda płatności nieznana, domyślnie gotówka
        n.net, n.gross - n.net, n.gross,
        'PLN',
        n.completed_at::date, n.completed_at::date, n.completed_at,
        'Wizyta #' || n.visit_number || ' – ' ||
            trim(both ' ' FROM concat_ws(' ',
                n.brand, n.model,
                CASE WHEN n.plate IS NOT NULL AND n.plate <> '' THEN '(' || n.plate || ')' END)),
        COALESCE(NULLIF(n.company_name, ''),
                 NULLIF(trim(both ' ' FROM concat_ws(' ', n.first_name, n.last_name)), '')),
        n.company_nip,
        n.actor, n.actor,
        n.completed_at, n.completed_at, NULL, NULL
    FROM numbered n
    RETURNING id, visit_id, document_number, total_gross
)
SELECT count(*) AS receipts_created,
       COALESCE(SUM(total_gross), 0) AS total_gross_backfilled
FROM inserted;

-- ── SEKCJA 3 (OPCJONALNIE) — wpis do kasy dla ZAŁOŻONEJ gotówki ──────────────
-- WYŁĄCZONA domyślnie. Kasa (cash_registers.balance) to łańcuch sald
-- balance_before/after; wstawianie operacji "w przeszłość" rozjeżdża późniejsze
-- salda. Ten blok dokłada operacje na KONIEC historii, czytając bieżące saldo
-- (spójny łańcuch), z komentarzem jak z aplikacji. Odkomentuj świadomie i tylko
-- dla studiów, które faktycznie rozliczają wizyty gotówką.
--
-- DO $$
-- DECLARE
--     reg_id uuid;
--     bal    bigint;
--     r      record;
-- BEGIN
--     SELECT id, balance INTO reg_id, bal FROM cash_registers
--       WHERE studio_id = :studio_id FOR UPDATE;
--     IF reg_id IS NULL THEN
--         reg_id := gen_random_uuid();
--         bal := 0;
--         INSERT INTO cash_registers (id, studio_id, balance, currency, updated_at)
--         VALUES (reg_id, :studio_id, 0, 'PLN', now());
--     END IF;
--
--     FOR r IN
--         SELECT fd.id AS doc_id, fd.total_gross, fd.description, fd.created_by, fd.issue_date
--         FROM financial_documents fd
--         WHERE fd.studio_id = :studio_id
--           AND fd.source = 'VISIT'
--           AND fd.document_type = 'RECEIPT'
--           AND fd.payment_method = 'CASH'
--           AND fd.status = 'PAID'
--           AND fd.deleted_at IS NULL
--           AND NOT EXISTS (
--               SELECT 1 FROM cash_operations co WHERE co.financial_document_id = fd.id
--           )
--         ORDER BY fd.issue_date, fd.id
--     LOOP
--         INSERT INTO cash_operations (
--             id, studio_id, cash_register_id, amount, balance_before, balance_after,
--             operation_type, comment, financial_document_id, created_by, created_at
--         )
--         VALUES (
--             gen_random_uuid(), :studio_id, reg_id, r.total_gross, bal, bal + r.total_gross,
--             'PAYMENT_IN', r.description, r.doc_id, r.created_by, now()
--         );
--         bal := bal + r.total_gross;
--     END LOOP;
--
--     UPDATE cash_registers SET balance = bal, updated_at = now() WHERE id = reg_id;
-- END $$;

-- ── Weryfikacja po backfillu (nadal w transakcji) ────────────────────────────
SELECT v.visit_number, fd.document_number, fd.status, fd.payment_method,
       fd.total_net, fd.total_vat, fd.total_gross, fd.issue_date
FROM visits v
JOIN financial_documents fd ON fd.visit_id = v.id AND fd.deleted_at IS NULL
WHERE v.studio_id = :studio_id
  AND v.status = 'COMPLETED'
ORDER BY fd.issue_date, fd.document_number;

-- Po sprawdzeniu podglądów: zamień poniższe na COMMIT.
ROLLBACK;

-- Spójność rozliczeń subskrypcji: wersje, karencja, stany zamówień, inbox notyfikacji P24
-- i ograniczenia, których schemat sprzed Flyway nigdy nie dostał.
--
-- ## Skąd ta migracja
--
-- Audyt `docs/SUBSCRIPTION_AUDIT_2026-10.md`. Tabele rozliczeniowe zakładał `ddl-auto`
-- (przed Flyway), a produkcja z `ddl-auto=validate` sprawdza tylko kolumny, nie ograniczenia.
-- Z mapowania encji wynikało, że NIE ma: kluczy obcych do `studios`, unikatu „jeden PENDING
-- downgrade na studio" (choć KDoc encji twierdził, że jest), unikatu na płatności P24 ani na
-- wpisach w historii płatności. Każdy z tych braków przepuszczał po cichu podwójną realizację
-- albo osierocone wiersze. Ta migracja je DODAJE — zgodnie z decyzją, że constraintów w module
-- rozliczeń nie zdejmujemy, tylko naprawiamy kod, który na nie wpadał.
--
-- ## Dlaczego tyle IF NOT EXISTS i bloków DO
--
-- Ta sama migracja musi przejść na produkcji (tabele z dawnego ddl-auto, ograniczenia nieznane)
-- i na bazie, którą właśnie założył Hibernate (testy, środowiska lokalne z `ddl-auto=update`).
-- Nieudana migracja to restart aplikacji w pętli, więc każdy krok, który mógłby trafić na dane
-- łamiące nowe ograniczenie, najpierw te dane porządkuje albo — gdy porządkowanie nie jest
-- bezpieczne dla pieniędzy — zakłada ograniczenie jako NOT VALID (pilnuje nowych wierszy).
--
-- Bez `CREATE INDEX CONCURRENTLY`: Flyway wykonuje migrację w transakcji, a tabele są małe.

-- ── 1. Nowe kolumny ────────────────────────────────────────────────────────────

-- Optymistyczne blokady (@Version). DEFAULT 0: istniejące wiersze i natywne INSERT-y
-- (StudioSubscriptionBackfill) dostają wersję bez wiedzy o niej.
ALTER TABLE studio_subscription_plans ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE pending_plan_changes      ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE payment_orders            ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Koniec karencji (PAST_DUE). Wcześniej PAST_DUE dawał dostęp bez żadnej daty.
ALTER TABLE studios ADD COLUMN IF NOT EXISTS grace_ends_at TIMESTAMPTZ;

-- Moduł wyłączany z końcem opłaconego okresu zamiast natychmiast.
ALTER TABLE studio_subscription_add_ons ADD COLUMN IF NOT EXISTS cancel_at TIMESTAMPTZ;

-- „Zapłacone" i „zrealizowane" to osobne fakty; data realizacji i ostatniego sprawdzenia w P24.
ALTER TABLE payment_orders ADD COLUMN IF NOT EXISTS fulfilled_at TIMESTAMPTZ;
ALTER TABLE payment_orders ADD COLUMN IF NOT EXISTS last_reconciled_at TIMESTAMPTZ;

-- Wpis w historii płatności wie, którego zamówienia dotyczy.
ALTER TABLE subscription_payment_log ADD COLUMN IF NOT EXISTS order_id UUID;

-- ── 2. Dane: nowe znaczenie stanów zamówień ───────────────────────────────────

-- PAID znaczyło dotąd „zapłacone I zrealizowane" (błąd realizacji cofał PAID do PENDING).
-- Od teraz PAID = „zapłacone, czeka na realizację", a zrealizowane to FULFILLED.
UPDATE payment_orders
SET status = 'FULFILLED', fulfilled_at = COALESCE(fulfilled_at, paid_at)
WHERE status = 'PAID';

-- ── 3. Jeden PENDING downgrade na studio ──────────────────────────────────────

-- Duplikaty mogły powstać przez wyścig dwóch żądań zmiany planu. Zostaje najnowszy —
-- ten sam wynik, który dawało `cancelPendingForStudio` + zapis nowego wiersza.
UPDATE pending_plan_changes p
SET status = 'CANCELLED'
WHERE p.status = 'PENDING'
  AND EXISTS (
      SELECT 1 FROM pending_plan_changes newer
      WHERE newer.studio_id = p.studio_id
        AND newer.status = 'PENDING'
        AND (newer.requested_at > p.requested_at
             OR (newer.requested_at = p.requested_at AND newer.id > p.id))
  );

CREATE UNIQUE INDEX IF NOT EXISTS uq_pending_plan_changes_one_pending
    ON pending_plan_changes (studio_id) WHERE status = 'PENDING';

-- ── 4. Jedno otwarte zamówienie na produkt ────────────────────────────────────

-- Nic dotąd nie zamykało porzuconych koszyków, więc starsze PENDING na ten sam produkt
-- przechodzą w EXPIRED. To nie zamyka drogi pieniądzom: EXPIRED przyjmuje spóźnioną płatność,
-- a puste `last_reconciled_at` każe workerowi raz sprawdzić każde takie zamówienie w P24.
UPDATE payment_orders o
SET status = 'EXPIRED',
    failure_reason = 'V172: zastąpione nowszym zamówieniem na ten sam produkt'
WHERE o.status = 'PENDING'
  AND EXISTS (
      SELECT 1 FROM payment_orders newer
      WHERE newer.studio_id = o.studio_id
        AND newer.order_type = o.order_type
        AND COALESCE(newer.plan_key, '') = COALESCE(o.plan_key, '')
        AND newer.add_on_keys = o.add_on_keys
        AND newer.status = 'PENDING'
        AND (newer.created_at > o.created_at
             OR (newer.created_at = o.created_at AND newer.id > o.id))
  );

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_orders_one_open_per_product
    ON payment_orders (studio_id, order_type, COALESCE(plan_key, ''), add_on_keys)
    WHERE status = 'PENDING';

-- ── 5. Jedna płatność P24 = jedno zamówienie ──────────────────────────────────

-- Duplikat p24_order_id to dane pieniężne do ręcznego wyjaśnienia, nie do automatycznej
-- poprawki — wtedy indeks nie powstaje, a migracja nie blokuje startu (zapytanie Q4 z audytu
-- pokaże, które to wiersze).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM payment_orders
        WHERE p24_order_id IS NOT NULL
        GROUP BY p24_order_id HAVING count(*) > 1
    ) THEN
        RAISE WARNING 'V172: zduplikowane payment_orders.p24_order_id — indeks uq_payment_orders_p24_order_id pominięty, wyjaśnij ręcznie';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_orders_p24_order_id
            ON payment_orders (p24_order_id) WHERE p24_order_id IS NOT NULL;
    END IF;
END $$;

-- ── 6. Historia płatności: jeden efekt danego rodzaju na zamówienie ───────────

-- Historia sprzed V172 ma order_id = NULL i indeks jej nie dotyczy.
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_log_order_event
    ON subscription_payment_log (order_id, event_type) WHERE order_id IS NOT NULL;

-- ── 7. Ograniczenia biznesowe (porównania, nie enumowe listy IN) ──────────────

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_payment_orders_amount_non_negative') THEN
        ALTER TABLE payment_orders ADD CONSTRAINT chk_payment_orders_amount_non_negative
            CHECK (amount_cents >= 0) NOT VALID;
    END IF;

    -- Zamówienie, za które są pieniądze, musi wiedzieć, kiedy przyszły.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_payment_orders_paid_has_paid_at') THEN
        ALTER TABLE payment_orders ADD CONSTRAINT chk_payment_orders_paid_has_paid_at
            CHECK (paid_at IS NOT NULL OR (status <> 'PAID' AND status <> 'FULFILLED' AND status <> 'REFUND_REQUIRED')) NOT VALID;
    END IF;
END $$;

-- ── 8. Klucze obce do studios ─────────────────────────────────────────────────

-- Sieroty po usuniętych studiach (DemoCleanupJob kasował `studios`, a wierszy subskrypcji
-- nie). Plan, moduły i oczekujące zmiany planu nieistniejącego studia nie znaczą nic —
-- kasujemy je dokładnie tak, jak zrobiłby to ON DELETE CASCADE, gdyby klucz istniał.
DELETE FROM studio_subscription_add_ons a
WHERE NOT EXISTS (
    SELECT 1 FROM studio_subscription_plans p
    JOIN studios s ON s.id = p.studio_id
    WHERE p.id = a.studio_subscription_plan_id
);
DELETE FROM studio_subscription_plans p
WHERE NOT EXISTS (SELECT 1 FROM studios s WHERE s.id = p.studio_id);
DELETE FROM pending_plan_changes p
WHERE NOT EXISTS (SELECT 1 FROM studios s WHERE s.id = p.studio_id);

DO $$
DECLARE
    fk RECORD;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_studio_subscription_plans_studio') THEN
        ALTER TABLE studio_subscription_plans ADD CONSTRAINT fk_studio_subscription_plans_studio
            FOREIGN KEY (studio_id) REFERENCES studios (id) ON DELETE CASCADE NOT VALID;
        ALTER TABLE studio_subscription_plans VALIDATE CONSTRAINT fk_studio_subscription_plans_studio;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_pending_plan_changes_studio') THEN
        ALTER TABLE pending_plan_changes ADD CONSTRAINT fk_pending_plan_changes_studio
            FOREIGN KEY (studio_id) REFERENCES studios (id) ON DELETE CASCADE NOT VALID;
        ALTER TABLE pending_plan_changes VALIDATE CONSTRAINT fk_pending_plan_changes_studio;
    END IF;

    -- Moduły → plan: klucz wygenerowany przez Hibernate (nazwa FK…) nie ma kaskady, więc
    -- kaskada ze `studios` zatrzymałaby się na modułach. ZASTĘPUJEMY go silniejszym w tej samej
    -- transakcji — to wymiana, nie usunięcie ograniczenia.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_studio_add_ons_plan') THEN
        FOR fk IN
            SELECT c.conname
            FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
            WHERE c.contype = 'f'
              AND c.conrelid = 'studio_subscription_add_ons'::regclass
              AND c.confrelid = 'studio_subscription_plans'::regclass
              AND a.attname = 'studio_subscription_plan_id'
        LOOP
            EXECUTE format('ALTER TABLE studio_subscription_add_ons DROP CONSTRAINT %I', fk.conname);
        END LOOP;
        ALTER TABLE studio_subscription_add_ons ADD CONSTRAINT fk_studio_add_ons_plan
            FOREIGN KEY (studio_subscription_plan_id) REFERENCES studio_subscription_plans (id) ON DELETE CASCADE;
    END IF;

    -- Pieniądze nie znikają kaskadą: usunięcie studia z zamówieniami ma się wywrócić.
    -- NOT VALID bez VALIDATE: historyczne zamówienia usuniętych kont demo zostają jako
    -- zapis pieniędzy; klucz pilnuje każdego nowego i zmienianego wiersza.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_payment_orders_studio') THEN
        ALTER TABLE payment_orders ADD CONSTRAINT fk_payment_orders_studio
            FOREIGN KEY (studio_id) REFERENCES studios (id) ON DELETE RESTRICT NOT VALID;
    END IF;
END $$;

-- ── 9. Inbox notyfikacji Przelewy24 ───────────────────────────────────────────

-- Każda notyfikacja z poprawnym podpisem jest zapisywana, zanim cokolwiek się z nią stanie.
-- Unikat (provider, provider_order_id) to klucz idempotencji: duplikat notyfikacji (albo
-- notyfikacja i rekoncyliacja tej samej płatności) trafia w istniejący wiersz.
CREATE TABLE IF NOT EXISTS payment_notifications (
    id                UUID PRIMARY KEY,
    provider          VARCHAR(20)  NOT NULL,
    provider_order_id BIGINT       NOT NULL,
    session_id        VARCHAR(100) NOT NULL,
    amount_cents      BIGINT       NOT NULL,
    currency          VARCHAR(3)   NOT NULL,
    payload           JSONB        NOT NULL,
    source            VARCHAR(20)  NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    already_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ,
    last_error        TEXT,
    order_id          UUID,
    studio_id         UUID,
    received_at       TIMESTAMPTZ  NOT NULL,
    processed_at      TIMESTAMPTZ,
    CONSTRAINT uq_payment_notifications_provider_order UNIQUE (provider, provider_order_id)
);

CREATE INDEX IF NOT EXISTS idx_payment_notifications_retry
    ON payment_notifications (next_attempt_at) WHERE status IN ('RECEIVED', 'UNMATCHED');
CREATE INDEX IF NOT EXISTS idx_payment_notifications_session
    ON payment_notifications (session_id);

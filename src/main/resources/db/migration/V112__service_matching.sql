-- Dopasowanie usług do „Podobnych zleceń" bez bazy wektorowej.
--
-- Decyzja projektowa (po analizie wariantów): zamiast liczyć bliskość tekstów
-- w czasie zapytania, klasyfikujemy KAŻDĄ nazwę usługi raz — do zamkniętej,
-- globalnej taksonomii rodzin — i zapisujemy wynik na zawsze. W czasie kliknięcia
-- zostaje zwykłe zapytanie relacyjne: zero wywołań modelu, zero osadzeń.
-- Wzorzec 1:1 z vehicle_segments (V82): pytanie „czym jest Powłoka ceramiczna"
-- ma jedną odpowiedź dla całego świata i nie zmienia się między studiami.

-- ═══════════════════════════════════════════════════════════════════════════════
-- 1. Rodzina nazwy usługi. Wiersz globalny ma studio_id = UUID zerowy; wiersz
--    per studio (source=MANUAL) NADPISUJE globalny — to furtka na przypadek,
--    gdy studio używa wieloznacznej nazwy („Ochrona lakieru") w swoim znaczeniu.
-- ═══════════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS service_families (
    id          UUID PRIMARY KEY,
    studio_id   UUID NOT NULL,
    -- lower + trim + zbite spacje; klucz wyszukiwania, nie do wyświetlania
    name_key    VARCHAR(220) NOT NULL,
    -- oryginalna pisownia, po której człowiek pozna, o co chodziło
    name_sample VARCHAR(220) NOT NULL,
    -- kod z zamkniętej listy w kodzie (ServiceFamily); PPF i WRAP to DWIE rodziny
    family      VARCHAR(30)  NOT NULL,
    -- FULL | PARTIAL | UNKNOWN — zakres wyczytany z samej nazwy
    scope       VARCHAR(20)  NOT NULL,
    -- LLM | MANUAL — ręcznej poprawki automat nie nadpisuje
    source      VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_service_families_key
    ON service_families (studio_id, name_key);

-- ═══════════════════════════════════════════════════════════════════════════════
-- 2. Sygnatura pozycji zlecenia: co (rodzina, zakres) wykonano na której wizycie.
--    Wiersz per POZYCJA, nie per zlecenie — zlecenie wielousługowe pasuje do
--    zapytania, jeśli pasuje KTÓRAKOLWIEK jego pozycja.
-- ═══════════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS visit_service_signatures (
    id           UUID PRIMARY KEY,
    visit_id     UUID NOT NULL,
    studio_id    UUID NOT NULL,
    name_key     VARCHAR(220) NOT NULL,
    family       VARCHAR(30)  NOT NULL,
    scope        VARCHAR(20)  NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_visit_service_signatures_visit
    ON visit_service_signatures (visit_id);
CREATE INDEX IF NOT EXISTS ix_visit_service_signatures_studio
    ON visit_service_signatures (studio_id);

-- ═══════════════════════════════════════════════════════════════════════════════
-- 3. Odczytana intencja leada: o jaką robotę pyta klient, względem cennika studia.
--    Jeden wiersz na leada, liczony przy PIERWSZYM otwarciu sekcji i ważny, dopóki
--    treść zapytania się nie zmieni (query_fingerprint). Dziennik decyzji jak
--    w lead_message_classifications (V109): model zapisany w wierszu.
-- ═══════════════════════════════════════════════════════════════════════════════
CREATE TABLE IF NOT EXISTS lead_service_intents (
    lead_id           UUID PRIMARY KEY,
    studio_id         UUID NOT NULL,
    -- MATCHED | NOT_IN_CATALOG | NO_SERVICE
    intent            VARCHAR(30) NOT NULL,
    -- kody rodzin rozdzielone przecinkami (zamknięta lista, bez przecinków w kodach)
    families          VARCHAR(300) NOT NULL DEFAULT '',
    -- name_key pozycji cennika wskazanych przez model, rozdzielone znakiem |
    matched_name_keys TEXT NOT NULL DEFAULT '',
    scope             VARCHAR(20) NOT NULL,
    query_fingerprint VARCHAR(64) NOT NULL,
    model             VARCHAR(60) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_lead_service_intents_studio
    ON lead_service_intents (studio_id);

-- ═══════════════════════════════════════════════════════════════════════════════
-- 4. Indeks wizyt przestaje być stanem indeksowania WEKTORÓW, a staje się
--    relacyjnym indeksem wyszukiwania: auto trzymane w kolumnach, nie w metadanych
--    dokumentu. signature_version = 0 dla istniejących wierszy wymusza ponowne
--    ostemplowanie całej historii — bez tego backfill nigdy by nie ruszył, bo
--    uzgadniacz wybiera kandydatów po updated_at wizyty.
-- ═══════════════════════════════════════════════════════════════════════════════
ALTER TABLE visit_index_state ADD COLUMN IF NOT EXISTS brand_key         VARCHAR(120);
ALTER TABLE visit_index_state ADD COLUMN IF NOT EXISTS model_key         VARCHAR(160);
ALTER TABLE visit_index_state ADD COLUMN IF NOT EXISTS size_segment      VARCHAR(20);
ALTER TABLE visit_index_state ADD COLUMN IF NOT EXISTS market_tier       VARCHAR(20);
ALTER TABLE visit_index_state ADD COLUMN IF NOT EXISTS happened_at       TIMESTAMPTZ;
ALTER TABLE visit_index_state ADD COLUMN IF NOT EXISTS signature_version INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS ix_visit_index_state_car
    ON visit_index_state (studio_id, brand_key, model_key);
CREATE INDEX IF NOT EXISTS ix_visit_index_state_segment
    ON visit_index_state (studio_id, size_segment);

-- ═══════════════════════════════════════════════════════════════════════════════
-- 5. Wektory zleceń wypadają z użycia całkowicie — dopasowanie auta zawsze było
--    metadanymi, a dopasowanie usługi przejmują rodziny. Tabela tworzona przez
--    Spring AI (nie przez Flyway), więc i sprzątana jawnie tutaj.
-- ═══════════════════════════════════════════════════════════════════════════════
DROP TABLE IF EXISTS visit_similarity_vectors;

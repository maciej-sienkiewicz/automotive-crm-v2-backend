-- Pasmo cenowe, pętle zwrotne i odczyt załączników — Etapy 4/5/7 przebudowy
-- (docs/similar-visits-redesign.md, §3.4, §4.2, §4.6).
--
-- 1. lead_similar_matches rośnie o WYNIK PASMA. Jedna tabela wyniku, nie dwie:
--    druga tabela kluczowana po lead_id oznaczałaby dual-write i adapter, którego
--    nikt potem nie usunie. verdict: BAND | SINGLE | EVIDENCE_ONLY | ABSTAIN;
--    abstention_code: NO_VEHICLE | NOT_IN_CATALOG | NO_COMPARABLE | ANALYSIS_FAILED.
--
-- 2. visit_match_feedback rośnie o POWÓD i ZAKRES odrzucenia oraz snapshot osi
--    zrobiony W CHWILI odrzucenia (późniejsze przestemplowanie nie może zmienić
--    znaczenia zapisanej opinii). scope=STUDIO wyklucza wizytę z puli compów całego
--    studia — koniec z odklikiwaniem tej samej absurdalnej wizyty na pięćdziesiątym
--    leadzie — ale z TTL (expires_at): rynek jest strukturalnie cienki i nieodwracalne
--    kasowanie compów jest groźniejsze niż jedno zbędne pokazanie.
--    verdict=RELEVANT dostaje pierwszą ścieżkę zapisu („Użyj tej ceny").
--
-- 3. lead_attachment_facts — fakty odczytane ze zdjęć klienta (L2). KLUCZ ZŁOŻONY
--    (studio_id, content_sha256): ten sam plik u dwóch najemców to dwa wiersze;
--    klucz po samym haszu byłby kolizją i wyciekiem opisu zdjęcia między studiami.
--    Hasz bajtów, nie attachment_id: ten sam plik przesłany dwa razy to jedna
--    odpowiedź modelu i jedna zapłata.
--
-- 4. anchor_outcomes — „Użyj tej ceny": którą kwotę właściciel faktycznie przeniósł
--    do wyceny. Pierwszy sygnał POZYTYWNY w historii tej funkcji.
--
-- 5. studio_price_anchors — mediana cen ZREALIZOWANYCH per nazwa usługi, liczona
--    nocnym jobem czystym SQL-em (zero LLM, zero kliknięć). Kotwica zastępcza dla
--    usług requireManualPrice, których basePriceGross wynosi 0 — bez niej bramka
--    skali wyłącza się dokładnie przy najdroższych i najrzadszych robotach.

ALTER TABLE lead_similar_matches
    ADD COLUMN IF NOT EXISTS verdict         VARCHAR(20),
    ADD COLUMN IF NOT EXISTS abstention_code VARCHAR(30),
    ADD COLUMN IF NOT EXISTS band_min        BIGINT,
    ADD COLUMN IF NOT EXISTS band_median     BIGINT,
    ADD COLUMN IF NOT EXISTS band_max        BIGINT,
    ADD COLUMN IF NOT EXISTS sample_size     SMALLINT,
    ADD COLUMN IF NOT EXISTS one_sided       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS anchor_gross    BIGINT,
    ADD COLUMN IF NOT EXISTS divergence      NUMERIC(6,3);

ALTER TABLE visit_match_feedback
    ADD COLUMN IF NOT EXISTS reason_code    VARCHAR(30),
    ADD COLUMN IF NOT EXISTS scope          VARCHAR(10) NOT NULL DEFAULT 'LEAD',
    ADD COLUMN IF NOT EXISTS expires_at     TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS need_operation VARCHAR(20),
    ADD COLUMN IF NOT EXISTS need_part      VARCHAR(20),
    ADD COLUMN IF NOT EXISTS cand_operation VARCHAR(20),
    ADD COLUMN IF NOT EXISTS cand_part      VARCHAR(20),
    ADD COLUMN IF NOT EXISTS price_ratio    NUMERIC(10,4);

CREATE INDEX IF NOT EXISTS ix_vmf_studio_scope
    ON visit_match_feedback (studio_id, scope, visit_id);
CREATE INDEX IF NOT EXISTS ix_vmf_axes_pair
    ON visit_match_feedback (need_operation, need_part, cand_operation, cand_part);

CREATE TABLE IF NOT EXISTS lead_attachment_facts (
    studio_id       UUID NOT NULL,
    content_sha256  CHAR(64) NOT NULL,
    lead_id         UUID NOT NULL,
    attachment_id   UUID NOT NULL,
    readable        BOOLEAN NOT NULL,
    part            VARCHAR(20),
    operation_hint  VARCHAR(20),
    damage_type     VARCHAR(30),
    severity        VARCHAR(20),
    spot_count      SMALLINT,
    summary_pl      VARCHAR(300),
    model           VARCHAR(60) NOT NULL,
    prompt_version  VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (studio_id, content_sha256)
);

CREATE INDEX IF NOT EXISTS ix_laf_lead
    ON lead_attachment_facts (lead_id);

CREATE TABLE IF NOT EXISTS anchor_outcomes (
    id           UUID PRIMARY KEY,
    studio_id    UUID NOT NULL,
    lead_id      UUID NOT NULL,
    visit_id     UUID,
    band_median  BIGINT,
    price_used   BIGINT NOT NULL,
    operation    VARCHAR(20),
    part         VARCHAR(20),
    created_at   TIMESTAMPTZ NOT NULL,
    created_by   UUID
);

CREATE INDEX IF NOT EXISTS ix_ao_studio
    ON anchor_outcomes (studio_id, created_at DESC);

CREATE TABLE IF NOT EXISTS studio_price_anchors (
    studio_id             UUID NOT NULL,
    name_key              VARCHAR(220) NOT NULL,
    median_realized_gross BIGINT NOT NULL,
    iqr_ratio             NUMERIC(6,3),
    observations          INT NOT NULL,
    window_from           DATE NOT NULL,
    computed_at           TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (studio_id, name_key)
);

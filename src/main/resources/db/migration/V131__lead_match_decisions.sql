-- Dziennik decyzji doboru podobnych zleceń — Etap 1 przebudowy
-- (docs/similar-visits-redesign.md, §5).
--
-- WIERSZ POWSTAJE TAKŻE DLA KANDYDATÓW ODRZUCONYCH. Dziś grade() zwraca samą rangę,
-- a wyliczone po drodze pokrycie i skupienie giną wraz z ramką stosu — na pytanie
-- „dlaczego próg bagażnika wszedł, a full body nie" odpowiada się śledztwem w kodzie
-- zamiast zapytaniem SQL. Ten dziennik zamienia śledztwo w SELECT i jest jedynym
-- materiałem, na którym da się potem skalibrować progi (albo udowodnić, że
-- weryfikator LLM niczego nie dokłada i wyciąć go z kodu).
--
-- Kolumny v_* niosą werdykt weryfikatora LLM (L4) — null, gdy weryfikator nie był
-- uruchamiany (jest adaptacyjny: wchodzi tylko przy ≥2 kandydatach i dużym rozrzucie).
-- verifier_agreed_with_gate to licznik KRYTERIUM ŚMIERCI L4: bliskie 100% zgody
-- po kwartale = jedyne płatne wywołanie w ścieżce leada wypada z kodu.
-- why_it_fits / what_differs to PRODUKT: zdania weryfikatora idą na kartę compa.
--
-- comp_class: DIRECT | ADJUSTED | REJECTED
-- reject_code: kod bramki, która odrzuciła (OPERATION_MISMATCH | SURFACE_MISMATCH |
--              SCALE_MISMATCH | TOO_OLD | VALUE_FOCUS | CAR_MISMATCH | NO_SIGNATURES |
--              WORK_MISMATCH | NO_INTENT | VERIFIER_REJECTED) — bez CHECK-a, patrz V101.

CREATE TABLE IF NOT EXISTS lead_match_decisions (
    id              UUID PRIMARY KEY,
    studio_id       UUID NOT NULL,
    lead_id         UUID NOT NULL,
    visit_id        UUID NOT NULL,
    tier            VARCHAR(40),
    comp_class      VARCHAR(20) NOT NULL,
    value_coverage  NUMERIC(6,3),
    value_focus     NUMERIC(6,3),
    price_ratio     NUMERIC(10,4),
    shown           BOOLEAN NOT NULL,
    position        SMALLINT,
    exploration     BOOLEAN NOT NULL DEFAULT FALSE,
    relaxed_axis    VARCHAR(20),
    reject_code     VARCHAR(40),
    prompt_version  VARCHAR(20),
    rules_version   SMALLINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    v_same_operation          BOOLEAN,
    v_same_part               BOOLEAN,
    v_same_scale              BOOLEAN,
    v_price_comparable        BOOLEAN,
    verifier_agreed_with_gate BOOLEAN,
    why_it_fits     VARCHAR(200),
    what_differs    VARCHAR(200)
);

CREATE INDEX IF NOT EXISTS ix_lmd_lead
    ON lead_match_decisions (lead_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_lmd_studio_reject
    ON lead_match_decisions (studio_id, reject_code, created_at);

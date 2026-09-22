-- Sugestie usług muszą umieć się uzasadnić — prompt v3.
--
-- Incydent: lead z pytaniem „kompleksowa renowacja lamp, obejmująca usunięcie
-- zmatowienia i zarysowań oraz zabezpieczenie powierzchni po wykonanej usłudze"
-- (Volkswagen Passat B7) dostał dwie sugestie: „Oklejenie reflektorów folią ochronną"
-- za 300 zł i „Okresowy serwis powłoki ceramicznej" za 499 zł. Klient nie pytał ani
-- o jedno, ani o drugie. Cennik tego studia NIE MA renowacji reflektorów, więc
-- poprawną odpowiedzią była pusta lista.
--
-- Zapis w lead_service_intents pokazał dokładnie, co się stało:
--   needs    = CORRECT:LAMPS:FULL|PROTECT:LAMPS:FULL   ← jedna robota rozbita na dwie
--   families = CORRECTION_POLISH                        ← właściwa rodzina...
--   pozycje  = rodziny PPF i CERAMIC_COATING            ← ...i pozycje z dwóch innych
--   anchor   = 79900 gr, CATALOG                        ← błąd poszedł też w kotwicę cen
--
-- Trzy braki, które to przepuściły, i trzy kolumny, które je domykają:
--
-- 1. lead_service_items.evidence_quote — sugestia bez dosłownego pokrycia w treści
--    maila nie ma prawa powstać. Cytat jest sprawdzany W KODZIE jako podciąg zapytania,
--    które model widział (LeadServiceIntentService.quoteBackedBy) — to ta sama zasada,
--    co „model wybiera numery pozycji, nigdy nie emituje nazw ani cen": uzasadnienie
--    dane na słowo jest warte tyle, co cena dana na słowo. Do v3 istniał JEDEN
--    evidence_quote na cały werdykt i nigdy nie wracał z bazy (toIntent go nie mapował).
--
-- 2. lead_service_intents.verdict_json — werdykt PER POTRZEBA zamiast jednego na leada.
--    Bez tego nie dało się wyrazić zdania, które było tu prawdą: „głównej roboty
--    (renowacja lamp) NIE MA w cenniku, a poboczna jest". MATCHED na jednej pozycji
--    przepuszczał wszystkie pozostałe. JSON, a nie łańcuch z separatorem „|" jak
--    w kolumnie needs, bo niesie DOSŁOWNE cytaty z maila klienta — każdy separator
--    byłby tam ładunkiem do wstrzyknięcia.
--
-- 3. lead_service_intents.reasoning — analiza modelu była parsowana i wyrzucana,
--    więc na pytanie „dlaczego akurat ta pozycja" odpowiadało się śledztwem w kodzie.
--    Bratni tor (lead_message_classifications) zapisuje ją od początku.
--
-- prompt_version idzie z v2 na v3 w kodzie, więc WSZYSTKIE zapisane werdykty
-- unieważniają się same, leniwie, przy najbliższym otwarciu leada. Wiersze zastane
-- (verdict_json = '') czytane są starą ścieżką i nie udają, że mają uzasadnienie.

ALTER TABLE lead_service_items
    ADD COLUMN IF NOT EXISTS evidence_quote VARCHAR(300);

ALTER TABLE lead_service_intents
    ADD COLUMN IF NOT EXISTS reasoning    VARCHAR(1000),
    ADD COLUMN IF NOT EXISTS verdict_json TEXT NOT NULL DEFAULT '';

-- Dziennik doboru sugestii — WIERSZ PER KANDYDAT, TAKŻE ODRZUCONY.
--
-- Bliźniak lead_match_decisions (V130) po stronie sugestii. Powstał z pytania, na które
-- nie dało się odpowiedzieć bez odtwarzania rozumowania modelu z pamięci: „dlaczego
-- serwis powłoki ceramicznej wszedł, a nic innego nie?". Zapisany był wyłącznie wynik.
--
-- stage mówi, na której bramce pozycja się zatrzymała (albo SHOWN, gdy przeszła):
--   INVALID_INDEX  numer spoza cennika          NEED_NOT_MATCHED  potrzeba spoza oferty
--   ROLE_NOT_ANSWER dosprzedaż, nie odpowiedź   NO_QUOTE          brak pokrycia w treści
--   FAMILY_MISMATCH rodzina spoza zadeklarowanych  AXIS_MISMATCH  inna operacja/część
--   INACTIVE_OR_PACKAGE nieaktywna lub pakiet   DUPLICATE         ta sama pod dwiema potrzebami
--   VERIFIER_REJECTED  drugi model powiedział nie
--   VERIFIER_UNAVAILABLE weryfikator podpięty, ale przerwany awarią → abstencja
--
-- Bez CHECK-ów na stage i role (spójnie z V101 i V132): źródłem prawdy są stałe
-- STAGE_* w LeadServiceIntentService i enum SuggestionRole.
--
-- Nowy przebieg zastępuje poprzedni (DELETE po lead_id przed zapisem) — dziennik
-- opisuje AKTUALNY dobór, historię zmian trzyma audyt.
CREATE TABLE IF NOT EXISTS lead_suggestion_decisions (
    id             UUID         PRIMARY KEY,
    studio_id      UUID         NOT NULL,
    lead_id        UUID         NOT NULL,
    service_id     UUID,
    service_name   VARCHAR(200) NOT NULL,
    need_index     INTEGER      NOT NULL,
    stage          VARCHAR(30)  NOT NULL,
    quote          VARCHAR(300),
    role           VARCHAR(20)  NOT NULL,
    prompt_version VARCHAR(20)  NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_lsd_lead
    ON lead_suggestion_decisions (lead_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_lsd_studio_stage
    ON lead_suggestion_decisions (studio_id, stage, created_at);

COMMENT ON COLUMN lead_service_items.evidence_quote IS
    'Dosłowny fragment wiadomości klienta uzasadniający sugestię, sprawdzony w kodzie jako podciąg zapytania. NULL dla pozycji ręcznych i sugestii sprzed v3.';
COMMENT ON COLUMN lead_service_intents.verdict_json IS
    'Werdykt v3: potrzeby ze statusami i cytatami + pozycje, które przeszły bramki. Pusty łańcuch = wiersz sprzed v3, czytany starą ścieżką.';
COMMENT ON COLUMN lead_suggestion_decisions.stage IS
    'Bramka, na której pozycja odpadła, albo SHOWN. Źródło prawdy: stałe STAGE_* w LeadServiceIntentService.';

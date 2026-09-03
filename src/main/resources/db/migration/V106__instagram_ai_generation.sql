-- Trwałość generatora postów Instagram: reguły stylistyczne studia + historia wygenerowanych postów.
--
-- Do tej pory reguły stylistyczne przychodziły w każdym żądaniu z frontendu i ginęły razem
-- z nim, a wygenerowany post nie istniał nigdzie poza odpowiedzią HTTP — nie dało się go
-- ocenić ani niczego się z tej oceny nauczyć. Obie tabele zamykają tę pętlę.
--
-- Statusy (rating) trzymane jako VARCHAR bez CHECK-a wyliczającego wartości enuma
-- — patrz V101 i NoEnumCheckConstraintsTest.

CREATE TABLE IF NOT EXISTS instagram_style_rules (
    id          UUID PRIMARY KEY,
    studio_id   UUID NOT NULL,
    -- Jedna reguła = jedno zdanie w prompcie (np. „Nie używaj emoji"). Limit długości
    -- i liczby aktywnych reguł pilnuje warstwa aplikacyjna — prompt musi zmieścić się
    -- w rozsądnym budżecie tokenów.
    rule_text   TEXT NOT NULL,
    -- Wyłączona reguła zostaje w bazie: studio zwykle chce ją włączyć z powrotem,
    -- a historyczne oceny czyta się względem reguł z chwili generowania.
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_instagram_style_rules_studio
    ON instagram_style_rules (studio_id);

CREATE TABLE IF NOT EXISTS instagram_generated_posts (
    id                  UUID PRIMARY KEY,
    studio_id           UUID NOT NULL,
    topic               TEXT NOT NULL,
    additional_context  TEXT,
    requested_tone      VARCHAR(32),
    requested_length    VARCHAR(16),
    content             TEXT NOT NULL,
    -- 'POSITIVE' | 'NEGATIVE'; NULL dopóki studio nie oceniło posta.
    rating              VARCHAR(16),
    -- Komentarz wyłącznie przy ocenie negatywnej — „za dużo wykrzykników" jest
    -- konkretną wskazówką dla kolejnych generowań, „ok" przy pozytywnej niczym nie jest.
    rating_comment      TEXT,
    -- Raport pętli generuj → weryfikuj → popraw: werdykt per reguła + liczba iteracji.
    verification_report JSONB,
    -- Reguły aktywne W CHWILI generowania. Bez snapshotu ocena „post łamie reguły"
    -- byłaby czytana względem dzisiejszych reguł, a te mogły się od tego czasu zmienić.
    rules_snapshot      JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    rated_at            TIMESTAMPTZ
);

-- Historia postów studia (GET /posts) oraz weryfikacja przynależności przy ocenie.
CREATE INDEX IF NOT EXISTS idx_instagram_generated_posts_studio
    ON instagram_generated_posts (studio_id, created_at DESC);

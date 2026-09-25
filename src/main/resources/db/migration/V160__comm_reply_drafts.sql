-- ═══════════════════════════════════════════════════════════════════════════════
-- Szkice odpowiedzi na maile (LLM + RAG z historii wysłanych wiadomości).
--
-- comm_reply_examples: pary „pytanie klienta → nasza odpowiedź" wyjęte z poczty
-- studia, z wektorem pytania. Szkic „w moim stylu" szuka par, w których klient
-- pytał o to samo, i pokazuje modelowi, jak studio wtedy odpisało.
--
-- Wiersz powstaje dla KAŻDEJ przejrzanej wiadomości wychodzącej; eligible = FALSE
-- oznacza odrzut (brak pytania przed odpowiedzią, odpowiedź za krótka) — dzięki
-- temu uzgadniacz nie bierze tych samych odrzutów przy każdym przebiegu.
--
-- Bez indeksu HNSW, świadomie: HNSW jest przybliżony i przy filtrze po studiu
-- potrafi zwrócić inny zestaw dla tego samego zapytania. Szkic ma być powtarzalny,
-- a jedno studio ma setki, najwyżej tysiące par — pełny przegląd po indeksie
-- studia kosztuje milisekundy i zawsze daje ten sam wynik.
--
-- comm_reply_draft_preferences: odpowiedź użytkownika na pytanie „pisać w moim
-- stylu czy zaproponować treść?". Brak wiersza = jeszcze nie pytaliśmy.
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS comm_reply_examples (
    id                  UUID PRIMARY KEY,
    studio_id           UUID         NOT NULL,
    thread_id           UUID         NOT NULL,
    outbound_message_id UUID         NOT NULL,
    inbound_message_id  UUID,
    eligible            BOOLEAN      NOT NULL,
    inquiry_text        TEXT,
    reply_text          TEXT,
    sent_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    embedding           vector(1536)
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_comm_reply_examples_outbound
    ON comm_reply_examples (outbound_message_id);

CREATE INDEX IF NOT EXISTS idx_comm_reply_examples_studio
    ON comm_reply_examples (studio_id, eligible);

CREATE TABLE IF NOT EXISTS comm_reply_draft_preferences (
    user_id        UUID PRIMARY KEY,
    studio_id      UUID    NOT NULL,
    use_sent_style BOOLEAN NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

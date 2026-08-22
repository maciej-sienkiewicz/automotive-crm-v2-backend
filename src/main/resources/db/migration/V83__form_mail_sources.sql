-- Nadawcy-roboty formularzy: „Oznacz mail jako lead z formularza".
--
-- Formularz na stronie studia wysyła powiadomienie zawsze z tego samego adresu
-- (wordpress@studio.pl, no-reply@wix.com), a klient jest dopiero W TREŚCI maila.
-- Oznaczenie jednego takiego maila zapisuje nadawcę tutaj — i od tej chwili każdy
-- kolejny mail z tego adresu przechodzi przez odczyt LLM-em i sam staje się leadem.
CREATE TABLE IF NOT EXISTS form_mail_sources (
    id             UUID PRIMARY KEY,
    studio_id      UUID NOT NULL,
    -- Znormalizowany (lower + trim): to jest klucz dopasowania przychodzącej poczty.
    sender_email   VARCHAR(320) NOT NULL,
    -- Wyłączenie zamiast usunięcia: dziennik odczytów wskazuje na źródło, a historia
    -- „skąd wziął się ten lead" ma przeżyć rozmyślenie się użytkownika.
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by_name VARCHAR(255),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    lead_count     BIGINT       NOT NULL DEFAULT 0,
    last_lead_at   TIMESTAMPTZ
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_form_mail_sources_key
    ON form_mail_sources (studio_id, sender_email);

-- Dziennik odczytów: jeden wiersz na przetworzony mail, także nieudany.
--
-- Unikalność po message_id robi z przetwarzania operację idempotentną — restart,
-- ponowny sync IMAP ani podwójne kliknięcie nie mają prawa zrobić drugiego leada
-- z tego samego maila. Wpis „przyszło, ale poległo" jest wart więcej niż brak śladu.
CREATE TABLE IF NOT EXISTS form_mail_extractions (
    id          UUID PRIMARY KEY,
    studio_id   UUID NOT NULL,
    source_id   UUID NOT NULL,
    message_id  UUID NOT NULL,
    -- CREATED | REJECTED | FAILED
    status      VARCHAR(20) NOT NULL,
    reason      VARCHAR(300),
    lead_id     UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_form_mail_extractions_message
    ON form_mail_extractions (message_id);

CREATE INDEX IF NOT EXISTS ix_form_mail_extractions_source
    ON form_mail_extractions (source_id, created_at DESC);

-- Kolejka wiadomości do klienta czekających na godziny wysyłki.
--
-- Okno 12:00–18:00 (communication.send-window.*) obowiązywało dotąd tylko podziękowania
-- po wizycie. Od teraz egzekwuje je bramka wysyłkowa dla KAŻDEJ wiadomości do klienta:
-- poza oknem wiadomość nie idzie do dostawcy, tylko ląduje tutaj, a dispatcher wysyła ją
-- przy najbliższym otwarciu. Wyjątki (link do podpisu przy ladzie, przypomnienie
-- „za godzinę wizyta", wysyłki testowe) omijają kolejkę jawnie w kodzie — DeliveryPolicy.
--
-- W wierszu leży tylko to, co klient ma przeczytać. Adresat jest ten podany przez
-- wywołującego, PRZED przekierowaniem na numer studia: moduł, zgoda, przekierowanie,
-- whitelista i kredyty są rozstrzygane dopiero w chwili faktycznej wysyłki.

CREATE TABLE IF NOT EXISTS outbound_message_queue (
    id                  UUID PRIMARY KEY,
    studio_id           UUID NOT NULL,
    -- NULL dla wiadomości bez kartoteki klienta (zestawienie miesięczne dla kontrahenta).
    customer_id         UUID,
    channel             VARCHAR(10) NOT NULL,
    category            VARCHAR(30) NOT NULL,
    recipient           VARCHAR(255) NOT NULL,
    subject             VARCHAR(500),
    body                TEXT NOT NULL,
    context             VARCHAR(255) NOT NULL,
    -- QUEUED / SENDING / SENT / FAILED / CANCELLED. Świadomie bez CHECK-a — patrz
    -- EnumCheckConstraintDropper: lista stałych w bazie rozjeżdża się z enumem.
    status              VARCHAR(20) NOT NULL,
    scheduled_for       TIMESTAMPTZ NOT NULL,
    attempts            INTEGER NOT NULL DEFAULT 0,
    last_error          TEXT,
    external_message_id VARCHAR(255),
    sent_at             TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Dispatcher: co minutę „QUEUED z terminem w przeszłości", najstarsze pierwsze.
CREATE INDEX IF NOT EXISTS idx_outbound_queue_status_scheduled
    ON outbound_message_queue (status, scheduled_for);

CREATE INDEX IF NOT EXISTS idx_outbound_queue_studio
    ON outbound_message_queue (studio_id, created_at);

-- Załączniki (PDF zestawienia) osobno: listowanie kolejki nie ciągnie bajtów,
-- a po wysyłce (albo ostatecznej porażce) wiersze załączników są kasowane.
CREATE TABLE IF NOT EXISTS outbound_message_attachment (
    id           UUID PRIMARY KEY,
    message_id   UUID NOT NULL REFERENCES outbound_message_queue (id) ON DELETE CASCADE,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(255) NOT NULL,
    content      BYTEA NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_outbound_attachment_message
    ON outbound_message_attachment (message_id);

-- Dziennik komunikacji dostaje nowy status QUEUED i wskaźnik na wiersz kolejki, którym
-- dispatcher domyka wpis (QUEUED -> SENT / FAILED) po faktycznej wysyłce.
ALTER TABLE communication_log
    ADD COLUMN IF NOT EXISTS queued_message_id UUID;

CREATE INDEX IF NOT EXISTS idx_comm_log_queued_message
    ON communication_log (queued_message_id);

-- Na bazach założonych przez Hibernate kolumna status ma CHECK z listą stałych sprzed
-- QUEUED. EnumCheckConstraintDropper usuwa go przy starcie; ta migracja robi to samo
-- dla środowisk, na których dropper jest wyłączony.
DO $$
DECLARE c RECORD;
BEGIN
    FOR c IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'communication_log'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%status%ANY (ARRAY[%'
    LOOP
        EXECUTE format('ALTER TABLE communication_log DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

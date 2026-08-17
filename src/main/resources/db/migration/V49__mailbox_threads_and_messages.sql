-- Skrzynka pocztowa studia: konta IMAP/SMTP, wątki i wiadomości e-mail.
--
-- 1. mail_accounts       — skrzynka podpięta przez studio (IMAP/SMTP lub OAuth), hasło szyfrowane AES-GCM.
-- 2. mail_threads        — kontener konwersacji; istnieje niezależnie od leada (spam/faktury też są wątkami),
--                          a lead_id wskazuje leada dopiero, gdy wątek zostanie uznany za zapytanie ofertowe.
-- 3. mail_messages       — pojedyncza wiadomość z nagłówkami RFC 5322; message_id jest kluczem idempotencji
--                          ingestu i podstawą deterministycznego wątkowania (In-Reply-To / References).
-- 4. mail_sender_rules   — reguły nadawców uczone z decyzji użytkownika ("to nie zapytanie"), wejście do
--                          zerokosztowego etapu filtrowania przed wywołaniem LLM.

CREATE TABLE IF NOT EXISTS mail_accounts (
    id                   UUID PRIMARY KEY,
    studio_id            UUID NOT NULL,
    email_address        VARCHAR(320) NOT NULL,
    provider_type        VARCHAR(30) NOT NULL,
    auth_type            VARCHAR(30) NOT NULL,
    encrypted_password   TEXT,
    imap_host            VARCHAR(255),
    imap_port            INTEGER,
    smtp_host            VARCHAR(255),
    smtp_port            INTEGER,
    status               VARCHAR(30) NOT NULL,
    last_error           VARCHAR(500),
    last_sync_at         TIMESTAMP WITH TIME ZONE,
    inbox_uid_validity   BIGINT,
    inbox_last_uid       BIGINT,
    sent_uid_validity    BIGINT,
    sent_last_uid        BIGINT,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_mail_accounts_studio_email ON mail_accounts(studio_id, email_address);
CREATE INDEX IF NOT EXISTS idx_mail_accounts_studio ON mail_accounts(studio_id);
CREATE INDEX IF NOT EXISTS idx_mail_accounts_status ON mail_accounts(status);

CREATE TABLE IF NOT EXISTS mail_threads (
    id                   UUID PRIMARY KEY,
    studio_id            UUID NOT NULL,
    mail_account_id      UUID,
    lead_id              UUID,
    subject_norm         VARCHAR(500),
    external_thread_key  VARCHAR(255),
    classification       VARCHAR(20) NOT NULL,
    last_message_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_direction       VARCHAR(10),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_mail_threads_studio_last_msg ON mail_threads(studio_id, last_message_at);
CREATE INDEX IF NOT EXISTS idx_mail_threads_lead ON mail_threads(lead_id);
CREATE INDEX IF NOT EXISTS idx_mail_threads_studio_subject ON mail_threads(studio_id, subject_norm);
CREATE INDEX IF NOT EXISTS idx_mail_threads_studio_extkey ON mail_threads(studio_id, external_thread_key);
CREATE INDEX IF NOT EXISTS idx_mail_threads_studio_classification ON mail_threads(studio_id, classification);

CREATE TABLE IF NOT EXISTS mail_messages (
    id                   UUID PRIMARY KEY,
    studio_id            UUID NOT NULL,
    thread_id            UUID NOT NULL,
    mail_account_id      UUID,
    direction            VARCHAR(10) NOT NULL,
    message_id           VARCHAR(500) NOT NULL,
    in_reply_to          VARCHAR(500),
    references_ids       TEXT,
    from_email           VARCHAR(320) NOT NULL,
    from_name            VARCHAR(255),
    to_emails            TEXT,
    subject              VARCHAR(1000),
    sent_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    body_html            TEXT,
    body_text_clean      TEXT,
    has_attachments      BOOLEAN NOT NULL DEFAULT FALSE,
    provider_uid         VARCHAR(255),
    send_status          VARCHAR(20) NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Idempotencja ingestu: ta sama wiadomość pobrana ponownie (re-fetch IMAP, kopia z folderu Wysłane)
-- nie tworzy duplikatu, tylko uzupełnia provider_uid.
CREATE UNIQUE INDEX IF NOT EXISTS idx_mail_messages_studio_msgid ON mail_messages(studio_id, message_id);
CREATE INDEX IF NOT EXISTS idx_mail_messages_thread_sent ON mail_messages(thread_id, sent_at);

CREATE TABLE IF NOT EXISTS mail_sender_rules (
    id                   UUID PRIMARY KEY,
    studio_id            UUID NOT NULL,
    pattern              VARCHAR(320) NOT NULL,
    classification       VARCHAR(20) NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_mail_sender_rules_studio_pattern ON mail_sender_rules(studio_id, pattern);

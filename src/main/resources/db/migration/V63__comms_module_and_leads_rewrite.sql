-- Nowy moduł komunikacji (webmail + synchronizacja IMAP) i przepisany moduł leadów.
--
-- Stary moduł (wątki mailowe z klasyfikacją AI, estymacje, komentarze, cytaty) jest
-- usuwany w całości — świadoma decyzja "czystej karty". mail_accounts zostaje bez zmian:
-- podpięte skrzynki i zaszyfrowane poświadczenia przeżywają migrację; liczniki UID są
-- zerowane, żeby nowy silnik wykonał świeży backfill (dedup po Message-ID i tak
-- zabezpiecza przed duplikatami).
--
-- 1. comm_threads         — konwersacja; jednostka pracy użytkownika i zaczep leada.
-- 2. comm_messages        — wiadomość: tożsamość wewnętrzna (id/message_id_hdr) trwała,
--                           tożsamość IMAP (uid/uidvalidity) ulotna i wymienialna.
-- 3. comm_attachments     — załączniki + obrazki inline (cid), treść w bazie (limit 15 MB).
-- 4. comm_labels          — lokalne foldery użytkownika; nigdy nie dotykają IMAP.
-- 5. comm_outbox          — idempotentne komendy IMAP (oznacz przeczytane, APPEND do Wysłanych).
-- 6. leads (v2)           — lead wskazuje wątek; usługi z zamrożoną ceną; słownik powodów porażki.
-- 7. lead_service_items   — wycenione pozycje; suma = wartość leada.
-- 8. lead_status_history  — append-only ślad przejść statusów; surowiec analityki.

-- ── Stary moduł: do usunięcia ────────────────────────────────────────────────────────
DROP TABLE IF EXISTS mail_sender_rules;
DROP TABLE IF EXISTS mail_messages;
DROP TABLE IF EXISTS mail_threads;
DROP TABLE IF EXISTS lead_comments;
DROP TABLE IF EXISTS lead_estimation_items;
DROP TABLE IF EXISTS lead_estimations;
DROP TABLE IF EXISTS lead_user_quote_items;
DROP TABLE IF EXISTS lead_user_quotes;
DROP TABLE IF EXISTS quote_reply_examples;
DROP TABLE IF EXISTS leads;

-- Świeży backfill dla nowego silnika.
UPDATE mail_accounts SET
    inbox_uid_validity = NULL,
    inbox_last_uid     = NULL,
    sent_uid_validity  = NULL,
    sent_last_uid      = NULL;

-- ── Komunikacja ──────────────────────────────────────────────────────────────────────
CREATE TABLE comm_threads (
    id                 UUID PRIMARY KEY,
    studio_id          UUID NOT NULL,
    account_id         UUID NOT NULL,
    subject_norm       VARCHAR(500),
    subject            VARCHAR(1000),
    participant_email  VARCHAR(320) NOT NULL,
    participant_name   VARCHAR(255),
    last_message_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_direction     VARCHAR(10) NOT NULL,
    last_snippet       VARCHAR(200),
    message_count      INTEGER NOT NULL DEFAULT 0,
    unread_count       INTEGER NOT NULL DEFAULT 0,
    has_attachments    BOOLEAN NOT NULL DEFAULT FALSE,
    lead_id            UUID,
    label_id           UUID,
    archived           BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_comm_threads_studio_last   ON comm_threads(studio_id, last_message_at);
CREATE INDEX idx_comm_threads_account_last  ON comm_threads(account_id, last_message_at);
CREATE INDEX idx_comm_threads_participant   ON comm_threads(studio_id, participant_email);
CREATE INDEX idx_comm_threads_lead          ON comm_threads(lead_id);
CREATE INDEX idx_comm_threads_label         ON comm_threads(label_id);

CREATE TABLE comm_messages (
    id                 UUID PRIMARY KEY,
    studio_id          UUID NOT NULL,
    account_id         UUID NOT NULL,
    thread_id          UUID NOT NULL,
    direction          VARCHAR(10) NOT NULL,
    folder_kind        VARCHAR(10) NOT NULL,
    message_id_hdr     VARCHAR(500) NOT NULL,
    in_reply_to        VARCHAR(500),
    references_ids     TEXT,
    from_email         VARCHAR(320) NOT NULL,
    from_name          VARCHAR(255),
    to_emails          TEXT,
    cc_emails          TEXT,
    subject            VARCHAR(1000),
    sent_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    body_html_safe     TEXT,
    body_text          TEXT,
    body_text_clean    TEXT,
    has_attachments    BOOLEAN NOT NULL DEFAULT FALSE,
    imap_uid           BIGINT,
    imap_uid_validity  BIGINT,
    is_read            BOOLEAN NOT NULL DEFAULT FALSE,
    read_source        VARCHAR(10),
    read_at            TIMESTAMP WITH TIME ZONE,
    send_status        VARCHAR(10) NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Idempotencja ingestu: ta sama wiadomość pobrana ponownie (reset UIDVALIDITY, kopia
-- własnej wysyłki wracająca z folderu Wysłane) nie tworzy duplikatu.
CREATE UNIQUE INDEX idx_comm_messages_account_msgid ON comm_messages(account_id, message_id_hdr);
CREATE INDEX idx_comm_messages_thread_sent ON comm_messages(thread_id, sent_at);
CREATE INDEX idx_comm_messages_studio_from ON comm_messages(studio_id, from_email);
CREATE INDEX idx_comm_messages_unread      ON comm_messages(account_id, folder_kind, is_read);

CREATE TABLE comm_attachments (
    id            UUID PRIMARY KEY,
    studio_id     UUID NOT NULL,
    message_id    UUID NOT NULL,
    file_name     VARCHAR(500) NOT NULL,
    content_type  VARCHAR(255) NOT NULL,
    content_id    VARCHAR(300),
    is_inline     BOOLEAN NOT NULL DEFAULT FALSE,
    size_bytes    BIGINT NOT NULL,
    content       BYTEA NOT NULL
);

CREATE INDEX idx_comm_attachments_message ON comm_attachments(message_id);
CREATE INDEX idx_comm_attachments_cid     ON comm_attachments(message_id, content_id);

CREATE TABLE comm_labels (
    id          UUID PRIMARY KEY,
    studio_id   UUID NOT NULL,
    name        VARCHAR(100) NOT NULL,
    color       VARCHAR(20),
    position    INTEGER NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX idx_comm_labels_studio_name ON comm_labels(studio_id, name);

CREATE TABLE comm_outbox (
    id               UUID PRIMARY KEY,
    studio_id        UUID NOT NULL,
    account_id       UUID NOT NULL,
    message_id       UUID NOT NULL,
    command_type     VARCHAR(20) NOT NULL,
    status           VARCHAR(10) NOT NULL,
    attempts         INTEGER NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error       VARCHAR(500),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_comm_outbox_due     ON comm_outbox(status, next_attempt_at);
CREATE INDEX idx_comm_outbox_message ON comm_outbox(message_id);

-- ── Leady v2 ─────────────────────────────────────────────────────────────────────────
CREATE TABLE leads (
    id                     UUID PRIMARY KEY,
    studio_id              UUID NOT NULL,
    source                 VARCHAR(20) NOT NULL,
    status                 VARCHAR(20) NOT NULL,
    contact_identifier     TEXT NOT NULL,
    customer_name          TEXT,
    initial_message        TEXT,
    estimated_value        BIGINT NOT NULL DEFAULT 0,
    requires_verification  BOOLEAN NOT NULL DEFAULT FALSE,
    vehicle_brand          TEXT,
    vehicle_model          TEXT,
    customer_id            UUID,
    appointment_id         UUID,
    visit_id               UUID,
    assigned_user_id       UUID,
    assigned_user_name     TEXT,
    lost_reason            VARCHAR(500),
    stagnant_alert_sent_at TIMESTAMP WITH TIME ZONE,
    thread_id              UUID,
    category               VARCHAR(30),
    lost_reason_code       VARCHAR(30),
    first_response_at      TIMESTAMP WITH TIME ZONE,
    closed_at              TIMESTAMP WITH TIME ZONE,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_leads_studio_status  ON leads(studio_id, status);
CREATE INDEX idx_leads_studio_created ON leads(studio_id, created_at);
CREATE INDEX idx_leads_contact        ON leads(studio_id, contact_identifier);
CREATE INDEX idx_leads_thread         ON leads(thread_id);
CREATE INDEX idx_leads_appointment    ON leads(appointment_id);

CREATE TABLE lead_service_items (
    id           UUID PRIMARY KEY,
    studio_id    UUID NOT NULL,
    lead_id      UUID NOT NULL,
    service_id   UUID,
    name         VARCHAR(200) NOT NULL,
    price_gross  BIGINT NOT NULL,
    quantity     INTEGER NOT NULL DEFAULT 1,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_lead_service_items_lead ON lead_service_items(lead_id);

CREATE TABLE lead_status_history (
    id                  UUID PRIMARY KEY,
    studio_id           UUID NOT NULL,
    lead_id             UUID NOT NULL,
    from_status         VARCHAR(20),
    to_status           VARCHAR(20) NOT NULL,
    lost_reason_code    VARCHAR(30),
    changed_by_user_id  UUID,
    changed_by_name     VARCHAR(200),
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_lead_status_history_lead   ON lead_status_history(lead_id, created_at);
CREATE INDEX idx_lead_status_history_studio ON lead_status_history(studio_id, created_at);

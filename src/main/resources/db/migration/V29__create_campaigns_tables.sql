-- Moduł kampanii SMS & e-mail (docs/campaigns-module-design.md)

CREATE TABLE campaigns (
    id                  uuid PRIMARY KEY,
    studio_id           uuid NOT NULL,
    name                varchar(200) NOT NULL,
    kind                varchar(20) NOT NULL,          -- ONE_TIME | AUTOMATIC
    channel             varchar(10) NOT NULL,          -- SMS | EMAIL | BOTH
    status              varchar(20) NOT NULL,          -- DRAFT|SCHEDULED|SENDING|COMPLETED|CANCELLED|FAILED|ACTIVE|PAUSED|ARCHIVED
    audience            jsonb NOT NULL,
    sms_template        text,
    email_subject       text,
    email_body          text,
    scheduled_at        timestamptz,
    trigger_config      jsonb,
    recipients_total    int NOT NULL DEFAULT 0,
    recipients_sent     int NOT NULL DEFAULT 0,
    recipients_failed   int NOT NULL DEFAULT 0,
    recipients_skipped  int NOT NULL DEFAULT 0,
    credits_spent       int NOT NULL DEFAULT 0,
    created_by          uuid NOT NULL,
    updated_by          uuid NOT NULL,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    started_at          timestamptz,
    completed_at        timestamptz
);
CREATE INDEX idx_campaigns_studio_status ON campaigns (studio_id, status);
CREATE INDEX idx_campaigns_due ON campaigns (status, scheduled_at);

CREATE TABLE campaign_recipients (
    id                      uuid PRIMARY KEY,
    campaign_id             uuid NOT NULL REFERENCES campaigns(id) ON DELETE CASCADE,
    studio_id               uuid NOT NULL,
    customer_id             uuid NOT NULL,
    channel                 varchar(10) NOT NULL,      -- SMS | EMAIL
    address                 varchar(255) NOT NULL,
    rendered_subject        text,
    rendered_body           text NOT NULL,
    status                  varchar(30) NOT NULL,
    error_message           text,
    trigger_source_visit_id uuid,
    scheduled_for           timestamptz NOT NULL,
    sent_at                 timestamptz,
    created_at              timestamptz NOT NULL
);
-- Jedna wizyta-trigger odpala wiadomość raz; dla ONE_TIME (visit NULL) unikalność per kampania+klient+kanał.
CREATE UNIQUE INDEX uq_campaign_recipient_auto
    ON campaign_recipients (campaign_id, customer_id, channel, trigger_source_visit_id)
    WHERE trigger_source_visit_id IS NOT NULL;
CREATE UNIQUE INDEX uq_campaign_recipient_onetime
    ON campaign_recipients (campaign_id, customer_id, channel)
    WHERE trigger_source_visit_id IS NULL;
CREATE INDEX idx_campaign_recipients_due ON campaign_recipients (status, scheduled_for);
CREATE INDEX idx_campaign_recipients_campaign ON campaign_recipients (campaign_id, status);
CREATE INDEX idx_campaign_recipients_customer ON campaign_recipients (studio_id, customer_id);

CREATE TABLE campaign_settings (
    studio_id            uuid PRIMARY KEY,
    quiet_hours_start    time NOT NULL DEFAULT '20:00',
    quiet_hours_end      time NOT NULL DEFAULT '08:00',
    frequency_cap_days   int  NOT NULL DEFAULT 7,
    sms_footer           text,
    email_footer         text,
    updated_at           timestamptz NOT NULL
);

-- Rezygnacja klienta z komunikacji marketingowej (STOP / link w e-mailu / ręcznie).
-- Osobna tabela append-only zamiast kolumny na customers — nie dotykamy agregatu klienta.
CREATE TABLE campaign_opt_outs (
    id           uuid PRIMARY KEY,
    studio_id    uuid NOT NULL,
    customer_id  uuid NOT NULL,
    source       varchar(20) NOT NULL,                 -- SMS_STOP | EMAIL_LINK | MANUAL
    opted_out_at timestamptz NOT NULL,
    CONSTRAINT uq_campaign_opt_out UNIQUE (studio_id, customer_id)
);
CREATE INDEX idx_campaign_opt_outs_customer ON campaign_opt_outs (studio_id, customer_id);

-- Migration: Visit Card (Karta Wizyty) + upselling feature
--
-- Production runs WITHOUT hibernate ddl-auto (application-docker-props has no
-- spring.jpa.hibernate.ddl-auto, so the default "none" applies) — schema changes
-- must be applied manually. This script is idempotent: safe to run repeatedly.
--
-- Apply with:
--   psql -U <user> -d <db> -f migrate-visit-card-upsell.sql

BEGIN;

-- ── studio_settings: new configuration columns ────────────────────────────────

-- Delivery channel for the Visit Card link (EMAIL / SMS / BOTH / NONE)
ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS visit_card_delivery_channel VARCHAR(10) NOT NULL DEFAULT 'EMAIL';

-- SMSAPI sender-name confirmation flag (referenced by OutboundCommunicationGateway)
ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS sms_api_name_confirmed BOOLEAN NOT NULL DEFAULT FALSE;

-- ── visit_card_tokens: public access tokens for the customer-facing card ─────

CREATE TABLE IF NOT EXISTS visit_card_tokens (
    id              UUID PRIMARY KEY,
    studio_id       UUID         NOT NULL,
    visit_id        UUID,
    appointment_id  UUID,
    token           VARCHAR(64)  NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Token anchored to a visit OR a reservation (older rows predate appointment_id)
ALTER TABLE visit_card_tokens
    ADD COLUMN IF NOT EXISTS appointment_id UUID;
ALTER TABLE visit_card_tokens
    ALTER COLUMN visit_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_visit_card_tokens_token
    ON visit_card_tokens (token);
CREATE UNIQUE INDEX IF NOT EXISTS idx_visit_card_tokens_visit
    ON visit_card_tokens (studio_id, visit_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_visit_card_tokens_appointment
    ON visit_card_tokens (studio_id, appointment_id);

-- ── visit_upsell_suggestions: suggested additional services per visit/reservation ──

CREATE TABLE IF NOT EXISTS visit_upsell_suggestions (
    id                 UUID PRIMARY KEY,
    studio_id          UUID          NOT NULL,
    visit_id           UUID,
    appointment_id     UUID,
    service_id         UUID          NOT NULL,
    service_name       VARCHAR(200)  NOT NULL,
    base_price_net     BIGINT        NOT NULL,
    vat_rate           INTEGER       NOT NULL,
    adjustment_type    VARCHAR(20)   NOT NULL,
    adjustment_value   BIGINT        NOT NULL,
    final_price_net    BIGINT        NOT NULL,
    final_price_gross  BIGINT        NOT NULL,
    note               VARCHAR(500),
    status             VARCHAR(20)   NOT NULL,
    service_item_id    UUID,
    created_by         UUID          NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_at       TIMESTAMP WITH TIME ZONE,
    confirmed_at       TIMESTAMP WITH TIME ZONE
);

ALTER TABLE visit_upsell_suggestions
    ADD COLUMN IF NOT EXISTS appointment_id UUID;
ALTER TABLE visit_upsell_suggestions
    ALTER COLUMN visit_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_upsell_suggestions_visit
    ON visit_upsell_suggestions (studio_id, visit_id);
CREATE INDEX IF NOT EXISTS idx_upsell_suggestions_appointment
    ON visit_upsell_suggestions (studio_id, appointment_id);
CREATE INDEX IF NOT EXISTS idx_upsell_suggestions_status
    ON visit_upsell_suggestions (visit_id, status);

-- ── upsell_reservation_consents: "Odpisz TAK" tracking for reservation cards ──

CREATE TABLE IF NOT EXISTS upsell_reservation_consents (
    id                   UUID PRIMARY KEY,
    studio_id            UUID         NOT NULL,
    appointment_id       UUID         NOT NULL,
    customer_phone       VARCHAR(30)  NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    external_message_id  VARCHAR(255),
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    responded_at         TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_upsell_res_consents_appointment
    ON upsell_reservation_consents (appointment_id);
CREATE INDEX IF NOT EXISTS idx_upsell_res_consents_phone_status
    ON upsell_reservation_consents (customer_phone, status);

COMMIT;

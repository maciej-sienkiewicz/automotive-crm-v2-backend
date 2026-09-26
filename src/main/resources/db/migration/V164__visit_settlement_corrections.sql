-- ═══════════════════════════════════════════════════════════════════════════════
-- Poprawka rozliczenia wizyty po wydaniu pojazdu.
--
-- Nic nie jest usuwane ani nadpisywane. Poprawka:
--   • zastępuje dotychczasowe dokumenty (superseded_at) i stawia obok nich korekty
--     storno (document_type = 'CORRECTION', kwoty ze znakiem, corrects_document_id),
--   • wystawia nowe dokumenty,
--   • zostawia wpis w visit_settlement_corrections: co było, co jest, kto i dlaczego.
-- settlement_correction_id wiąże wszystkie dokumenty jednej poprawki z jej wpisem.
--
-- Faktury KSeF: faktura niewysłana jest anulowana (ksef_status = 'CANCELLED',
-- numer zostaje zajęty — luka w numeracji jest dopuszczalna, powtórzenie nie);
-- faktura do paragonu niesie znacznik FP (invoice_to_receipt).
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE financial_documents
    ADD COLUMN IF NOT EXISTS corrects_document_id     UUID,
    ADD COLUMN IF NOT EXISTS settlement_correction_id UUID,
    ADD COLUMN IF NOT EXISTS superseded_at            TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_fin_docs_corrects_document
    ON financial_documents(corrects_document_id) WHERE corrects_document_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_fin_docs_settlement_correction
    ON financial_documents(settlement_correction_id) WHERE settlement_correction_id IS NOT NULL;

ALTER TABLE ksef_revenue_invoices
    ADD COLUMN IF NOT EXISTS invoice_to_receipt BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS cancelled_at       TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS cancelled_by       UUID;

CREATE TABLE IF NOT EXISTS visit_settlement_corrections (
    id                          UUID                     PRIMARY KEY,
    studio_id                   UUID                     NOT NULL,
    visit_id                    UUID                     NOT NULL,
    reason                      VARCHAR(500),
    total_net_before            BIGINT                   NOT NULL,
    total_gross_before          BIGINT                   NOT NULL,
    total_net_after             BIGINT                   NOT NULL,
    total_gross_after           BIGINT                   NOT NULL,
    payment_method_before       VARCHAR(30),
    payment_method_after        VARCHAR(30)              NOT NULL,
    document_type_before        VARCHAR(20),
    document_type_after         VARCHAR(20)              NOT NULL,
    ksef_action                 VARCHAR(30)              NOT NULL,
    cancelled_ksef_invoice_id   UUID,
    ksef_correction_invoice_id  UUID,
    new_ksef_invoice_id         UUID,
    ksef_error                  TEXT,
    steps                       TEXT                     NOT NULL,
    services_before             TEXT                     NOT NULL,
    services_after              TEXT                     NOT NULL,
    created_by                  UUID                     NOT NULL,
    created_by_name             VARCHAR(255),
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_visit_settlement_corrections_visit
    ON visit_settlement_corrections(studio_id, visit_id, created_at);

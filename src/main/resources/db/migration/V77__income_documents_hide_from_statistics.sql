-- Ukrywanie dokumentów przychodowych ze statystyk.
--
-- Odpowiednik statusu EXCLUDED znanego z dokumentów kosztowych (ksef_invoices.status):
-- dokument zostaje w bazie — ledger KSeF musi pozostać kompletny — ale wypada
-- ze statystyk, kafli podsumowania i domyślnej listy dokumentów.
--
-- Lista przychodów łączy dwa źródła, więc oba muszą znać ten sam znacznik.

ALTER TABLE ksef_revenue_invoices
    ADD COLUMN IF NOT EXISTS excluded_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS excluded_by UUID;

ALTER TABLE financial_documents
    ADD COLUMN IF NOT EXISTS excluded_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS excluded_by UUID;

-- Filtr „nieukryte" wchodzi do każdego zapytania listy i statystyk.
CREATE INDEX IF NOT EXISTS idx_ksef_revenue_invoices_studio_excluded
    ON ksef_revenue_invoices (studio_id, excluded_at);

CREATE INDEX IF NOT EXISTS idx_fin_docs_studio_excluded
    ON financial_documents (studio_id, excluded_at);

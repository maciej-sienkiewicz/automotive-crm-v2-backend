-- Odręczna notatka na dokumencie przychodowym z modułu finansowego.
--
-- Lista dokumentów przychodowych to unia dwóch źródeł: faktury z ledgera KSeF
-- (ksef_revenue_invoices) oraz paragony i dokumenty „inne" (financial_documents).
-- Faktury KSeF mają już kolumnę `note` (jak faktury kosztowe) — a financial_documents
-- miały tylko `description` (opis samego dokumentu, np. „Wizyta #12 — reszta kwoty").
-- To co innego niż notatka operatora, więc dokładamy dedykowaną kolumnę `note`,
-- spójnie z ksef_invoices i ksef_revenue_invoices.
--
-- Bez CHECK-ów, TEXT jak pozostałe notatki. Endpointy:
--   PATCH  /api/v1/finance/income-documents/{sourceKind}/{id}/note
--   DELETE /api/v1/finance/income-documents/{sourceKind}/{id}/note
-- kierują zapis do ksef_revenue_invoices.note (KSEF) albo tej kolumny (FINANCE).

ALTER TABLE financial_documents ADD COLUMN IF NOT EXISTS note TEXT;

COMMENT ON COLUMN financial_documents.note IS
    'Odręczna notatka operatora do dokumentu przychodowego. Odpowiednik ksef_invoices.note po stronie kosztów.';

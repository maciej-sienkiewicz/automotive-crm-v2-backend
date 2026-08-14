-- Powiązanie dokumentu finansowego z fakturą przychodową KSeF.
--
-- Faktura wystawiona przy zakończeniu wizyty tworzy DWA rekordy: dokument
-- w financial_documents (podstawa kasy, podsumowań i raportu płatności) oraz
-- fakturę w ksef_revenue_invoices (XML, status wysyłki, UPO). Zunifikowana lista
-- dokumentów przychodowych łączy oba źródła, więc bez jawnego powiązania ta sama
-- faktura pojawiłaby się na niej dwa razy.
--
-- Kolumna pozostaje NULL dla dokumentów bez odpowiednika w KSeF: paragonów,
-- dokumentów „inne" oraz faktur wystawionych przed wdrożeniem modułu KSeF
-- (te ostatnie są prezentowane jako faktury spoza KSeF — zgodnie ze stanem faktycznym).

ALTER TABLE financial_documents
    ADD COLUMN IF NOT EXISTS ksef_revenue_invoice_id UUID;

CREATE INDEX IF NOT EXISTS idx_fin_docs_ksef_revenue
    ON financial_documents(ksef_revenue_invoice_id)
    WHERE ksef_revenue_invoice_id IS NOT NULL;

-- Backfill par utworzonych zanim powiązanie istniało: dopasowanie po wizycie.
-- DISTINCT ON gwarantuje jednoznaczny wybór, gdyby do jednej wizyty istniała
-- więcej niż jedna faktura CRM (bierzemy najwcześniejszą).
UPDATE financial_documents fd
SET ksef_revenue_invoice_id = match.id
FROM (
    SELECT DISTINCT ON (studio_id, visit_id) id, studio_id, visit_id
    FROM ksef_revenue_invoices
    WHERE source = 'CRM'
      AND invoice_type = 'VAT'
      AND visit_id IS NOT NULL
    ORDER BY studio_id, visit_id, created_at
) AS match
WHERE fd.ksef_revenue_invoice_id IS NULL
  AND fd.document_type = 'INVOICE'
  AND fd.direction     = 'INCOME'
  AND fd.visit_id      = match.visit_id
  AND fd.studio_id     = match.studio_id;

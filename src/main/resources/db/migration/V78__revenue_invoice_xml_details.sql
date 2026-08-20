-- Szczegóły faktur przychodowych pobieranych z KSeF (pozycje, adresy, płatność).
--
-- Pull po SUBJECT1 czytał dotąd wyłącznie metadane, więc faktury wystawione poza CRM
-- miały pusty podgląd: żadnych pozycji, adresów ani formy płatności. Teraz pobieramy
-- też XML — a flaga pilnuje, które dokumenty jeszcze na niego czekają, bo limit żądań
-- KSeF nie pozwala pobrać wszystkiego w jednym przebiegu.

ALTER TABLE ksef_revenue_invoices
    ADD COLUMN IF NOT EXISTS details_synced BOOLEAN NOT NULL DEFAULT FALSE;

-- Faktury wystawione w CRM mają własny XML i pozycje od chwili wystawienia —
-- pobieranie ich z KSeF byłoby wydawaniem limitu na dane, które już mamy.
UPDATE ksef_revenue_invoices SET details_synced = TRUE WHERE source = 'CRM';

-- Kandydaci synchronizacji wstecznej wybierani są dokładnie po tej parze kolumn.
CREATE INDEX IF NOT EXISTS idx_ksef_revenue_invoices_details_backfill
    ON ksef_revenue_invoices (studio_id, details_synced)
    WHERE details_synced = FALSE;

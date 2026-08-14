-- Unikalność numeru dokumentu przychodowego w obrębie NIP-u sprzedawcy.
--
-- KSeF rejestruje faktury w kontekście NIP-u i odrzuca kodem 440 („Duplikat
-- faktury") dokument o numerze już zarejestrowanym pod tym NIP-em. Numeracja
-- była wcześniej liczona per studio, więc dwa studia dzielące jeden NIP
-- generowały tę samą serię FV/{rok}/{seq} i drugie wystawienie leciało w KSeF
-- na 440. Indeks poniżej zamyka tę dziurę na poziomie bazy, a numerację
-- (MAX+1 per NIP) obsługuje RevenueInvoiceNumberGenerator.
--
-- Dokumenty REJECTED są poza indeksem: nie istnieją w KSeF, więc ich numery
-- nie są tam zajęte i nie mogą blokować kolejnych wystawień. Generator i tak
-- liczy MAX ze WSZYSTKICH wierszy, więc numer odrzuconej faktury nie wraca
-- do obiegu (kluczowe przy 440: numer bywa zajęty w KSeF przez inny dokument).

-- Rozbrojenie ewentualnych kolizji sprzed tej zmiany. W praktyce dotyczy
-- wyłącznie środowisk testowych, gdzie kilka studiów dzieliło fikcyjny NIP —
-- na produkcji (jedno studio na NIP) zapytanie nie zmienia żadnego wiersza.
-- Zostaje najstarszy dokument; kolejnym dopisujemy sufiks, żeby nie zgubić
-- danych i nie wywrócić migracji.
WITH kolizje AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY seller_nip, invoice_number
               ORDER BY created_at, id
           ) AS pozycja
    FROM ksef_revenue_invoices
    WHERE ksef_status <> 'REJECTED'
      AND seller_nip IS NOT NULL
)
UPDATE ksef_revenue_invoices i
SET invoice_number = i.invoice_number || '-D' || k.pozycja
FROM kolizje k
WHERE i.id = k.id
  AND k.pozycja > 1;

CREATE UNIQUE INDEX IF NOT EXISTS ux_ksef_revenue_invoices_seller_number
    ON ksef_revenue_invoices (seller_nip, invoice_number)
    WHERE ksef_status <> 'REJECTED';

-- Wsparcie dla wyznaczania MAX+1 w serii danego NIP-u.
CREATE INDEX IF NOT EXISTS idx_ksef_rev_seller_number
    ON ksef_revenue_invoices (seller_nip, invoice_number);

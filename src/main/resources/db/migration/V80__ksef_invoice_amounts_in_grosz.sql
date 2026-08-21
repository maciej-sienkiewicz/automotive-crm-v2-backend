-- Kwoty faktur kosztowych: grosze w BIGINT zamiast złotych w DOUBLE PRECISION.
--
-- Strona przychodowa (ksef_revenue_invoices, financial_documents) od początku
-- trzyma grosze jako liczby całkowite; kosztowa została na liczbach
-- zmiennoprzecinkowych. Skutki były widoczne: FinanceReportingHandler mnożył
-- sumę kosztów przez 100, żeby sprowadzić ją do groszy, a każde porównanie
-- kwoty „co do grosza" na double jest z definicji zawodne — co blokowało
-- wykrywanie zdublowanych faktur po identycznej kwocie.
--
-- Konwersja idzie przez NUMERIC, nie wprost z double: rzutowanie
-- double precision -> numeric używa najkrótszej reprezentacji dziesiętnej
-- (45.45 pozostaje 45.45), więc mnożenie przez 100 daje dokładnie 4545,
-- a nie 4544.999999999999.
--
-- quantity nie jest kwotą, ale też nie ma powodu trzymać jej na double —
-- przechodzi na NUMERIC(14,3), tak jak ilość po stronie przychodowej.

ALTER TABLE ksef_invoices
    ALTER COLUMN net_amount   TYPE BIGINT USING ROUND(net_amount::numeric   * 100),
    ALTER COLUMN gross_amount TYPE BIGINT USING ROUND(gross_amount::numeric * 100),
    ALTER COLUMN vat_amount   TYPE BIGINT USING ROUND(vat_amount::numeric   * 100);

ALTER TABLE ksef_invoice_items
    ALTER COLUMN unit_price_net TYPE BIGINT USING ROUND(unit_price_net::numeric * 100),
    ALTER COLUMN net_value      TYPE BIGINT USING ROUND(net_value::numeric      * 100),
    ALTER COLUMN gross_value    TYPE BIGINT USING ROUND(gross_value::numeric    * 100),
    ALTER COLUMN quantity       TYPE NUMERIC(14, 3) USING ROUND(quantity::numeric, 3);

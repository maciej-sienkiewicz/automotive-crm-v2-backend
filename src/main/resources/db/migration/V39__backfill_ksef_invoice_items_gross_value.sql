-- Wypełnia gross_value dla pozycji faktur KSeF gdzie nie było P_11A w XML.
--
-- Stawki numeryczne (0, 5, 8, 23, ...): brutto = netto * (1 + stawka/100), zaokrąglone do 2 miejsc.
-- Stawki niepieniężne (zw, np, oo, ...): brutto = netto (brak VAT, gross = net).

UPDATE ksef_invoice_items
SET gross_value = CASE
    WHEN vat_rate ~ '^[0-9]+([.][0-9]+)?$'
        THEN ROUND((net_value * (1.0 + vat_rate::numeric / 100.0))::numeric, 2)
    ELSE ROUND(net_value::numeric, 2)
END
WHERE gross_value IS NULL
  AND net_value IS NOT NULL;

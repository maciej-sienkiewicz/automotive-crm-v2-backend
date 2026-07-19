-- services.base_price_gross: cena brutto przechowywana obok netto.
--
-- Dotychczas system przechowywał wyłącznie netto, a brutto liczył w locie:
-- gross = net + round(net * vat / 100). Ta funkcja NIE jest surjektywna na
-- siatce groszy (dla VAT 23% żadne całkowite netto nie daje np. 201,00 zł:
-- 163,41 zł -> 200,99 zł; 163,42 zł -> 201,01 zł). Cena brutto wpisana przez
-- użytkownika ginęła więc bezpowrotnie przy zapisie i "przeskakiwała" o grosz
-- w sugestiach/katalogu. Od teraz brutto jest przechowywane dokładnie tak,
-- jak wprowadził je użytkownik.
--
-- Backfill: dla istniejących usług brutto wyliczane dotychczasową metodą,
-- więc zachowanie dla starych danych się nie zmienia.

ALTER TABLE services
    ADD COLUMN IF NOT EXISTS base_price_gross BIGINT;

UPDATE services
SET base_price_gross = CASE
    WHEN vat_rate <= 0 THEN base_price_net
    ELSE base_price_net + ROUND(base_price_net * vat_rate / 100.0)
END
WHERE base_price_gross IS NULL;

ALTER TABLE services
    ALTER COLUMN base_price_gross SET NOT NULL;

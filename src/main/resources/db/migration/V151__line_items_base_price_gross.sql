-- Dokładne brutto ceny bazowej na pozycjach rezerwacji, wizyt i sugestii upsellu.
--
-- CLAUDE.md §1: kwota wpisana przez człowieka jest źródłem prawdy i nie wolno jej
-- odtwarzać z drugiej. Te trzy tabele trzymały tylko netto ceny bazowej — dokładne
-- brutto (wpisane od strony brutto albo z katalogu) było użyte raz, przy tworzeniu
-- pozycji, a każda późniejsza edycja, check-in czy akceptacja upsellu odtwarzała je
-- z netta. Przejście brutto → netto → brutto nie jest tożsamością: 190000 gr brutto
-- → 154472 gr netto → 190001 gr brutto, więc usługa za 1900,00 zł trafiała na paragon
-- i fakturę jako 1900,01 zł.
--
-- Semantyka kolumny (jak unit_price_gross przy ksef_revenue_invoice_items):
--   NOT NULL → dokładne brutto ceny bazowej: wpisane od strony brutto albo wzięte
--              z cennika (tam para netto/brutto jest zapisana wprost); to brutto
--              wygrywa z każdym przeliczeniem,
--   NULL     → nikt go nie ustalił; brutto wynika z netta.

ALTER TABLE visit_service_items      ADD COLUMN IF NOT EXISTS base_price_gross BIGINT;
ALTER TABLE appointment_line_items   ADD COLUMN IF NOT EXISTS base_price_gross BIGINT;
ALTER TABLE visit_upsell_suggestions ADD COLUMN IF NOT EXISTS base_price_gross BIGINT;

-- Wypełnienie tylko tam, gdzie dane DOWODZĄ ceny wpisanej od brutto: rabat zerowy
-- (cena końcowa = cena bazowa), a brutto różne od brutta wyliczonego z netta (a więc
-- nie mogło z niego powstać) i odległe od niego najwyżej o grosz zaokrąglenia „w stu".
-- Żadna istniejąca kwota się nie zmienia — kolumna utrwala brutto, które już jest
-- zapisane jako cena końcowa, żeby następna edycja pozycji go nie zgubiła.
--
-- Wiersze z brutto RÓWNYM wyliczonemu zostają z NULL: przeliczenie z netta da dla nich
-- identyczną kwotę, więc nic nie zjedzie. Wierszy z rabatem nie ruszamy — rabatów od
-- netta nie da się jednoznacznie odwrócić, a rabaty kwotowe rezerwacji liczyły się
-- wcześniej z odwróconym znakiem, więc cofanie ich z ceny końcowej dałoby złą bazę.

UPDATE visit_service_items
SET base_price_gross = final_price_gross
WHERE base_price_gross IS NULL
  AND adjustment_type IN ('PERCENT', 'FIXED_NET', 'FIXED_GROSS')
  AND adjustment_value = 0
  AND final_price_net = base_price_net
  AND final_price_gross <> base_price_net
        + CASE WHEN vat_rate <= 0 THEN 0 ELSE ROUND(base_price_net * vat_rate / 100.0) END
  AND ABS(final_price_gross - (base_price_net
        + CASE WHEN vat_rate <= 0 THEN 0 ELSE ROUND(base_price_net * vat_rate / 100.0) END)) <= 1;

UPDATE appointment_line_items
SET base_price_gross = final_price_gross
WHERE base_price_gross IS NULL
  AND adjustment_type IN ('PERCENT', 'FIXED_NET', 'FIXED_GROSS')
  AND adjustment_value = 0
  AND final_price_net = base_price_net
  AND final_price_gross <> base_price_net
        + CASE WHEN vat_rate <= 0 THEN 0 ELSE ROUND(base_price_net * vat_rate / 100.0) END
  AND ABS(final_price_gross - (base_price_net
        + CASE WHEN vat_rate <= 0 THEN 0 ELSE ROUND(base_price_net * vat_rate / 100.0) END)) <= 1;

UPDATE visit_upsell_suggestions
SET base_price_gross = final_price_gross
WHERE base_price_gross IS NULL
  AND adjustment_type IN ('PERCENT', 'FIXED_NET', 'FIXED_GROSS')
  AND adjustment_value = 0
  AND final_price_net = base_price_net
  AND final_price_gross <> base_price_net
        + CASE WHEN vat_rate <= 0 THEN 0 ELSE ROUND(base_price_net * vat_rate / 100.0) END
  AND ABS(final_price_gross - (base_price_net
        + CASE WHEN vat_rate <= 0 THEN 0 ELSE ROUND(base_price_net * vat_rate / 100.0) END)) <= 1;

COMMENT ON COLUMN visit_service_items.base_price_gross IS
    'Dokładne brutto ceny bazowej: wpisane od strony brutto albo z cennika; NULL = brutto liczy się z netta (CLAUDE.md §1).';
COMMENT ON COLUMN appointment_line_items.base_price_gross IS
    'Dokładne brutto ceny bazowej: wpisane od strony brutto albo z cennika; NULL = brutto liczy się z netta (CLAUDE.md §1).';
COMMENT ON COLUMN visit_upsell_suggestions.base_price_gross IS
    'Dokładne brutto ceny bazowej z katalogu; NULL w sugestiach sprzed V151 (CLAUDE.md §1).';

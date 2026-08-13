-- Metoda cenowa pozycji faktury przychodowej: NET (cena wpisana netto, VAT liczony
-- od netto) lub GROSS (cena wpisana brutto, VAT wzorem art. 106e ust. 7 ustawy o VAT:
-- brutto × stawka / (100 + stawka); netto = brutto − VAT).
--
-- Kwota wpisana przez użytkownika jest źródłem prawdy i nigdy nie jest przeliczana
-- wstecz — eliminuje to groszowe przeskoki (np. 500.00 → 499.99). Dla pozycji GROSS
-- XML FA(3) używa pól P_9B/P_11A (metoda brutto) zamiast P_9A/P_11.

ALTER TABLE ksef_revenue_invoice_items
    ADD COLUMN IF NOT EXISTS price_mode VARCHAR(5) NOT NULL DEFAULT 'NET';

ALTER TABLE ksef_revenue_invoice_items
    ADD COLUMN IF NOT EXISTS unit_price_gross BIGINT;

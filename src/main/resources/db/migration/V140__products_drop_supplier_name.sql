-- ── Moduł produktów: usunięcie pola „dostawca" ───────────────────────────────
--
-- Decyzja produktowa: formularz nowego produktu ma pytać o jedno — nazwę. Dostawca
-- nie był ani wymagany, ani używany w żadnym odczycie: leżał w nakładce studia jako
-- pole do wypełnienia, którego nikt nie czytał.
--
-- Tryb wdrożeniowy to Flyway + ddl-auto=validate (patrz V138): encja ProductStudioEntity
-- nie ma już tego pola, więc kolumna MUSI zniknąć tą migracją.

ALTER TABLE product_studio DROP COLUMN IF EXISTS supplier_name;

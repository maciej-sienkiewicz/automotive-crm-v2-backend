-- ── Moduł produktów: usunięcie pola „producent" ──────────────────────────────
--
-- Decyzja produktowa: marka w zupełności identyfikuje produkt na etykiecie, a osobne
-- pole „producent" było w praktyce kopią marki („jak marka, jeśli puste") i tylko
-- zaśmiecało formularz i kartę. Usuwamy je z UI, z encji i z bazy.
--
-- Tryb wdrożeniowy to Flyway + ddl-auto=validate (patrz V138): encja ProductEntity nie
-- ma już tego pola, więc kolumna MUSI zniknąć tą migracją, inaczej validate nie wywali
-- startu, ale kolumna NOT NULL bez wartości domyślnej zablokuje każdy INSERT.
--
-- Indeks pełnotekstowy odwołuje się do tej kolumny w wyrażeniu — Postgres i tak
-- usunąłby go razem z kolumną; robimy to jawnie i odtwarzamy bez producenta.

DROP INDEX IF EXISTS idx_products_search;

ALTER TABLE products DROP COLUMN IF EXISTS manufacturer_name;

CREATE INDEX IF NOT EXISTS idx_products_search ON products USING GIN (
    to_tsvector('simple', COALESCE(brand, '') || ' ' || COALESCE(name, ''))
);

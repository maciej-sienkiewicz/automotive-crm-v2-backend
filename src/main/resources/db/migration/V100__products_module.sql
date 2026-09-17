-- ── Moduł produktów ──────────────────────────────────────────────────────────
--
-- UWAGA: Flyway jest w tym repo WYŁĄCZONY (spring.flyway.enabled=false); schemat
-- powstaje z encji JPA (ddl-auto=update). Ten plik jest SKRYPTEM PRZEGLĄDOWYM
-- uruchamianym ręcznie i niesie wyłącznie to, czego Hibernate nie zrobi: indeksy
-- częściowe/funkcyjne, ograniczenia CHECK i COMMENT ON. Wzorzec: V99.

-- Katalog globalny — wiersze WSPÓŁDZIELONE między wszystkimi tenantami. Brak studio_id
-- jest zamierzony (patrz komentarz niżej i ProductEntity).
COMMENT ON TABLE products IS
    'Katalog globalny — wiersze WSPÓŁDZIELONE między wszystkimi tenantami. Brak studio_id jest zamierzony. Dane prywatne studia (cena, notatki, ocena, powiązania z wizytami) leżą w product_studio / product_notes / product_ratings / visit_products, każda z własnym studio_id.';

-- Jeden produkt = jeden GTIN. Cały mechanizm deduplikacji katalogu.
CREATE UNIQUE INDEX IF NOT EXISTS uq_products_gtin
    ON products (gtin) WHERE gtin IS NOT NULL;

-- Produkty bez kodu (chemia luzem) deduplikuje klucz naturalny: (marka, nazwa, wielkość).
CREATE UNIQUE INDEX IF NOT EXISTS uq_products_natural_key
    ON products (LOWER(brand), LOWER(name), package_size_value, package_size_unit)
    WHERE gtin IS NULL;

-- Wyszukiwanie pełnotekstowe po marce/nazwie/producencie (katalog rośnie liniowo
-- z liczbą tenantów, więc szybko robi się duży).
CREATE INDEX IF NOT EXISTS idx_products_search ON products USING GIN (
    to_tsvector('simple',
        COALESCE(brand, '') || ' ' || COALESCE(name, '') || ' ' || COALESCE(manufacturer_name, ''))
);

-- Para cena/kierunek w nakładce studia jest albo kompletna, albo pusta — połowiczna
-- kwota to grosz zgubiony na odczycie (CLAUDE.md §1). Odzwierciedla init w ProductStudioEntity.
ALTER TABLE product_studio DROP CONSTRAINT IF EXISTS chk_product_studio_price_complete;
ALTER TABLE product_studio ADD CONSTRAINT chk_product_studio_price_complete CHECK (
    (unit_price_net_cents IS NULL AND unit_price_gross_cents IS NULL
        AND price_entered_as IS NULL AND vat_rate IS NULL)
    OR
    (unit_price_net_cents IS NOT NULL AND unit_price_gross_cents IS NOT NULL
        AND price_entered_as IS NOT NULL AND vat_rate IS NOT NULL)
);

-- VatRate dopuszcza -1 (zwolniony) — zakup od podatnika zwolnionego z VAT jest realny.
ALTER TABLE product_studio DROP CONSTRAINT IF EXISTS chk_product_studio_vat;
ALTER TABLE product_studio ADD CONSTRAINT chk_product_studio_vat CHECK (
    vat_rate IS NULL OR vat_rate IN (-1, 0, 5, 8, 23)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_product_studio
    ON product_studio (studio_id, product_id);

-- Ocena: dokładnie jedna na produkt na studio; zakres 1..5.
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_ratings
    ON product_ratings (studio_id, product_id);
ALTER TABLE product_ratings DROP CONSTRAINT IF EXISTS chk_product_ratings_range;
ALTER TABLE product_ratings ADD CONSTRAINT chk_product_ratings_range CHECK (rating BETWEEN 1 AND 5);

-- Notatki: aktywne wg (studio, produkt), najnowsze pierwsze.
CREATE INDEX IF NOT EXISTS idx_product_notes_active
    ON product_notes (studio_id, product_id, created_at DESC)
    WHERE is_deleted = FALSE;

-- Relacja wizyta↔produkt. Świadomie BEZ klucza unikalnego (visit_id, product_id):
-- ten sam produkt może dopiąć dwoje ludzi; deduplikujemy przy odczycie.
CREATE INDEX IF NOT EXISTS idx_visit_products_visit ON visit_products (studio_id, visit_id);
CREATE INDEX IF NOT EXISTS idx_visit_products_product ON visit_products (studio_id, product_id, created_at DESC);

-- Kolejka propozycji korekt katalogu (panel moderacji: faza 5).
CREATE INDEX IF NOT EXISTS idx_product_proposals_pending
    ON product_correction_proposals (status, created_at) WHERE status = 'PENDING';

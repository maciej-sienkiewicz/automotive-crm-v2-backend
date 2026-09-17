-- ── Moduł produktów ──────────────────────────────────────────────────────────
--
-- Środowisko wdrożeniowe (application-docker-props): spring.flyway.enabled=true +
-- ddl-auto=validate. Flyway buduje schemat, Hibernate tylko go WERYFIKUJE — więc ta
-- migracja MUSI tworzyć tabele w całości, z kolumnami dokładnie jak w encjach JPA.
-- (Lokalnie Flyway jest wyłączony, a ddl-auto=update tworzy te same tabele z encji —
-- ta migracja się wtedy nie wykonuje.)
--
-- Katalog produktów (products) jest WSPÓŁDZIELONY między wszystkimi tenantami: brak
-- kolumny studio_id jest ZAMIERZONY (jedyny taki wyjątek w bazie). Dane prywatne studia
-- leżą w osobnych tabelach, każda z własnym studio_id.

CREATE TABLE IF NOT EXISTS products (
    id                    uuid PRIMARY KEY,
    gtin                  VARCHAR(14),
    name                  VARCHAR(200) NOT NULL,
    brand                 VARCHAR(120) NOT NULL,
    manufacturer_name     VARCHAR(200) NOT NULL,
    unit_of_measure       VARCHAR(10)  NOT NULL,
    package_size_value    NUMERIC(12,3) NOT NULL,
    package_size_unit     VARCHAR(10)  NOT NULL,
    package_height_mm     INTEGER,
    package_width_mm      INTEGER,
    package_depth_mm      INTEGER,
    description           TEXT,
    image_file_id         VARCHAR(500),
    source                VARCHAR(20)  NOT NULL,
    verification_level    VARCHAR(20)  NOT NULL,
    source_confidence     NUMERIC(4,3),
    source_payload        TEXT,
    resolved_at           timestamp with time zone,
    created_by_studio_id  uuid NOT NULL,
    created_by            uuid NOT NULL,
    created_at            timestamp with time zone NOT NULL DEFAULT now(),
    updated_by            uuid NOT NULL,
    updated_at            timestamp with time zone NOT NULL DEFAULT now(),
    is_withdrawn          BOOLEAN NOT NULL DEFAULT FALSE,
    withdrawn_reason      VARCHAR(300),
    CONSTRAINT chk_products_package_size_positive CHECK (package_size_value > 0)
);

COMMENT ON TABLE products IS
    'Katalog globalny — wiersze WSPÓŁDZIELONE między wszystkimi tenantami. Brak studio_id jest zamierzony. Dane prywatne studia (cena, notatki, ocena, powiązania z wizytami) leżą w product_studio / product_notes / product_ratings / visit_products, każda z własnym studio_id.';

-- Jeden produkt = jeden GTIN. Cały mechanizm deduplikacji katalogu.
CREATE UNIQUE INDEX IF NOT EXISTS uq_products_gtin ON products (gtin) WHERE gtin IS NOT NULL;
-- Produkty bez kodu (chemia luzem) deduplikuje klucz naturalny.
CREATE UNIQUE INDEX IF NOT EXISTS uq_products_natural_key
    ON products (LOWER(brand), LOWER(name), package_size_value, package_size_unit)
    WHERE gtin IS NULL;
CREATE INDEX IF NOT EXISTS idx_products_search ON products USING GIN (
    to_tsvector('simple',
        COALESCE(brand, '') || ' ' || COALESCE(name, '') || ' ' || COALESCE(manufacturer_name, ''))
);
CREATE INDEX IF NOT EXISTS idx_products_brand ON products (LOWER(brand));
CREATE INDEX IF NOT EXISTS idx_products_created_by_studio ON products (created_by_studio_id);


-- ── Nakładka studia (dane prywatne) ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS product_studio (
    id                        uuid PRIMARY KEY,
    studio_id                 uuid NOT NULL,
    product_id                uuid NOT NULL REFERENCES products (id),
    unit_price_net_cents      BIGINT,
    unit_price_gross_cents    BIGINT,
    price_entered_as          VARCHAR(5),
    vat_rate                  INTEGER,
    supplier_name             VARCHAR(200),
    internal_name             VARCHAR(200),
    internal_note             TEXT,
    is_favourite              BOOLEAN NOT NULL DEFAULT FALSE,
    is_hidden                 BOOLEAN NOT NULL DEFAULT FALSE,
    created_by                uuid NOT NULL,
    created_at                timestamp with time zone NOT NULL DEFAULT now(),
    updated_by                uuid NOT NULL,
    updated_at                timestamp with time zone NOT NULL DEFAULT now(),
    -- Para cena/kierunek jest albo kompletna, albo pusta — połowiczna kwota to grosz
    -- zgubiony na odczycie (CLAUDE.md §1). Odzwierciedla init w ProductStudioEntity.
    CONSTRAINT chk_product_studio_price_complete CHECK (
        (unit_price_net_cents IS NULL AND unit_price_gross_cents IS NULL
            AND price_entered_as IS NULL AND vat_rate IS NULL)
        OR
        (unit_price_net_cents IS NOT NULL AND unit_price_gross_cents IS NOT NULL
            AND price_entered_as IS NOT NULL AND vat_rate IS NOT NULL)
    ),
    -- VatRate dopuszcza -1 (zwolniony) — zakup od podatnika zwolnionego jest realny.
    CONSTRAINT chk_product_studio_vat CHECK (vat_rate IS NULL OR vat_rate IN (-1, 0, 5, 8, 23))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_studio ON product_studio (studio_id, product_id);
CREATE INDEX IF NOT EXISTS idx_product_studio_studio ON product_studio (studio_id);
CREATE INDEX IF NOT EXISTS idx_product_studio_product ON product_studio (product_id);


-- ── Notatki (nieskończenie wiele) ───────────────────────────────────────────
CREATE TABLE IF NOT EXISTS product_notes (
    id               uuid PRIMARY KEY,
    studio_id        uuid NOT NULL,
    product_id       uuid NOT NULL REFERENCES products (id),
    content          TEXT NOT NULL,
    visit_id         uuid,
    is_deleted       BOOLEAN NOT NULL DEFAULT FALSE,
    created_by       uuid NOT NULL,
    created_by_name  VARCHAR(200) NOT NULL,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    updated_by       uuid,
    updated_by_name  VARCHAR(200),
    updated_at       timestamp with time zone,
    deleted_by       uuid,
    deleted_at       timestamp with time zone
);
CREATE INDEX IF NOT EXISTS idx_product_notes_lookup ON product_notes (studio_id, product_id, created_at);


-- ── Ocena (dokładnie jedna na produkt na studio) ────────────────────────────
CREATE TABLE IF NOT EXISTS product_ratings (
    id             uuid PRIMARY KEY,
    studio_id      uuid NOT NULL,
    product_id     uuid NOT NULL REFERENCES products (id),
    rating         INTEGER NOT NULL,
    justification  VARCHAR(500),
    rated_by       uuid NOT NULL,
    rated_by_name  VARCHAR(200) NOT NULL,
    rated_at       timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT chk_product_ratings_range CHECK (rating BETWEEN 1 AND 5)
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_ratings ON product_ratings (studio_id, product_id);
CREATE INDEX IF NOT EXISTS idx_product_ratings_product ON product_ratings (studio_id, product_id);


-- ── Relacja wizyta ↔ produkt (czysto informacyjna) ──────────────────────────
-- Świadomie BEZ klucza unikalnego (studio_id, visit_id, product_id): ten sam produkt
-- może dopiąć dwoje ludzi; deduplikujemy przy odczycie.
CREATE TABLE IF NOT EXISTS visit_products (
    id               uuid PRIMARY KEY,
    studio_id        uuid NOT NULL,
    visit_id         uuid NOT NULL,
    product_id       uuid NOT NULL REFERENCES products (id),
    note             VARCHAR(500),
    created_by       uuid NOT NULL,
    created_by_name  VARCHAR(200) NOT NULL,
    created_at       timestamp with time zone NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_visit_products_visit ON visit_products (studio_id, visit_id);
CREATE INDEX IF NOT EXISTS idx_visit_products_product ON visit_products (studio_id, product_id, created_at);


-- ── Propozycje korekt katalogu (kolejka moderacji — panel w fazie 5) ────────
CREATE TABLE IF NOT EXISTS product_correction_proposals (
    id               uuid PRIMARY KEY,
    product_id       uuid NOT NULL REFERENCES products (id),
    studio_id        uuid NOT NULL,
    proposed_fields  TEXT NOT NULL,
    reason           VARCHAR(500),
    status           VARCHAR(20) NOT NULL,
    created_by       uuid NOT NULL,
    created_at       timestamp with time zone NOT NULL DEFAULT now(),
    reviewed_by      uuid,
    reviewed_at      timestamp with time zone,
    review_note      VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_product_proposals_pending
    ON product_correction_proposals (status, created_at) WHERE status = 'PENDING';

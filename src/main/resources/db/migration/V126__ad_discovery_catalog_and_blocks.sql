-- Odkrywanie obszaru: katalog fraz zamiast dowolnego tekstu + wykluczenia reklamodawców.
--
-- ── 1. Frazy przestają być wpisywane przez studio ────────────────────────────
--
-- Fraza jest kluczem WSPÓLNEGO cache, a każda unikalna fraza to osobne pobranie
-- z limitu 180 wywołań/godz. na całą instalację. Przy dowolnym tekście liczba
-- unikalnych fraz rosła z liczbą studiów. Katalog (AdDiscoveryCatalog) jest
-- zamknięty, więc budżet jest z góry znany, a wyniki dwóch studiów porównywalne.
--
-- Studio może z katalogu tylko ODZNACZAĆ pozycje — trzymamy więc listę wykluczeń,
-- nie listę fraz. Kolumna `phrases` znika, bo od teraz kłamałaby: nie ona decyduje,
-- co jest śledzone. Dotychczasowe wpisy i tak zostałyby zastąpione katalogiem.
ALTER TABLE meta_ad_location_trackings
    ADD COLUMN IF NOT EXISTS excluded_phrase_ids TEXT NOT NULL DEFAULT '';

ALTER TABLE meta_ad_location_trackings
    DROP COLUMN IF EXISTS phrases;

-- ── 2. Wykluczeni reklamodawcy ───────────────────────────────────────────────
--
-- Jedna tabela na dwa poziomy, bo reguła jest ta sama — różni się tylko zasięg:
--
--   studio_id IS NULL      → wykluczenie GLOBALNE, ustawione przez administratora
--                            aplikacji. Boty, hurtownie i profile zza granicy,
--                            które trafiają w polskie frazy, ale nie są niczyją
--                            konkurencją. Nie widzi ich nikt.
--   studio_id = <studio>   → czarna lista jednego studia. Firma może być legalnym
--                            reklamodawcą, a mimo to nie interesować tego studia.
--
-- Dwa CZĘŚCIOWE indeksy unikalne zamiast jednego złożonego: w Postgresie NULL nie
-- równa się NULL, więc zwykły UNIQUE (studio_id, page_id) przepuściłby dowolną
-- liczbę duplikatów globalnych.
CREATE TABLE IF NOT EXISTS meta_ad_advertiser_blocks (
    id                 UUID PRIMARY KEY,
    -- NULL = wykluczenie globalne (administrator aplikacji)
    studio_id          UUID,
    page_id            VARCHAR(40)  NOT NULL,
    -- Nazwa strony w chwili wykluczenia — żeby lista dała się przejrzeć po ludzku.
    page_name          VARCHAR(200),
    -- Po co wykluczony: „bot", „hurtownia", „profil zagraniczny", notatka studia.
    reason             VARCHAR(300),
    created_by_user_id UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ad_blocks_global_page
    ON meta_ad_advertiser_blocks (page_id)
    WHERE studio_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_ad_blocks_studio_page
    ON meta_ad_advertiser_blocks (studio_id, page_id)
    WHERE studio_id IS NOT NULL;

-- Odczyt tabeli wyników pyta o oba poziomy naraz (globalne + własne studia).
CREATE INDEX IF NOT EXISTS ix_ad_blocks_studio
    ON meta_ad_advertiser_blocks (studio_id);

-- Odkrywanie obszaru: „Kto jeszcze reklamuje się na frazy X, Y w rejonie Z".
--
-- Inaczej niż kalendarz reklam (meta_ad_snapshots), który śledzi z góry znane
-- strony obserwowanych profili, tu pytamy bibliotekę po TREŚCI reklamy i dopiero
-- u siebie filtrujemy po terenie. Dwie zasady trzymają to w ryzach przy jednym
-- wspólnym tokenie Meta (limit 200 wywołań/godz. na całą instalację):
--
--   1) cache jest kluczowany po frazie i WSPÓLNY dla wszystkich najemców —
--      reklamy dla „detailing" w Polsce są takie same, kto by nie pytał;
--   2) lokalizacja to filtr na ODCZYCIE, nie parametr pobrania.

-- ── Wpis frazy we wspólnym cache (jeden na frazę) ────────────────────────────
-- Metadane pobrania: świeżość (last_fetched_at rządzi TTL i on-demand), status
-- ostatniej próby i czy wynik był ucięty limitem stron (fraza zbyt ogólna).
CREATE TABLE IF NOT EXISTS meta_ad_discovery_phrases (
    id              UUID PRIMARY KEY,
    -- Znormalizowana fraza (małymi, pojedyncze spacje). Klucz dzielony między studiami.
    phrase          VARCHAR(200) NOT NULL,
    last_fetched_at TIMESTAMPTZ,
    -- OK | RATE_LIMITED | NOT_VERIFIED | ERROR
    last_status     VARCHAR(20)  NOT NULL DEFAULT 'OK',
    ad_count        INTEGER      NOT NULL DEFAULT 0,
    truncated       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ad_discovery_phrases_phrase
    ON meta_ad_discovery_phrases (phrase);

-- ── Aktywne reklamy w cache, przypięte do frazy (nie do studia) ──────────────
-- Bez historii: przy każdym odświeżeniu frazy podmieniamy jej wiersze na stan
-- na teraz. Ta sama reklama może wisieć pod kilkoma frazami, stąd klucz złożony.
CREATE TABLE IF NOT EXISTS meta_ad_discovery_ads (
    id               UUID PRIMARY KEY,
    phrase           VARCHAR(200) NOT NULL,
    ad_archive_id    VARCHAR(64)  NOT NULL,
    page_id          VARCHAR(40)  NOT NULL,
    page_name        VARCHAR(200),
    delivery_start   DATE         NOT NULL,
    -- NULL = emisja trwa. Odkrywanie pobiera tylko aktywne, więc zwykle NULL.
    delivery_stop    DATE,
    -- Zasięg w Polsce policzony z rozbicia wiek/płeć — liczba pokazywana w tabeli.
    reach_pl         INTEGER,
    -- trójki "nazwa;typ;wykluczona(0|1)" rozdzielone | (kodowanie MetaAdCodec)
    target_locations TEXT         NOT NULL DEFAULT '',
    snapshot_url     TEXT,
    fetched_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ad_discovery_ads_phrase_archive
    ON meta_ad_discovery_ads (phrase, ad_archive_id);

CREATE INDEX IF NOT EXISTS ix_ad_discovery_ads_phrase
    ON meta_ad_discovery_ads (phrase);

-- ── Trwałe śledzenie obszaru należące do jednego studia ──────────────────────
-- Gdy istnieje i jest aktywne, jego frazy wchodzą do cyklicznego odświeżania
-- (2×/dobę) — to różnica między „pokaż raz" a „śledź na stałe".
CREATE TABLE IF NOT EXISTS meta_ad_location_trackings (
    id                 UUID PRIMARY KEY,
    studio_id          UUID         NOT NULL,
    label              VARCHAR(120) NOT NULL,
    -- frazy wyszukiwania rozdzielone | (klucze do wspólnego cache)
    phrases            TEXT         NOT NULL DEFAULT '',
    -- miejscowości rejonu rozdzielone |, tak jak wpisał człowiek
    locations          TEXT         NOT NULL DEFAULT '',
    -- CITIES_ONLY | INCLUDE_BROADER
    match_mode         VARCHAR(20)  NOT NULL DEFAULT 'INCLUDE_BROADER',
    active             BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by_user_id UUID         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_ad_location_trackings_studio
    ON meta_ad_location_trackings (studio_id);

-- Źródło fraz do cyklicznego odświeżania: aktywne śledzenia wszystkich najemców.
CREATE INDEX IF NOT EXISTS ix_ad_location_trackings_active
    ON meta_ad_location_trackings (active);

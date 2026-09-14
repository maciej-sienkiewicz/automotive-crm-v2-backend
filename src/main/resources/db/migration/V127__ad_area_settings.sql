-- Odkrywanie obszaru: jedno ustawienie na studio zamiast listy nazwanych śledzeń.
--
-- Wiele śledzeń miało sens, dopóki studio wpisywało własne frazy: różne zestawy
-- fraz dla różnych rejonów. Po przejściu na wspólny katalog (V126) każde śledzenie
-- miało już DOKŁADNIE te same frazy i różniło się wyłącznie listą miejscowości —
-- a wtedy „Detailing Poznań" i „PPF Poznań" to nie dwa śledzenia, tylko dwa razy
-- to samo, z nazwą do wymyślenia i formularzem do wypełnienia.
--
-- Zostaje jedno pytanie, na które studio faktycznie odpowiada: gdzie patrzeć.
-- Nazwa, lista, wstrzymywanie i limit śledzeń znikają razem z powodem, dla
-- którego istniały.
CREATE TABLE IF NOT EXISTS meta_ad_area_settings (
    -- Studio jest kluczem: jedno ustawienie, nie zbiór.
    studio_id           UUID PRIMARY KEY,
    -- miejscowości rejonu rozdzielone |, tak jak wpisał człowiek
    locations           TEXT        NOT NULL DEFAULT '',
    -- CITIES_ONLY | INCLUDE_BROADER
    match_mode          VARCHAR(20) NOT NULL DEFAULT 'INCLUDE_BROADER',
    -- identyfikatory fraz katalogu odznaczonych przez studio, rozdzielone |
    excluded_phrase_ids TEXT        NOT NULL DEFAULT '',
    updated_by_user_id  UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Przeniesienie tego, co studia zdążyły ustawić: pierwszy rejon z brzegu wygrywa.
-- Wyboru „który z kilku" nie da się zrobić lepiej niż arbitralnie, a rejon jest
-- do poprawienia jednym kliknięciem — w przeciwieństwie do nazw, które i tak znikają.
INSERT INTO meta_ad_area_settings (studio_id, locations, match_mode, excluded_phrase_ids, created_at, updated_at)
SELECT DISTINCT ON (studio_id)
       studio_id, locations, match_mode, excluded_phrase_ids, created_at, updated_at
FROM meta_ad_location_trackings
WHERE active = TRUE
ORDER BY studio_id, updated_at DESC
ON CONFLICT (studio_id) DO NOTHING;

DROP TABLE IF EXISTS meta_ad_location_trackings;

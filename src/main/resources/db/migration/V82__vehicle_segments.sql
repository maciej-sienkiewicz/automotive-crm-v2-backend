-- Klasyfikacja modeli aut na segment wielkości i klasę rynkową.
--
-- Tabela jest GLOBALNA, nie per studio. „Toyota Corolla to kompakt marki popularnej"
-- jest faktem o świecie, a nie o kliencie danego studia — trzymanie tego per studio
-- oznaczałoby N zapytań do modelu językowego o dokładnie tę samą odpowiedź.
--
-- Wiersz powstaje raz, przy pierwszym leadzie z danym autem, i od tego momentu
-- odpowiada z bazy. Klucze trzymamy znormalizowane (lower + trim), bo marka wpada
-- tu z katalogu pojazdów, ale model bywa pusty albo różnie zapisany.
CREATE TABLE IF NOT EXISTS vehicle_segments (
    id            UUID PRIMARY KEY,
    brand_key     VARCHAR(120) NOT NULL,
    -- Pusty łańcuch, nie NULL: w indeksie unikalnym NULL nie jest równy NULL,
    -- więc „Toyota bez modelu" wpadałaby tu w kółko przy każdym leadzie.
    model_key     VARCHAR(160) NOT NULL DEFAULT '',
    brand         VARCHAR(120) NOT NULL,
    model         VARCHAR(160),
    -- A, B, C, D, E, F, SUV, VAN, SPORT, UNKNOWN
    size_segment  VARCHAR(20)  NOT NULL,
    -- BUDGET, MAINSTREAM, PREMIUM, LUXURY, UNKNOWN
    market_tier   VARCHAR(20)  NOT NULL,
    -- LLM albo MANUAL — żeby dało się odróżnić poprawkę człowieka od zgadnięcia modelu.
    source        VARCHAR(20)  NOT NULL DEFAULT 'LLM',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_vehicle_segments_key
    ON vehicle_segments (brand_key, model_key);

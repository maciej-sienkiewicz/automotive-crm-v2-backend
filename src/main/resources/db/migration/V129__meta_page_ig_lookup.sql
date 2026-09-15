-- Nazwa profilu na Instagramie odczytana ze strony reklamodawcy w Bibliotece Meta.
--
-- Osobna tabela, a NIE kolumna przy reklamach: uchwyt należy do STRONY na
-- Facebooku, nie do pojedynczej reklamy, i nie zmienia się praktycznie nigdy.
-- Raz ustalony zostaje na stałe — dzięki temu dwadzieścia kilka firm oznacza
-- dwadzieścia kilka uruchomień przeglądarki w całej historii instalacji,
-- a nie przy każdym odświeżeniu tabeli.
--
-- Tabela jest WSPÓLNA dla wszystkich studiów (brak studio_id): strona
-- „Auto Spa Poznań" ma ten sam profil niezależnie od tego, kto na nią patrzy.
-- To ta sama zasada, co przy wspólnym cache fraz.

CREATE TABLE IF NOT EXISTS meta_page_ig_lookup (
    page_id      VARCHAR(40)  PRIMARY KEY,

    -- Nazwa bez małpy. NULL znaczy „sprawdzono i nie ma", a nie „nie sprawdzano"
    -- — od tego drugiego jest brak wiersza.
    ig_username  VARCHAR(30),

    -- OK | EMPTY | BLOCKED | TIMEOUT | ERROR. Rozróżnienie EMPTY od reszty jest
    -- sednem alertowania: EMPTY to normalny wynik, pozostałe oznaczają, że nie
    -- udało się sprawdzić. Bez typu wyliczeniowego w bazie — zgodnie z regułą
    -- projektu, że wartości enuma żyją w kodzie.
    status       VARCHAR(20)  NOT NULL,

    checked_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    attempts     INT          NOT NULL DEFAULT 1
);

-- Wyszukiwanie „co odświeżyć": wiersze bez nazwy, od najdawniej sprawdzanych.
CREATE INDEX IF NOT EXISTS idx_meta_page_ig_lookup_recheck
    ON meta_page_ig_lookup (checked_at)
    WHERE ig_username IS NULL;

COMMENT ON TABLE meta_page_ig_lookup IS
    'Nazwa profilu IG strony reklamodawcy; wspólna dla studiów, ustalana raz.';

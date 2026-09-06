-- Reklamy konkurencji z Meta Ad Library.
--
-- Biblioteka reklam indeksuje reklamy po STRONIE NA FACEBOOKU, a my obserwujemy
-- profile na Instagramie i nie ma między nimi mostu w API. Dlatego powiązanie
-- jest ręczne (właściciel wskazuje stronę) i mieszka na globalnym profilu —
-- jeden profil to jedna strona, niezależnie od tego, ile studiów go obserwuje.
ALTER TABLE instagram_profiles
    ADD COLUMN IF NOT EXISTS facebook_page_id        VARCHAR(40),
    ADD COLUMN IF NOT EXISTS facebook_page_name      VARCHAR(200),
    ADD COLUMN IF NOT EXISTS facebook_page_linked_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_ig_profiles_fb_page
    ON instagram_profiles (facebook_page_id)
    WHERE facebook_page_id IS NOT NULL;

-- Migawka jednej reklamy. „Migawka", bo Meta nie ma historii: pokazuje stan na
-- teraz i po roku usuwa reklamę z biblioteki. Bez własnego zapisu nie wiemy ani
-- że reklama się skończyła, ani że kiedykolwiek istniała.
--
-- delivery_stop NULL = reklama nadal się emituje. Moment, w którym Meta wpisze
-- tam datę, jest jedynym sygnałem zakończenia — zapisujemy go w ended_detected_at,
-- bo to data NASZEGO odczytu, nie zawsze faktycznego wyłączenia.
--
-- Meta nie raportuje przerw w emisji, więc czas trwania to zawsze czas
-- kalendarzowy start → stop (albo dziś).
CREATE TABLE IF NOT EXISTS meta_ad_snapshots (
    id                UUID PRIMARY KEY,
    -- Identyfikator reklamy w bibliotece Meta (id z ads_archive).
    ad_archive_id     VARCHAR(64)  NOT NULL,
    page_id           VARCHAR(40)  NOT NULL,
    profile_id        UUID         NOT NULL,
    -- Pierwsza linia ad_creative_link_titles — służy WYŁĄCZNIE do odróżnienia
    -- reklam tego samego reklamodawcy, nie do czytania treści.
    title             TEXT,
    delivery_start    DATE         NOT NULL,
    delivery_stop     DATE,
    -- eu_total_reach: konta z całej UE. reach_pl policzone z rozbicia dla PL.
    reach_eu          INTEGER,
    reach_pl          INTEGER,
    -- FACEBOOK,INSTAGRAM,MESSENGER,AUDIENCE_NETWORK,THREADS
    platforms         VARCHAR(120) NOT NULL DEFAULT '',
    -- ustawienia reklamodawcy: "25-54", "All" / "Men" / "Women"
    target_ages       VARCHAR(20),
    target_gender     VARCHAR(12),
    -- trójki "nazwa;typ;wykluczona(0|1)" rozdzielone |
    target_locations  TEXT         NOT NULL DEFAULT '',
    payer             VARCHAR(200),
    beneficiary       VARCHAR(200),
    -- rozbicie zasięgu dla PL: "18-24;3900;1450;0" (przedział;M;K;nieokreślona) rozdzielone |
    reach_breakdown   TEXT         NOT NULL DEFAULT '',
    snapshot_url      TEXT,
    first_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Kiedy NASZ odczyt zobaczył, że delivery_stop przestało być NULL.
    ended_detected_at TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_meta_ad_snapshots_archive_id
    ON meta_ad_snapshots (ad_archive_id);

CREATE INDEX IF NOT EXISTS ix_meta_ad_snapshots_profile_start
    ON meta_ad_snapshots (profile_id, delivery_start);

-- Zdarzenia do Pulsu: „kto zakończył reklamę w ostatnich dniach".
CREATE INDEX IF NOT EXISTS ix_meta_ad_snapshots_ended
    ON meta_ad_snapshots (ended_detected_at)
    WHERE ended_detected_at IS NOT NULL;

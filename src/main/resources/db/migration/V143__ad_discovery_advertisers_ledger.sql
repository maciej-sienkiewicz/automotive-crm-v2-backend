-- Rejestr reklamodawców odkrywania: „kiedy pierwszy raz widzieliśmy tę firmę
-- i od kiedy w ogóle się reklamuje".
--
-- Cache odkrywania (meta_ad_discovery_ads) świadomie NIE prowadzi historii:
-- przy każdym odświeżeniu frazy podmieniamy jej wiersze na stan „teraz". To
-- wystarcza na pytanie „kto reklamuje się dziś", ale nie na „kto jest tu NOWY".
-- Firma, której stara kampania skończyła się wczoraj, a nowa ruszyła dziś,
-- wyglądałaby w samym cache jak debiutant - a to zwykła rotacja kreacji.
--
-- Stąd osobny, WSPÓLNY dla wszystkich najemców rejestr: jeden wiersz na stronę
-- reklamodawcy, nigdy nie kasowany. first_delivery_start to najwcześniejszy
-- start kampanii, jaką kiedykolwiek u tej strony widzieliśmy - po nim odróżniamy
-- „nowa firma" (zaczęła reklamować się niedawno) od „znana firma z nową kampanią".
CREATE TABLE IF NOT EXISTS meta_ad_discovery_advertisers (
    page_id              VARCHAR(40)  PRIMARY KEY,
    page_name            VARCHAR(200),
    -- Najwcześniejszy start kampanii tej strony, jaki kiedykolwiek widzieliśmy.
    first_delivery_start DATE         NOT NULL,
    first_seen_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_ad_discovery_advertisers_first_start
    ON meta_ad_discovery_advertisers (first_delivery_start);

-- Zasiew z obecnego cache: firmy, które reklamują się dziś, nie mogą po wdrożeniu
-- wszystkie naraz zaświecić jako „nowe". Bierzemy najwcześniejszy start ich
-- aktywnych kampanii - kto reklamuje się od marca, nie jest debiutantem we wrześniu.
INSERT INTO meta_ad_discovery_advertisers (page_id, page_name, first_delivery_start, first_seen_at, last_seen_at)
SELECT a.page_id,
       MAX(a.page_name),
       MIN(a.delivery_start),
       MIN(a.fetched_at),
       MAX(a.fetched_at)
FROM meta_ad_discovery_ads a
GROUP BY a.page_id
ON CONFLICT (page_id) DO NOTHING;

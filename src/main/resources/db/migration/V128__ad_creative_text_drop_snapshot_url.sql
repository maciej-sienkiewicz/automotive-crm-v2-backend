-- Reklama pokazywana u nas, a nie tylko linkowana do Biblioteki Meta.
--
-- 1. USUWAMY snapshot_url. Meta zwraca `ad_snapshot_url` w postaci
--    `https://www.facebook.com/ads/archive/render_ad/?id=<ID>&access_token=<TOKEN>`
--    — z tokenem dostępowym CAŁEJ instalacji w adresie. Trzymaliśmy to w bazie
--    i podawali przeglądarce każdego użytkownika CRM-a przy podglądzie reklamy
--    i w Pulsie. Link do reklamy składamy teraz z jej identyfikatora archiwum
--    (MetaAdLibraryUrl.forAd), który jest publiczny i pokazuje to samo.
--
--    DROP COLUMN kasuje też historyczne wartości — nie zostaje kopia tokena
--    w żadnym wierszu.
--
-- 2. DOKŁADAMY tekst kreacji. `ads_archive` nie oddaje grafiki reklamy w żadnym
--    polu, ale oddaje jej TREŚĆ. Zapisujemy ją, żeby podgląd kampanii pokazywał,
--    co reklama faktycznie mówi, zamiast odsyłać po to do Meta.

ALTER TABLE meta_ad_snapshots DROP COLUMN IF EXISTS snapshot_url;
ALTER TABLE meta_ad_discovery_ads DROP COLUMN IF EXISTS snapshot_url;

ALTER TABLE meta_ad_snapshots ADD COLUMN IF NOT EXISTS creative_body     TEXT;
ALTER TABLE meta_ad_snapshots ADD COLUMN IF NOT EXISTS link_description  VARCHAR(500);
ALTER TABLE meta_ad_snapshots ADD COLUMN IF NOT EXISTS link_caption      VARCHAR(300);

COMMENT ON COLUMN meta_ad_snapshots.creative_body IS
    'ad_creative_bodies[0] — treść reklamy. Grafiki Meta nie udostępnia.';

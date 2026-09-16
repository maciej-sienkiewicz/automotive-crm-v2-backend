-- Mapa uszkodzeń wizyty: PUNKTY, a nie tylko wygenerowany PDF.
--
-- Do tej pory z przyjęcia zostawał wyłącznie plik: `visits.damage_map_file_id`
-- wskazywał gotowy PDF, a współrzędne punktów i typ nadwozia ginęły razem z
-- pamięcią przeglądarki (przy przyjęciu z telefonu żyły w Redisie tyle, ile sesja
-- QR). Skutek: gdy w trakcie wizyty pojawiało się NOWE uszkodzenie, nie było od
-- czego zacząć — mapę trzeba było rysować od zera albo dopisywać uwagę w
-- komentarzu, której nie widać na dokumencie dla klienta.
--
-- Dlaczego osobna tabela, a nie kolumny na `visits`:
-- `SmsConsentService` (i każdy przyszły kod robiący to samo) zapisuje wizytę przez
-- `visitRepository.save(VisitEntity.fromDomain(visit))`, czyli przepisuje CAŁY
-- agregat z modelu domenowego. Kolumna na `visits`, której nie niesie `Visit`,
-- zostałaby przy takim zapisie po cichu wyzerowana — a punkty uszkodzeń są
-- dowodem w sporze o to, kto zrobił rysę. Własna tabela nie da się skasować
-- przypadkiem.
--
-- Dlaczego jsonb, a nie tabele punkt/zdjęcie/pociągnięcie:
-- to dokument czytany i zapisywany zawsze w całości (punkt → zdjęcia → pociągnięcia
-- pisaka). Nie filtrujemy po pojedynczym punkcie i nigdy nie będziemy — relacyjne
-- rozbicie dałoby trzy tabele i join, których nikt nie potrzebuje.

CREATE TABLE IF NOT EXISTS visit_damage_maps (
    visit_id            uuid PRIMARY KEY REFERENCES visits (id) ON DELETE CASCADE,
    studio_id           uuid NOT NULL,
    damage_points       jsonb NOT NULL DEFAULT '[]'::jsonb,
    vehicle_type        VARCHAR(30),
    -- Klucz S3 PDF-a odpowiadającego TYM punktom. Duplikuje `visits.damage_map_file_id`
    -- świadomie: tam jest „aktualny plik wizyty" (to jego doklejają maile), tutaj
    -- „plik wygenerowany z tej wersji punktów".
    document_s3_key     VARCHAR(500),
    revision            INTEGER NOT NULL DEFAULT 1,
    created_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_at          timestamp with time zone NOT NULL DEFAULT now(),
    updated_by          uuid,
    updated_by_name     VARCHAR(200)
);

CREATE INDEX IF NOT EXISTS idx_visit_damage_maps_studio ON visit_damage_maps (studio_id);

COMMENT ON TABLE visit_damage_maps IS
    'Punkty uszkodzeń wizyty (źródło prawdy dla mapy uszkodzeń). Jeden wiersz na wizytę; jsonb, bo dokument czytany i pisany w całości.';
COMMENT ON COLUMN visit_damage_maps.damage_points IS
    'Tablica punktów: [{id, x, y, note, photos:[{photoId, strokes:[{color, width, points:[{x,y}]}]}]}]. Współrzędne w procentach (0-100).';
COMMENT ON COLUMN visit_damage_maps.revision IS
    'Licznik zapisów mapy. 1 = mapa z przyjęcia; każda aktualizacja w trakcie wizyty podnosi o jeden.';

-- Bez backfillu i nie da się go zrobić: dla wizyt sprzed tej migracji punktów nigdy
-- nie zapisano, istnieje tylko PDF. Dla nich „Zaktualizuj uszkodzenia" startuje z
-- pustą mapą i mówi o tym wprost — zmyślanie punktów z obrazka byłoby gorsze niż
-- uczciwa pustka.

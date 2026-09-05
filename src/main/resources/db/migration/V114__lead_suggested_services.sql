-- „Sugerowane usługi": AI podsuwa pozycje z cennika na leadzie, człowiek je akceptuje
-- albo odrzuca. Sugestie mieszkają w TEJ SAMEJ tabeli co pozycje ręczne — odróżnia je
-- tylko status i źródło. Dzięki temu analityka, która czyta zdenormalizowane
-- leads.estimated_value, liczy potencjał (ręczne + sugerowane) bez nowej ścieżki.
--
-- status:  SUGGESTED (podsunięte przez AI, czeka na decyzję) | ACCEPTED (część wyceny).
--          Odrzucenie kasuje wiersz TWARDO (decyzja właściciela produktu), więc nie ma
--          wartości REJECTED — odrzucona sugestia po prostu znika.
-- source:  MANUAL (dodane ręcznie) | AI (podsunięte). Steruje badge „Sugerowane"
--          i zakresem destrukcyjnej pełnej podmiany (edytor rusza tylko NIE-sugestie).
-- price_source: skąd wzięła się cena — CATALOG (stała z cennika) | HISTORY (przepisana
--          z „Podobnego zlecenia") | MANUAL (wpisana ręcznie) | PENDING (usługa
--          z wyceną niestandardową bez trafienia w historii — czeka na kwotę).
--
-- Istniejące wiersze to wyłącznie ręczne, zaakceptowane pozycje — stąd defaulty.
-- Brak ograniczeń CHECK na tych kolumnach (świadomie): wartości pilnuje enum w kodzie,
-- a NoEnumCheckConstraintsTest tego wymaga.
ALTER TABLE lead_service_items ADD COLUMN IF NOT EXISTS status       VARCHAR(20) NOT NULL DEFAULT 'ACCEPTED';
ALTER TABLE lead_service_items ADD COLUMN IF NOT EXISTS source       VARCHAR(20) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE lead_service_items ADD COLUMN IF NOT EXISTS price_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL';

-- Sugestia usługi z wyceną niestandardową bez historii nie ma jeszcze ceny — wiersz
-- „czeka na kwotę". Do V114 kolumna była NOT NULL i taki wiersz nie mógł powstać.
ALTER TABLE lead_service_items ALTER COLUMN price_gross DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_lead_service_items_lead_status
    ON lead_service_items (lead_id, status);

-- Intencja leada zwracała dotąd tylko name_keys (do dopasowania „Podobnych zleceń").
-- Sugestie potrzebują KONKRETNYCH ID usług z cennika — ten sam wybór modelu, tylko
-- rozwiązany do identyfikatorów aktywnych, niepakietowych pozycji. Zapisujemy je, żeby
-- sugestie dało się przeliczyć z dziennika bez ponownego, stratnego mapowania nazwy na ID.
ALTER TABLE lead_service_intents ADD COLUMN IF NOT EXISTS matched_service_ids TEXT NOT NULL DEFAULT '';

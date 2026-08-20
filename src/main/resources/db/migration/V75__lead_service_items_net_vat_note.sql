-- ═══════════════════════════════════════════════════════════════════════════════
-- Pozycje usług na leadzie dostają netto, stawkę VAT i notatkę.
--
-- Wycena leada jest wykonywana tym samym komponentem co przyjęcie pojazdu
-- (EditableServicesTable), a ten pozwala policzyć cenę od netto, wybrać stawkę
-- i dopisać uwagę do pozycji. Bez tych trzech kolumn każdy powrót do wyceny
-- gubiłby to, co użytkownik przed chwilą wpisał — a notatka, która znika po
-- zapisaniu, jest gorsza niż brak notatek.
--
-- Kolumny są nullowalne: pozycje sprzed tej zmiany mają wyłącznie brutto i mają
-- prawo takie zostać. Brutto pozostaje wartością wiodącą — to ono sumuje się do
-- estimated_value i to ono było zamrożone w chwili wyceny.
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE lead_service_items
    ADD COLUMN IF NOT EXISTS price_net BIGINT,
    ADD COLUMN IF NOT EXISTS vat_rate  INTEGER,
    ADD COLUMN IF NOT EXISTS note      VARCHAR(500);

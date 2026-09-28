-- Odhaczanie wykonanych usług na widoku wizyty (np. tablet powieszony na hali).
--
-- Funkcja jest dla części studiów, więc domyślnie wyłączona. Odhaczenie to wyłącznie
-- znak dla ludzi: nie zmienia statusu wizyty, nie blokuje wydania i nie jest przez nic
-- sprawdzane. Kto i kiedy odhaczył, trafia do historii wizyty (audyt).
--
-- Osobna tabela zamiast kolumny w visit_service_items: pozycje usług zapisuje w całości
-- VisitEntity.fromDomain przy każdej zmianie wizyty, więc kolumna spoza modelu domeny
-- ginęłaby przy pierwszej edycji ceny.

ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS service_checklist_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS visit_service_checks (
    service_item_id  UUID         PRIMARY KEY,
    studio_id        UUID         NOT NULL,
    visit_id         UUID         NOT NULL,
    checked_at       TIMESTAMPTZ  NOT NULL,
    checked_by       UUID         NOT NULL,
    checked_by_name  VARCHAR(255)
);

CREATE INDEX IF NOT EXISTS idx_visit_service_checks_visit
    ON visit_service_checks (studio_id, visit_id);

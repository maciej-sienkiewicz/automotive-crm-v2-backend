-- Kategorie kosztów: flaga "Nie uwzględniaj w statystykach".
-- Używana dla kategorii typu "Własne" (np. wewnętrzne przelewy między spółkami),
-- których nie chcemy liczyć jako realny koszt w KPI ani na wykresach.
ALTER TABLE cost_categories
    ADD COLUMN IF NOT EXISTS exclude_from_stats BOOLEAN NOT NULL DEFAULT FALSE;

-- Wygląd logo w menu bocznym, policzony raz przy wgraniu (CompanyLogoProcessor).
--
-- Pasek menu jest ciemny. Dotąd KAŻDE logo dostawało białą podkładkę, żeby czarne
-- logotypy nie ginęły, ale logo z własnym tłem (biały napis na czarnym prostokącie)
-- wyglądało przez to jak znaczek w ramce. Podkładkę zostawiamy tylko tam, gdzie jest
-- potrzebna: przezroczyste tło + ciemny tusz. DEFAULT TRUE, bo logo wgrane przed tą
-- zmianą nie było analizowane i ma zachować dotychczasowy wygląd do ponownego wgrania.
ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS logo_needs_light_plate BOOLEAN NOT NULL DEFAULT TRUE;

-- Proporcje logo: poziomy logotyp zajmuje w nagłówku menu całą szerokość i zastępuje
-- nazwę firmy, sygnet stoi obok niej. Front zna proporcje przed wczytaniem obrazka,
-- więc nagłówek nie przeskakuje między układami.
ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS logo_aspect_ratio DOUBLE PRECISION;

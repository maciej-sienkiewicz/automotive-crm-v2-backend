-- Zgoda obowiązkowa vs. dobrowolna. Interfejs pokazywał tę różnicę („Obowiązkowa"
-- / „Opcjonalna") i wysyłał ją przy tworzeniu zgody, ale backend nie miał gdzie
-- jej zapisać — każda zgoda wracała jako dobrowolna.
--
-- Domyślnie false: zgoda marketingowa jest z definicji dobrowolna, a wszystkie
-- istniejące wiersze powstały w świecie bez tego rozróżnienia.
ALTER TABLE consent_definitions
    ADD COLUMN is_mandatory BOOLEAN NOT NULL DEFAULT FALSE;

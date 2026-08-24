-- Zgodność stanu wizualnego przy wydaniu pojazdu: odpowiedź „tak / nie" i uwagi,
-- zbierane od pracownika tuż przed wysłaniem protokołu do podpisu.
--
-- Trzymane przy protokole, nie przy wizycie: to oświadczenie dotyczy konkretnego
-- dokumentu, który klient podpisuje, i musi dać się odtworzyć razem z nim.
ALTER TABLE visit_protocols
    ADD COLUMN condition_match BOOLEAN,
    ADD COLUMN condition_remarks TEXT;

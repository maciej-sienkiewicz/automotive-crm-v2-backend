-- ═══════════════════════════════════════════════════════════════════════════════
-- Naprawa typu kolumny token_hash w signing_tablets.
--
-- V70 tworzyła ją jako CHAR(64), bo skrót SHA-256 ma zawsze dokładnie tyle znaków.
-- Postgres mapuje CHAR na typ `bpchar`, a encja (@Column(length = 64) na polu String)
-- oczekuje `varchar`. Walidacja schematu Hibernate'a porównuje typy dosłownie, więc
-- aplikacja nie wstawała w ogóle: „wrong column type encountered in column
-- [token_hash] (...) found [bpchar], but expecting [varchar(64)]".
--
-- Poprawiamy schemat, a nie encję: varchar jest tym, czego używa reszta tego
-- projektu, a CHAR w Postgresie nie daje nic poza dopełnianiem spacjami.
--
-- Konwersja jest bezpieczna dla danych: bpchar → varchar ucina końcowe spacje,
-- a skrót heksadecymalny SHA-256 wypełnia całe 64 znaki, więc żaden token nie
-- traci ani jednego znaku. Sparowane tablety działają dalej.
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE signing_tablets
    ALTER COLUMN token_hash TYPE VARCHAR(64);

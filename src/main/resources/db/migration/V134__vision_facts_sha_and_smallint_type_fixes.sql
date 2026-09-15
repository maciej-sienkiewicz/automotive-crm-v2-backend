-- ═══════════════════════════════════════════════════════════════════════════════
-- Typy kolumn z V130-V133 dociągnięte do tego, czego oczekują encje.
--
-- Wdrożenie wywalało się na starcie, jeszcze przed Tomcatem:
--   Schema-validation: wrong column type encountered in column [content_sha256]
--   in table [lead_attachment_facts]; found [bpchar (Types#CHAR)],
--   but expecting [varchar(64) (Types#VARCHAR)]
-- a za tym leciał kaskadowy UnsatisfiedDependencyException aż po piiAccessFilter —
-- mylący, bo z filtrem nie miał nic wspólnego: to tylko pierwszy bean, który
-- poprosił o EntityManagerFactory, której Hibernate nie zbudował.
--
-- CZEMU TO W OGÓLE PĘKA. Hibernate przy ddl-auto=validate porównuje typy przez
-- Dialect.equivalentTypes(kod encji, kod kolumny) i dopiero potem po nazwie typu.
-- Dla PostgreSQLDialect (sprawdzone na 6.4.4, nie z pamięci):
--   CHAR     vs VARCHAR → false   („varchar(64)" nie zaczyna się od „bpchar")
--   SMALLINT vs INTEGER → false   („integer" nie zaczyna się od „int2")
-- Czyli SQL musi trafić w typ encji co do rodziny, nie „w przybliżeniu".
--
-- 1. content_sha256 CHAR(64) → VARCHAR(64). Encja deklaruje @Column(length = 64)
--    na Stringu, co Hibernate mapuje na VARCHAR. Naprawiamy bazę, nie encję:
--    VARCHAR nie dopełnia spacjami, więc wyszukanie po haszu nie zależy od tego,
--    czy sterownik przyciął padding. To trzeci nawrót tej samej pary CHAR(64) +
--    length = 64 — V70 naprawiona przez V73, V91 przez V92, teraz V133.
--    Pilnuje tego od teraz NoNarrowColumnTypesTest.
--
-- 2. Osiem kolumn SMALLINT z V130-V133 → INTEGER. Wszystkie odpowiadające im pola
--    w encjach to Int, więc Hibernate oczekuje int4. Żadna z tych migracji nie
--    przeszła jeszcze walidacji na żadnym środowisku (weszły jednym commitem razem
--    z V133), więc bez tego pliku start wywalałby się dalej, kolumna po kolumnie.
--    Poszerzamy bazę zamiast zwężać encje do Short: dwa bajty na wiersz nie są warte
--    przeciągania Short przez sygnatury, porównania i DTO całego modułu podobnych
--    zleceń, a numery wersji reguł i liczniki i tak z czasem rosną.
--
-- ALTER TYPE przepisuje tabelę pod ACCESS EXCLUSIVE. Wszystkie te tabele to świeży
-- indeks podobnych zleceń (rzędy tysięcy wierszy na studio), a lead_attachment_facts
-- jest pusta — blokada jest chwilowa. Indeksy i klucz główny (studio_id,
-- content_sha256) Postgres przebuduje samodzielnie.
--
-- Migracji V130-V133 nie ruszamy: mają już wpisy w flyway_schema_history z sumami
-- kontrolnymi, a edycja wysypałaby start na „checksum mismatch".
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE lead_attachment_facts
    ALTER COLUMN content_sha256 TYPE VARCHAR(64),
    ALTER COLUMN spot_count     TYPE INTEGER;

ALTER TABLE lead_similar_matches
    ALTER COLUMN rules_version TYPE INTEGER,
    ALTER COLUMN sample_size   TYPE INTEGER;

ALTER TABLE lead_match_decisions
    ALTER COLUMN position      TYPE INTEGER,
    ALTER COLUMN rules_version TYPE INTEGER;

ALTER TABLE service_families
    ALTER COLUMN axes_version TYPE INTEGER;

ALTER TABLE visit_service_signatures
    ALTER COLUMN line_count TYPE INTEGER;

ALTER TABLE lead_service_intents
    ALTER COLUMN invalid_index_count TYPE INTEGER;

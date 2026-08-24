-- ═══════════════════════════════════════════════════════════════════════════════
-- push_devices.endpoint_hash: CHAR(64) -> VARCHAR(64)
--
-- V91 stworzyła kolumnę jako CHAR(64), wzorując się na signing_tablets.token_hash
-- z V70. Encja deklaruje ją jako @Column(length = 64), co Hibernate mapuje na
-- VARCHAR(64) — a produkcja startuje z ddl-auto=validate. Efekt: aplikacja nie
-- wstawała w ogóle:
--   Schema-validation: wrong column type encountered in column [endpoint_hash]
--   in table [push_devices]; found [bpchar], but expecting [varchar(64)]
--
-- Dlaczego V70 uszło na sucho, mimo identycznej pary CHAR(64) + length = 64:
-- baza produkcyjna powstała jeszcze pod ddl-auto=update, więc signing_tablets
-- istniała (jako varchar) ZANIM V70 się wykonała, a jej CREATE TABLE IF NOT
-- EXISTS był no-opem. push_devices to pierwsza tabela naprawdę zakładana przez
-- Flyway, więc jako pierwsza ujawniła tę rozjazd między SQL-em a encją.
--
-- Naprawiamy typ w bazie, nie w encji: VARCHAR nie dopełnia spacjami, więc
-- porównanie po haszu nie zależy od tego, czy sterownik przyciął padding.
-- Sama V91 zostaje nietknięta — jest już zapisana w flyway_schema_history
-- z sumą kontrolną, a jej edycja wysypałaby start na "checksum mismatch".
--
-- ALTER przepisuje tabelę, ale push_devices jest świeża i praktycznie pusta,
-- więc blokada jest chwilowa. Indeks unikalny na endpoint_hash Postgres
-- przebuduje samodzielnie.
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE push_devices
    ALTER COLUMN endpoint_hash TYPE VARCHAR(64);

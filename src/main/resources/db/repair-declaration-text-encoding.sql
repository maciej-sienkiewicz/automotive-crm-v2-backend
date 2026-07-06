-- Naprawa mojibake w kolumnie declaration_text tabeli signature_requests.
--
-- PRZYCZYNA: bajty UTF-8 polskich znaków zostały zapisane jako osobne znaki
-- Latin-1 (np. 'ś' [UTF-8: 0xC5 0x9B] → 'Å' [U+00C5] + znak kontrolny [U+009B]).
-- Następuje to gdy JVM lub sterownik JDBC odczytuje tekst z kodowaniem ISO-8859-1
-- zamiast UTF-8 (stare Spring Boot < 2.4 czytało application.properties jako Latin-1).
--
-- ALGORYTM NAPRAWY:
--   1. convert_to(text, 'LATIN1')  – bierze każdy znak Latin-1 i daje jego bajt
--      (U+00C5 → 0xC5, U+009B → 0x9B)
--   2. convert_from(bytes, 'UTF8') – traktuje te bajty jako UTF-8 i dekoduje
--      poprawnie (0xC5 0x9B → U+015B 'ś')
--
-- BEZPIECZEŃSTWO:
--   - Wiersze z poprawnym Unicode (znaki polskie > U+00FF, np. ś = U+015B)
--     nie pasują do warunku WHERE — nie są modyfikowane.
--   - Wiersze z czystym ASCII nie mają znaków Latin-1 extended — pomijane.
--   - W razie wątpliwości wykonaj najpierw SELECT poniżej bez UPDATE.
--
-- WYMAGANIA: PostgreSQL z bazą danych w kodowaniu UTF8 (sprawdź: SHOW server_encoding).

-- ── 1. Podgląd wierszy do naprawy ──────────────────────────────────────────
SELECT
    id,
    declaration_text                                              AS przed,
    convert_from(convert_to(declaration_text, 'LATIN1'), 'UTF8') AS po
FROM signature_requests
WHERE declaration_text ~ '[À-ÿ]'   -- zawiera znaki Latin-1 extended (U+00C0–U+00FF)
  AND declaration_text !~ '[Ā-ſ]'; -- ale NIE zawiera poprawnego Unicode Extended-A

-- ── 2. Naprawa ─────────────────────────────────────────────────────────────
UPDATE signature_requests
SET
    declaration_text = convert_from(convert_to(declaration_text, 'LATIN1'), 'UTF8'),
    updated_at       = NOW()
WHERE declaration_text ~ '[À-ÿ]'
  AND declaration_text !~ '[Ā-ſ]';

-- ── 3. Weryfikacja po naprawie ──────────────────────────────────────────────
SELECT COUNT(*) AS pozostalo_do_naprawy
FROM signature_requests
WHERE declaration_text ~ '[À-ÿ]'
  AND declaration_text !~ '[Ā-ſ]';
-- Wynik powinien być 0.

-- Import klientów: znormalizowany telefon do wykrywania duplikatów + sesje importu.
--
-- Dlaczego osobna kolumna, a nie porównywanie `phone`:
-- numery leżą w bazie tak, jak je ktoś wpisał — „+48 534 920 205", „534920205",
-- „534-920-205". Książka adresowa telefonu przyniesie jeszcze inny wariant tego samego
-- numeru, więc dopasowanie po surowym stringu wykryłoby znikomą część duplikatów i
-- import zrobiłby drugą kartotekę dla ludzi, których studio już ma.
--
-- Kolumna jest wyliczana w aplikacji ([CustomerEntity] @PrePersist/@PreUpdate), a nie
-- wyrażeniem GENERATED: reguła normalizacji ma jedno źródło prawdy w Kotlinie i musi być
-- ta sama dla importu, wyszukiwania i przyszłych integracji.

ALTER TABLE customers ADD COLUMN IF NOT EXISTS phone_e164 VARCHAR(20);

COMMENT ON COLUMN customers.phone_e164 IS
    'Telefon w formacie E.164 wyliczony z kolumny phone; NULL, gdy numeru nie da się znormalizować. Klucz dopasowania przy imporcie kontaktów.';

-- Backfill. Kolejność warunków musi odpowiadać normalizeToE164() w PhoneValidation.kt:
--   1) numer z jawnym plusem i sensowną długością        -> bez zmian
--   2) dziewięć cyfr (numer krajowy)                     -> +48…
--   3) prefiks 00 (międzynarodowy w zapisie telefonicznym) -> +…
--   4) 11 cyfr zaczynających się od 48                   -> +48…
-- Wszystko inne zostaje NULL-em: lepiej nie dopasować, niż skleić dwóch różnych klientów.
UPDATE customers
SET phone_e164 = CASE
        WHEN regexp_replace(phone, '[^0-9+]', '', 'g') ~ '^\+[0-9]{8,15}$'
            THEN regexp_replace(phone, '[^0-9+]', '', 'g')
        WHEN regexp_replace(phone, '[^0-9]', '', 'g') ~ '^[0-9]{9}$'
            THEN '+48' || regexp_replace(phone, '[^0-9]', '', 'g')
        WHEN regexp_replace(phone, '[^0-9]', '', 'g') ~ '^00[0-9]{8,15}$'
            THEN '+' || substring(regexp_replace(phone, '[^0-9]', '', 'g') from 3)
        WHEN regexp_replace(phone, '[^0-9]', '', 'g') ~ '^48[0-9]{9}$'
            THEN '+' || regexp_replace(phone, '[^0-9]', '', 'g')
        ELSE NULL
    END
WHERE phone IS NOT NULL AND phone <> '';

-- Indeks częściowy: NULL-e to numery nieznormalizowane, po których i tak nie szukamy.
CREATE INDEX IF NOT EXISTS idx_customers_studio_phone_e164
    ON customers (studio_id, phone_e164)
    WHERE phone_e164 IS NOT NULL;

-- E-mail jako drugi klucz dopasowania. Nowsze rekordy zapisują go małymi literami,
-- starsze niekoniecznie — indeks funkcyjny, żeby porównanie po LOWER() nie było seq scanem.
CREATE INDEX IF NOT EXISTS idx_customers_studio_email_lower
    ON customers (studio_id, LOWER(email))
    WHERE email IS NOT NULL;

-- ── Sesje importu ────────────────────────────────────────────────────────────
--
-- Sesja jest potrzebna, bo import bywa rozłożony na dwa urządzenia: kontakty przysyła
-- telefon (po zeskanowaniu kodu QR), a odznacza je i zatwierdza człowiek na komputerze.
-- Ładunek trafia więc do bazy, nie do pamięci przeglądarki.
--
-- `handoff_token` to sekret jednorazowy dla konkretnej sesji — celowo NIE jest to stały
-- `users.mobile_token`: zdjęcie ekranu z kodem QR nie może dawać komuś bezterminowego
-- dostępu do wysyłania danych do studia.
CREATE TABLE IF NOT EXISTS customer_import_sessions (
    id                UUID PRIMARY KEY,
    studio_id         UUID NOT NULL,
    created_by        UUID NOT NULL,
    -- ANDROID_PICKER | VCARD_FILE
    source            VARCHAR(20) NOT NULL,
    -- AWAITING_CONTACTS | READY | COMMITTED
    status            VARCHAR(20) NOT NULL,
    handoff_token     VARCHAR(64),
    contacts          JSONB NOT NULL DEFAULT '[]'::jsonb,
    device_label      VARCHAR(120),
    imported_count    INTEGER,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    committed_at      TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_customer_import_sessions_handoff
    ON customer_import_sessions (handoff_token)
    WHERE handoff_token IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_customer_import_sessions_studio
    ON customer_import_sessions (studio_id, created_at DESC);

-- Sesje są jednorazowe i krótkotrwałe; sprzątaniem zajmuje się zadanie po stronie
-- aplikacji, ale indeks po dacie wygaśnięcia trzyma ten przebieg tani.
CREATE INDEX IF NOT EXISTS idx_customer_import_sessions_expiry
    ON customer_import_sessions (expires_at);

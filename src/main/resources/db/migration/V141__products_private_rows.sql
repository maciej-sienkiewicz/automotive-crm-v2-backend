-- ── Produkty prywatne studia ─────────────────────────────────────────────────
--
-- Katalog `products` jest WSPÓŁDZIELONY między najemcami i to zostaje — ale tylko dla
-- wierszy, które da się jednoznacznie utożsamić, czyli mających POPRAWNY kod kreskowy.
-- GTIN jest kluczem tożsamości: dwa studia skanujące ten sam kod mają ten sam produkt.
--
-- Wpis bez kodu (albo z kodem, który nie przeszedł sumy kontrolnej) takiej tożsamości
-- NIE MA. „Pasta polerska" jednego studia to nie musi być „Pasta polerska" drugiego,
-- a literówka w nazwie rozlewała się wcześniej na wszystkich najemców. Takie wiersze
-- dostają właściciela i są widoczne wyłącznie dla niego.
--
--   owner_studio_id IS NULL      -> wiersz globalny, współdzielony (ma poprawny GTIN)
--   owner_studio_id = <studio>   -> wiersz prywatny tego studia
--
-- To NIE jest to samo co created_by_studio_id: tamto mówi, kto wiersz założył (audyt),
-- to mówi, kto go widzi (dostęp).

ALTER TABLE products ADD COLUMN IF NOT EXISTS owner_studio_id uuid;

COMMENT ON COLUMN products.owner_studio_id IS
    'NULL = wiersz globalny, współdzielony między najemcami (ma poprawny GTIN). Ustawione = wiersz PRYWATNY tego studia: bez kodu kreskowego albo z kodem niepoprawnym, więc bez jednoznacznej tożsamości. Każdy odczyt katalogu MUSI filtrować: owner_studio_id IS NULL OR owner_studio_id = <studio>.';

CREATE INDEX IF NOT EXISTS idx_products_owner ON products (owner_studio_id);

-- Klucz naturalny obowiązuje teraz W OBRĘBIE WŁAŚCICIELA: dwa studia mogą mieć własną
-- „Pastę polerską 1kg" bez kodu i to jest poprawne, a nie kolizja.
-- (Wiersze bez GTIN są od tej migracji zawsze prywatne, więc owner nigdy nie jest tu NULL.)
DROP INDEX IF EXISTS uq_products_natural_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_products_natural_key
    ON products (owner_studio_id, LOWER(brand), LOWER(name), package_size_value, package_size_unit)
    WHERE gtin IS NULL;

-- Wiersze bez kodu, które powstały PRZED tą zmianą, trafiły do puli globalnej przez
-- pomyłkę projektową. Oddajemy je studiu, które je założyło — nikt inny ich nie powinien
-- widzieć, a created_by_studio_id niesie dokładnie tę informację.
UPDATE products
   SET owner_studio_id = created_by_studio_id
 WHERE gtin IS NULL
   AND owner_studio_id IS NULL;

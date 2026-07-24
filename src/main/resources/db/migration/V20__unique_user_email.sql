-- E-mail użytkownika ma być unikalny globalnie, nie per studio.
-- Login (LoginHandler), reset hasła i CardDAV szukają po samym e-mailu
-- (findByEmail zmapowane na pojedynczy wynik), więc duplikat e-maila
-- w dwóch studiach wywalał NonUniqueResultException przy logowaniu.

-- 1. Normalizacja historycznych rekordów: wszystkie ścieżki zapisu robią
--    lowercase().trim(), ale starsze dane mogły powstać przed tą regułą.
UPDATE users SET email = lower(trim(email)) WHERE email <> lower(trim(email));

-- 2. Deduplikacja: dla każdego zduplikowanego e-maila zostaje najstarsze
--    konto; pozostałe są dezaktywowane, a ich e-mail dostaje sufiks
--    ".dup.<fragment-id>", żeby nie usuwać wierszy (mogą być wskazywane
--    przez inne tabele) i dało się je ręcznie odzyskać.
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY email ORDER BY created_at, id) AS rn
    FROM users
)
UPDATE users u
SET email     = left(u.email, 240) || '.dup.' || left(u.id::text, 8),
    is_active = false
FROM ranked r
WHERE u.id = r.id
  AND r.rn > 1;

-- 3. Podmiana zwykłego indeksu na unikalny (nazwa zgodna z encją UserEntity).
DROP INDEX IF EXISTS idx_users_email;
CREATE UNIQUE INDEX idx_users_email ON users (email);

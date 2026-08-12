-- role_permissions.permission: usunięcie CHECK constraintu wygenerowanego przez Hibernate.
--
-- Wzorzec błędu jak w V36: constraint powstał przy pierwszym utworzeniu schematu
-- (kolumna była wtedy @Enumerated) i zamroził ówczesną listę kodów uprawnień.
-- Katalog przeszedł od tego czasu restrukturyzacje v4/v5 (m.in. VISITS_SERVICE_PRICES_VIEW,
-- CUSTOMERS_VIEW, BATCH_ORDERS) i każdy zapis roli z nowym kodem kończy się błędem 23514.
--
-- W przeciwieństwie do V36 constraint NIE jest odtwarzany z nową listą:
-- 1. Kolumna celowo przechowuje surowe kody (nie @Enumerated), a kody legacy są
--    tłumaczone przy odczycie (Permission.fromStoredCode) — DB z założenia toleruje
--    wartości spoza aktualnego katalogu.
-- 2. Poprawność kodów z API wymusza kod aplikacji (Permission.fromApiCode — strict),
--    a zapisywany zbiór jest domykany po grafie zależności (PermissionHierarchy.close).
-- 3. Każda kolejna restrukturyzacja katalogu wymagałaby kolejnej migracji SQL —
--    dokładnie ta pułapka wywołała ten błąd.

ALTER TABLE role_permissions
    DROP CONSTRAINT IF EXISTS role_permissions_permission_check;

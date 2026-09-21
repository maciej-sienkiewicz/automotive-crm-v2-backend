-- Znacznik UID per FOLDER, a nie per konto.
--
-- IDEMPOTENTNA CELOWO (IF NOT EXISTS + ON CONFLICT). Ta migracja raz już rozminęła się
-- z wdrożonym kodem - encja pojechała na produkcję bez pliku SQL, bo plik był nieśledzony
-- przez gita, i aplikacja nie wstała na walidacji schematu. Ratunkiem było wtedy ręczne
-- wykonanie tego SQL-a na bazie; gdyby nie był idempotentny, następne wdrożenie
-- wywróciłoby się na "relation already exists" i trzeba byłoby grzebać we
-- flyway_schema_history.
--
-- Powód jest z produkcji: skrzynka biuro@carslab.pl ma trzy foldery wysłanych naraz
-- ("Elementy wysłane" - pusty, "Sent" - 106 wiadomości, "INBOX.Sent" - 67), bo zakładał
-- je każdy kolejny program pocztowy. CRM wybierał jeden i skanował tylko jego, więc
-- odpowiedzi wysyłane z innego klienta po cichu nie dopinały się do rozmów: skan mówił
-- "0 nowych", bo w TYM folderze faktycznie nic nowego nie było.
--
-- Skanujemy teraz wszystkie, a UID-y są numeracją folderu i między folderami nie
-- znaczą nic - stąd osobny wiersz dla każdego.
CREATE TABLE IF NOT EXISTS mail_folder_cursors (
    id           uuid PRIMARY KEY,
    account_id   uuid NOT NULL REFERENCES mail_accounts (id) ON DELETE CASCADE,
    folder_name  varchar(1000) NOT NULL,
    uid_validity bigint,
    last_uid     bigint NOT NULL DEFAULT 0,
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_mail_folder_cursors_account ON mail_folder_cursors (account_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_mail_folder_cursors_account_folder
    ON mail_folder_cursors (account_id, folder_name);

-- Przeniesienie znacznika, który już mamy.
--
-- Bez tego pierwszy przebieg po wdrożeniu potraktowałby rozpoznany folder jako nowy
-- i przeczytał go od UID 1: kilkaset wiadomości sprzed miesięcy wjechałoby do rozmów
-- jako świeże, z powiadomieniami. Duplikaty odsiałby Message-ID, ale hałas zostałby.
INSERT INTO mail_folder_cursors (id, account_id, folder_name, uid_validity, last_uid, updated_at)
SELECT gen_random_uuid(), id, sent_folder_name, sent_uid_validity, COALESCE(sent_last_uid, 0), now()
FROM mail_accounts
WHERE sent_folder_name IS NOT NULL
  AND sent_folder_name <> ''
ON CONFLICT (account_id, folder_name) DO NOTHING;

-- INBOX zostaje przy koncie: nazywa się INBOX u każdego dostawcy, jest dokładnie jeden
-- i nie ma tu czego wybierać. Kolumny sent_uid_validity / sent_last_uid zostają na
-- miejscu do czasu, aż wygaśnie potrzeba wycofania tej zmiany bez utraty znacznika.

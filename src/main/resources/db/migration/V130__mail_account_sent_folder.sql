-- Folder „Wysłane" nazywa się inaczej u każdego dostawcy i w każdym języku
-- („Sent", „Wysłane", „Elementy wysłane", „[Gmail]/Sent Mail", „INBOX.Sent"…).
-- Rozpoznajemy go generycznie (atrybut SPECIAL-USE \Sent, potem heurystyka nazw),
-- a wynik zapamiętujemy tutaj: wykrycie robi się raz, jest widoczne przy koncie
-- i daje się RĘCZNIE nadpisać, gdy skrzynka jest nietypowa. NULL = jeszcze nie
-- rozpoznano albo brak nadpisania.
ALTER TABLE mail_accounts ADD COLUMN IF NOT EXISTS sent_folder_name VARCHAR(1000);

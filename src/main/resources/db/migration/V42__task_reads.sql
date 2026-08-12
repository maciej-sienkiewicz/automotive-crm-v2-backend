-- Potwierdzenia odczytu zadań per użytkownik — zasilają licznik nieprzeczytanych
-- w zakładce „Powiadomienia" (samoobsługowy widok zadań dla ról bez dostępu do Tablicy).
-- Wiersz = „użytkownik widział to zadanie"; brak wiersza = nieprzeczytane.

CREATE TABLE IF NOT EXISTS task_reads (
    task_id uuid NOT NULL,
    user_id uuid NOT NULL,
    read_at timestamp with time zone NOT NULL,
    PRIMARY KEY (task_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_task_reads_user ON task_reads (user_id);

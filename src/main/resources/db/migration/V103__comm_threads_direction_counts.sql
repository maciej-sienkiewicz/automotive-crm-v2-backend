-- Foldery „Odebrane" i „Wysłane" w skrzynce CRM.
--
-- Wątek, w którym jest tylko nasza wiadomość (napisana od zera i bez odpowiedzi),
-- nie jest odebraną korespondencją i nie ma czego szukać w głównym widoku — należy
-- do folderu Wysłane. Ten sam wątek wraca do Odebranych, gdy klient odpisze.
-- Rozstrzyga o tym para liczników utrzymywana przy imporcie (CommsIngestService),
-- żeby lista nie liczyła kierunków wiadomości przy każdym odświeżeniu.
ALTER TABLE comm_threads
    ADD COLUMN IF NOT EXISTS inbound_count  INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS outbound_count INTEGER NOT NULL DEFAULT 0;

UPDATE comm_threads t
SET inbound_count  = COALESCE(c.inbound, 0),
    outbound_count = COALESCE(c.outbound, 0)
FROM (
    SELECT thread_id,
           SUM(CASE WHEN direction = 'INBOUND'  THEN 1 ELSE 0 END) AS inbound,
           SUM(CASE WHEN direction = 'OUTBOUND' THEN 1 ELSE 0 END) AS outbound
    FROM comm_messages
    GROUP BY thread_id
) c
WHERE c.thread_id = t.id;

-- Zamknięte podpowiedzi z paska na Tablicy. Wpis per użytkownik i klucz
-- podpowiedzi: snooze_until = NULL znaczy "nie pokazuj nigdy" (upselle),
-- data w przyszłości to drzemka. Klucze niosą okres (np. sufiks miesiąca),
-- więc nowa edycja podpowiedzi ma nowy klucz i nie dziedziczy zamknięcia.
CREATE TABLE IF NOT EXISTS dashboard_hint_dismissals (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    hint_key varchar(120) NOT NULL,
    snooze_until timestamp with time zone,
    created_at timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT uq_dashboard_hint_dismissals UNIQUE (user_id, hint_key)
);

CREATE INDEX IF NOT EXISTS idx_dashboard_hint_dismissals_user
    ON dashboard_hint_dismissals (user_id);

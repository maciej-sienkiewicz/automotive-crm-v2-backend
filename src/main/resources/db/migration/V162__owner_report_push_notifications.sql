-- ═══════════════════════════════════════════════════════════════════════════════
-- Raport właściciela: powiadomienie push „Dostępny nowy raport" zamiast maila.
--
-- Raport z załącznikiem mailem zastępuje powiadomienie na telefon, ustawiane
-- przez każdego użytkownika z dostępem do raportu (Ustawienia → Powiadomienia):
-- wyłączone, co tydzień, co 2 tygodnie albo co miesiąc. PDF generuje się na
-- żądanie w Statystykach, więc nie ma czego wysyłać w załączniku.
--
-- owner_report_settings i owner_report_dispatches z V161 służyły wyłącznie
-- wysyłce mailem — usuwamy je.
--
-- owner_report_notification_dispatches: jeden wiersz na powiadomienie
-- (użytkownik, okres). Unikalność jest blokadą między instancjami — push idzie
-- tylko z tej, której wstawienie się udało.
-- ═══════════════════════════════════════════════════════════════════════════════

DROP TABLE IF EXISTS owner_report_settings;
DROP TABLE IF EXISTS owner_report_dispatches;

CREATE TABLE IF NOT EXISTS owner_report_notifications (
    user_id    UUID PRIMARY KEY,
    studio_id  UUID NOT NULL,
    frequency  VARCHAR(20) NOT NULL DEFAULT 'OFF',
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_owner_report_notifications_studio
    ON owner_report_notifications (studio_id);

CREATE TABLE IF NOT EXISTS owner_report_notification_dispatches (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL,
    studio_id    UUID NOT NULL,
    period_start DATE NOT NULL,
    period_end   DATE NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_owner_report_notification_dispatch UNIQUE (user_id, period_start, period_end)
);

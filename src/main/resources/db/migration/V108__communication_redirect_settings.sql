-- Przekierowanie komunikacji: „Przekieruj każdą wiadomość mailową i SMS na moje dane".
--
-- Studio, które właśnie napisało szablony, chce zobaczyć na własnym telefonie i skrzynce,
-- co dostałby klient przy prawdziwych rezerwacjach — przez dzień, tydzień, ile trzeba —
-- i dopiero potem wyłączyć przełącznik. Do tego czasu klienci nie dostają nic.
--
-- Jeden wiersz na studio; brak wiersza = przełącznik nigdy nie dotknięty = wysyłka do klientów.
-- Podmiana odbiorcy dzieje się w jednym miejscu: OutboundCommunicationGateway.

CREATE TABLE IF NOT EXISTS communication_redirect_settings (
    id                 UUID PRIMARY KEY,
    studio_id          UUID NOT NULL UNIQUE,
    enabled            BOOLEAN NOT NULL DEFAULT FALSE,
    phone              VARCHAR(20)  NOT NULL DEFAULT '',
    email              VARCHAR(254) NOT NULL DEFAULT '',
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_by_user_id UUID
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_communication_redirect_settings_studio_id
    ON communication_redirect_settings (studio_id);

COMMENT ON TABLE communication_redirect_settings IS
    'Per studio: gdy enabled, każdy SMS i e-mail do klienta trafia na phone/email studia zamiast do klienta.';

-- Podziękowanie po wizycie planowane przy wydaniu pojazdu.
--
-- Dlaczego osobna tabela, a nie kolumna przy wizycie:
-- to nie jest atrybut wizyty, tylko wiadomość z własnym cyklem życia — czeka, wychodzi
-- albo się nie udaje, i musi to po sobie zostawić (numer, treść, id u operatora, błąd).
--
-- Wiersz powstaje TAKŻE wtedy, gdy studio podziękowania nie chce (status SKIPPED).
-- To on wyłącza automat POST_VISIT dla tej rezerwacji: bez niego odznaczenie pola przy
-- wydaniu nic by nie dało, bo automat liczy własne opóźnienie od odbioru pojazdu.
-- Stąd też brak NOT NULL na numerze, treści i terminie: decyzja odmowna nie ma czego
-- w sobie zamrozić.

CREATE TABLE IF NOT EXISTS scheduled_thank_you_sms (
    id                  UUID PRIMARY KEY,
    studio_id           UUID NOT NULL,
    visit_id            UUID NOT NULL,
    -- Klucz, po którym automat rozpoznaje „ta rezerwacja ma już swoją decyzję".
    appointment_id      UUID NOT NULL,
    customer_id         UUID NOT NULL,
    -- Numer i treść rozstrzygnięte w chwili planowania: zmiana szablonu w ustawieniach
    -- ani numeru w kartotece nie przepisuje wiadomości, na którą ktoś się już zgodził.
    phone_number        VARCHAR(20),
    message_content     TEXT,
    scheduled_for       TIMESTAMPTZ,
    status              VARCHAR(20) NOT NULL,
    sent_at             TIMESTAMPTZ,
    external_message_id VARCHAR(255),
    error_message       TEXT,
    created_by          UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Jedna decyzja na rezerwację. Drugie wydanie tej samej wizyty nie ma prawa dołożyć
-- drugiego podziękowania, a automat pyta o istnienie wiersza właśnie tym kluczem.
CREATE UNIQUE INDEX IF NOT EXISTS idx_thank_you_sms_appointment
    ON scheduled_thank_you_sms (appointment_id);

CREATE INDEX IF NOT EXISTS idx_thank_you_sms_visit
    ON scheduled_thank_you_sms (visit_id);

-- Kolejka wysyłkowa: co minutę pytamy o PENDING z terminem w przeszłości.
CREATE INDEX IF NOT EXISTS idx_thank_you_sms_status_scheduled
    ON scheduled_thank_you_sms (status, scheduled_for);

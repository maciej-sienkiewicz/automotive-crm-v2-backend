-- „Oddzwoniłem" — telefon do klienta odnotowany na leadzie.
--
-- Rozmowa telefoniczna nie zostawia po sobie śladu, który system mógłby przeczytać:
-- w wątku nie pojawia się mail, więc lead z odbytą rozmową wyglądał identycznie jak
-- lead, o którym nikt nie pamiętał. Skutki były dwa i oba mylące — oś czasu milczała
-- o najważniejszym kontakcie, a „czeka na naszą odpowiedź" świeciło się mimo że
-- odpowiedź padła, tylko głosem.
--
-- Osobna tabela, a nie notatka z doklejoną flagą: notatka jest tekstem, który ktoś
-- napisał, a to jest FAKT kontaktu — ma własną datę, autora i skutki (stempel czasu
-- pierwszej reakcji, przejście „Nowy" → „W kontakcie"). Notatka bywa przy nim
-- opcjonalnym komentarzem, nie odwrotnie.
CREATE TABLE IF NOT EXISTS lead_callbacks (
    id             UUID PRIMARY KEY,
    studio_id      UUID NOT NULL,
    lead_id        UUID NOT NULL,
    -- Opcjonalna: „prosił o kontakt po 15", „wyśle zdjęcia w weekend".
    note           VARCHAR(1000),
    called_by      UUID,
    called_by_name VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Oś czasu leada czyta je w kolejności chronologicznej.
CREATE INDEX IF NOT EXISTS ix_lead_callbacks_lead
    ON lead_callbacks (lead_id, created_at);

-- Załączniki wiadomości, z której powstał lead.
--
-- Klient pisze „proszę o wycenę" i dokłada zdjęcia lakieru albo skan dowodu. Wiadomość
-- szła przez automatyczne rozpoznanie leadów, lead powstawał — a pliki zostawały
-- wyłącznie w skrzynce. W „Przebiegu sprawy" nie było po nich śladu, więc handlowiec
-- musiał wiedzieć, że ma ich szukać w poczcie, i umieć trafić w ten wątek.
--
-- Trzymamy WSKAZANIE, nie kopię bajtów: plik ma jedno miejsce w bazie (comm_attachments),
-- więc nie ma dwóch wersji tego samego zdjęcia ani podwójnego rachunku za magazyn.
-- Metadane są zdenormalizowane, żeby lista załączników leada nie musiała dotykać
-- kolumny z zawartością.
--
-- Osobna tabela, a nie odczyt przez wątek leada: lead z formularza WWW świadomie nie ma
-- wątku (patrz FormMailLeadProcessor — wątek należy do robota, nie do klienta), a to
-- właśnie w zgłoszeniach z formularza zdjęcia trafiają się najczęściej.

CREATE TABLE IF NOT EXISTS lead_attachments (
    id            UUID PRIMARY KEY,
    studio_id     UUID NOT NULL,
    lead_id       UUID NOT NULL,
    -- Wiadomość źródłowa: po niej oś czasu wiesza plik pod właściwym wpisem.
    message_id    UUID NOT NULL,
    attachment_id UUID NOT NULL,
    file_name     VARCHAR(500) NOT NULL,
    content_type  VARCHAR(255) NOT NULL,
    size_bytes    BIGINT NOT NULL,
    -- Czas nadejścia wiadomości, nie czas podpięcia: oś czasu układa zdarzenia
    -- w kolejności, w jakiej wydarzyły się dla klienta.
    received_at   TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Ten sam plik nie może wisieć na leadzie dwa razy — powtórne oznaczenie wątku
-- jako leada ani ponowny przebieg klasyfikacji nie dokładają duplikatów.
CREATE UNIQUE INDEX IF NOT EXISTS idx_lead_attachments_unique
    ON lead_attachments (lead_id, attachment_id);

CREATE INDEX IF NOT EXISTS idx_lead_attachments_lead
    ON lead_attachments (lead_id, received_at);

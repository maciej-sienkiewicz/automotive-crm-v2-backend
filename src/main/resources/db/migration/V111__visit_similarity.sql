-- „Podobne zlecenia" przy leadzie: co już robiliśmy dla takiego auta i takiej usługi.
--
-- Handlowiec odpowiadający na „ile za oklejenie Panamery?" ma w tej chwili dwie drogi:
-- zapytać kogoś z pamięcią albo przeklikać historię wizyt ręcznie. Odpowiedź istnieje
-- w bazie od lat, tylko nie da się do niej dojść z poziomu leada.
--
-- Sama tabela wektorowa (visit_similarity_vectors) NIE powstaje tutaj: tworzy ją
-- Spring AI przez initializeSchema, tak samo jak instagram_post_vectors. Schemat
-- należy do biblioteki i przy jej podniesieniu potrafi się zmienić — opisanie go
-- ręcznie w migracji znaczyłoby utrzymywanie kopii cudzego kontraktu.

-- Co i kiedy trafiło do indeksu wektorowego.
--
-- Indeks jest modelem odczytowym, więc pilnuje go uzgadniacz chodzący po
-- `visits.updated_at`, a nie wywołania powtykane w handlery wizyt. Odcisk treści
-- rozstrzyga, czy wizyta wymaga PONOWNEGO osadzenia: zmiana notatki technicznej nie
-- rusza opisu, po którym szukamy, więc nie ma powodu płacić za nowy wektor.
CREATE TABLE IF NOT EXISTS visit_index_state (
    visit_id     UUID PRIMARY KEY,
    studio_id    UUID NOT NULL,
    -- Skrót opisu, który poszedł do osadzenia.
    fingerprint  VARCHAR(64) NOT NULL,
    indexed_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Kopia visits.updated_at z chwili indeksowania — po niej uzgadniacz wybiera zaległe.
    source_updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS ix_visit_index_state_studio
    ON visit_index_state (studio_id);

-- Ocena trafności dopasowania: „to zlecenie faktycznie jest podobne" albo nie.
--
-- Dwa zastosowania i żadnego więcej. Po pierwsze TWARDE WYKLUCZENIE: odrzucona para
-- nigdy nie wraca przy tym leadzie, bo pokazanie jej drugi raz po tym, jak człowiek
-- powiedział „nie", jest gorsze niż niepokazanie niczego. Po drugie MATERIAŁ DO
-- STROJENIA progu i promptu — po kilkuset ocenach widać w danych, czy dobór trafia.
--
-- Świadomie NIE jest to pętla ucząca retrieval: dostrajanie osadzeń na podstawie
-- kciuków to osobny projekt, a nie efekt uboczny tej tabeli.
CREATE TABLE IF NOT EXISTS visit_match_feedback (
    id              UUID PRIMARY KEY,
    studio_id       UUID NOT NULL,
    lead_id         UUID NOT NULL,
    visit_id        UUID NOT NULL,
    -- RELEVANT | IRRELEVANT
    verdict         VARCHAR(20) NOT NULL,
    created_by      UUID,
    created_by_name VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Jedna ocena na parę: kolejne kliknięcie zmienia zdanie, nie dokłada wiersza.
CREATE UNIQUE INDEX IF NOT EXISTS ux_visit_match_feedback_pair
    ON visit_match_feedback (lead_id, visit_id);

CREATE INDEX IF NOT EXISTS ix_visit_match_feedback_studio
    ON visit_match_feedback (studio_id, created_at DESC);

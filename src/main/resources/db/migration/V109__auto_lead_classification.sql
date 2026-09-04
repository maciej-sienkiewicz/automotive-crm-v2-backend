-- Automatyczne tworzenie leadów: klasyfikacja przychodzącej poczty przez LLM.
--
-- Do tej pory lead z maila powstawał tylko wtedy, gdy ktoś kliknął „Oznacz jako
-- lead" albo gdy nadawca był wcześniej oznaczony jako robot formularza. Reszta
-- zapytań — a te przychodzą ze zwykłych, nigdy wcześniej niewidzianych adresów —
-- czekała, aż człowiek przejrzy skrzynkę. Ta funkcja przenosi tę decyzję na model
-- językowy: LEAD (klient pyta o naszą usługę) albo NIE-LEAD (cała reszta).

-- Flaga per studio. DEFAULT FALSE, bo dla istniejących studiów włączenie automatu
-- bez ich wiedzy zmieniłoby zawartość tabeli leadów z dnia na dzień.
ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS auto_lead_classification_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Moment włączenia flagi — próg „od kiedy klasyfikujemy".
--
-- Bez niego doczytanie starego folderu (backfill, reset UIDVALIDITY) wsypałoby do
-- klasyfikatora lata poczty naraz: rachunek za tokeny i setki dawno obsłużonych
-- zgłoszeń pogrzebałyby te żywe. Ten sam mechanizm chroni dziś automat formularzy
-- (form_mail_sources.created_at). Wyłączenie i ponowne włączenie przesuwa próg —
-- świadomie, bo poczta z okresu, gdy funkcja była wyłączona, nie została pominięta
-- przez przypadek.
ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS auto_lead_classification_enabled_at TIMESTAMPTZ;

-- Dziennik klasyfikacji: jeden wiersz na przetworzoną wiadomość, także odrzuconą.
--
-- Pełni trzy role naraz:
--  1. IDEMPOTENCJA — unikalny indeks na message_id gwarantuje, że jedna wiadomość
--     to najwyżej jedna klasyfikacja i najwyżej jeden lead, niezależnie od tego,
--     ile razy sync jej dotknie i ile instancji aplikacji stoi za load balancerem.
--  2. AUDYT — „dlaczego ten mail nie stał się leadem" ma dawać się odpowiedzieć po
--     fakcie, stąd werdykt, pewność i uzasadnienie modelu obok siebie.
--  3. STROJENIE PROMPTU — nazwa modelu przy każdym wierszu pozwala porównać
--     skuteczność po podmianie modelu, zamiast zgadywać.
CREATE TABLE IF NOT EXISTS lead_message_classifications (
    id          UUID PRIMARY KEY,
    studio_id   UUID NOT NULL,
    message_id  UUID NOT NULL,
    thread_id   UUID NOT NULL,
    -- CREATED | REJECTED | FAILED | SKIPPED
    status      VARCHAR(20) NOT NULL,
    -- LEAD | NOT_LEAD | NULL (gdy do modelu w ogóle nie doszło)
    verdict     VARCHAR(20),
    -- 0.00–1.00; próg decyzyjny siedzi w konfiguracji, nie w bazie
    confidence  NUMERIC(3, 2),
    -- Jedno zdanie modelu — do czytania przez człowieka, nie do logiki
    reasoning   VARCHAR(500),
    reason      VARCHAR(300),
    model       VARCHAR(100),
    lead_id     UUID,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_lead_message_classifications_message
    ON lead_message_classifications (message_id);

-- Przeglądy „co automat zrobił w tym studiu" idą od najnowszych.
CREATE INDEX IF NOT EXISTS ix_lead_message_classifications_studio
    ON lead_message_classifications (studio_id, created_at DESC);

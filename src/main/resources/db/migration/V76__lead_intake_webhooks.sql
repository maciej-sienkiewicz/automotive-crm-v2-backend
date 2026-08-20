-- ═══════════════════════════════════════════════════════════════════════════════
-- Wejście leadów z formularza na stronie studia.
--
-- Studia mają formularze na swoich stronach i dziś ich wypełnienie kończy się
-- mailem, który ktoś musi przeczytać i przepisać. Ten webhook zdejmuje przepisywanie:
-- formularz strzela POST-em, a lead pojawia się w tabeli.
--
-- Dwa wymagania stoją tu naprzeciw siebie. PROSTOTA: właściciel studia ma wkleić
-- jeden adres w ustawienia wtyczki i skończyć. GENERYCZNOŚĆ: każda wtyczka nazywa
-- pola inaczej — Elementor kluczuje etykietami („Imię i nazwisko"), Contact Form 7
-- nazwami tagów („your-name"), Tally wysyła tablicę {label, value}. Dlatego kształt
-- ładunku NIE jest umową: przyjmujemy dowolny JSON, spłaszczamy go i mapujemy
-- słownikiem synonimów, a `field_mapping` istnieje tylko dla przypadków, w których
-- słownik nie trafi.
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS lead_intake_webhooks (
    id               UUID PRIMARY KEY,
    studio_id        UUID NOT NULL,
    -- Nazwa dla człowieka: „Formularz wyceny", „Landing PPF".
    name             VARCHAR(120) NOT NULL,
    -- SHA-256 tokenu z adresu. Sam token pokazujemy raz, przy tworzeniu —
    -- dokładnie tak jak przy parowaniu tabletów.
    token_hash       VARCHAR(64) NOT NULL,
    -- Ostatnie znaki tokenu, żeby dało się rozpoznać wpis na liście bez ujawniania go.
    token_hint       VARCHAR(12) NOT NULL,
    -- Opcjonalne nadpisania mapowania: {"email":["adres-kontaktowy"], ...}. NULL = sam słownik.
    field_mapping    TEXT,
    -- Tagi doklejane do każdego leada z tego formularza (JSON: ["PPF_WRAP"]).
    default_tag_codes TEXT,
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_received_at TIMESTAMP WITH TIME ZONE,
    received_count   BIGINT NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_lead_intake_webhooks_token
    ON lead_intake_webhooks(token_hash);

CREATE INDEX IF NOT EXISTS idx_lead_intake_webhooks_studio
    ON lead_intake_webhooks(studio_id, created_at DESC);

-- ═══════════════════════════════════════════════════════════════════════════════
-- Dziennik doręczeń. Trzymamy SUROWY ładunek każdego zgłoszenia.
--
-- Bez niego pierwsze pytanie po wdrożeniu — „wysłałem formularz i nic nie przyszło,
-- dlaczego?" — nie ma odpowiedzi. Z nim widać, co dokładnie przyszło i na czym
-- mapowanie się wyłożyło, a poprawione mapowanie da się zastosować wstecz.
-- ═══════════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS lead_intake_deliveries (
    id          UUID PRIMARY KEY,
    studio_id   UUID NOT NULL,
    webhook_id  UUID NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    -- CREATED / DUPLICATE / REJECTED
    status      VARCHAR(20) NOT NULL,
    reason      VARCHAR(300),
    lead_id     UUID,
    payload     TEXT NOT NULL,
    remote_ip   VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_lead_intake_deliveries_webhook
    ON lead_intake_deliveries(webhook_id, received_at DESC);

CREATE INDEX IF NOT EXISTS idx_lead_intake_deliveries_studio
    ON lead_intake_deliveries(studio_id, received_at DESC);

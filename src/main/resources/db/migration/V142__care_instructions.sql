-- Słownik instrukcji pielęgnacyjnych drukowanych na certyfikacie jakości.
--
-- Wcześniej cztery uniwersalne zasady siedziały w kodzie generatora PDF. Studio nie
-- mogło ich zmienić ani dopisać własnych, a instrukcje zależne od usługi (terminy
-- utwardzania powłoki) trzeba było przepisywać ręcznie przy każdym certyfikacie.
CREATE TABLE IF NOT EXISTS care_instructions (
    id                  UUID PRIMARY KEY,
    studio_id           UUID NOT NULL,
    -- Krótka etykieta na liście wyboru; na dokument idzie content.
    title               VARCHAR(200) NOT NULL,
    content             TEXT NOT NULL,
    -- Zaznaczaj przy każdym certyfikacie (zasady prawdziwe zawsze).
    is_default_selected BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order          INT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_care_instructions_studio
    ON care_instructions (studio_id, sort_order);

-- Przypisanie instrukcji do usługi: wykonanie usługi zaznacza jej instrukcje
-- na certyfikacie automatycznie.
CREATE TABLE IF NOT EXISTS service_care_instructions (
    id                   UUID PRIMARY KEY,
    studio_id            UUID NOT NULL,
    service_id           UUID NOT NULL,
    care_instruction_id  UUID NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_service_care_instruction UNIQUE (service_id, care_instruction_id)
);

CREATE INDEX IF NOT EXISTS idx_service_care_instr_service
    ON service_care_instructions (service_id);
CREATE INDEX IF NOT EXISTS idx_service_care_instr_studio
    ON service_care_instructions (studio_id);

-- Znacznik zasiania domyślnych instrukcji. Bez niego studio, które skasowało
-- domyślne wpisy, dostawałoby je z powrotem przy każdym starcie aplikacji.
ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS care_instructions_seeded_at TIMESTAMP WITH TIME ZONE;

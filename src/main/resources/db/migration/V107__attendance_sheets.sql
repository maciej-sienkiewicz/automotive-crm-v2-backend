-- Wygenerowane listy obecności.
--
-- Arkusz przestał być plikiem, który powstaje i od razu znika do przeglądarki: jest
-- dokumentem kadrowym, który się podpisuje, a podpisany dokument musi dać się później
-- odszukać i pobrać ponownie. Plik leży w S3, tu zostaje to, co pozwala go znaleźć
-- i powiedzieć, czy jest już podpisany.

CREATE TABLE IF NOT EXISTS attendance_sheets (
    id                 UUID PRIMARY KEY,
    studio_id          UUID NOT NULL,
    -- Miesiąc arkusza (YYYY-MM) — po nim szuka się „listy za wrzesień".
    period             VARCHAR(7) NOT NULL,
    -- Pracownicy, którzy weszli do arkusza. Zapis, a nie relacja: to migawka wyboru
    -- z chwili generowania, która nie ma podążać za zmianami w kadrach.
    employee_ids       JSONB NOT NULL DEFAULT '[]'::jsonb,
    -- Klucz S3 pliku bez podpisu. Zostaje także po podpisaniu: podpisany arkusz jest
    -- osobnym plikiem, więc widać, co dokładnie zostało podpisane.
    file_s3_key        VARCHAR(500) NOT NULL,
    signed_file_s3_key VARCHAR(500),
    -- Kto podpisał i kiedy — imię i nazwisko przepisane w chwili podpisu, bo konto
    -- może zmienić nazwę albo zniknąć, a dokument ma zostać czytelny.
    signer_name        VARCHAR(200),
    signed_at          TIMESTAMPTZ,
    signed_by          UUID,
    created_by         UUID NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Lista arkuszy studia (najnowsze pierwsze) i odczyt pojedynczego arkusza do podpisu.
CREATE INDEX IF NOT EXISTS idx_attendance_sheets_studio
    ON attendance_sheets (studio_id, created_at DESC);

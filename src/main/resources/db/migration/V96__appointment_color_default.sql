-- Kolor domyślny: ten, który ma być zaznaczony od razu na nowej wizycie
-- (/checkin/new, QuickEventModal). Dokładnie jeden na studio — zamiast kolumny
-- w ustawieniach studia trzymamy flagę przy kolorze, bo wtedy usunięcie koloru
-- nie zostawia wiszącego wskaźnika.
ALTER TABLE appointment_colors ADD COLUMN IF NOT EXISTS is_default boolean NOT NULL DEFAULT false;

-- Wyłączność pilnuje baza, nie tylko handler: dwa równoległe „ustaw domyślny"
-- inaczej zostawiłyby studio z dwoma domyślnymi kolorami i losowym wyborem.
CREATE UNIQUE INDEX IF NOT EXISTS uq_appointment_colors_default_per_studio
    ON appointment_colors (studio_id)
    WHERE is_default;

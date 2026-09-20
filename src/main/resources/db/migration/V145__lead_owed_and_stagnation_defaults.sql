-- Dwie zmiany wokół jednego pytania: „czyj jest ruch i od kiedy".
--
-- 1. PROGI STYGNIĘCIA DOSTAJĄ MIGRACJĘ I SENSOWNE DOMYŚLNE.
--
-- Kolumny lead_stagnant_* istniały dotąd wyłącznie z ddl-auto, a kontroler
-- podstawiał 48/72. Żaden ekran ich nie zmieniał, więc para 48/72 w praktyce
-- znaczyła „nieskonfigurowane", a nie „tak wybrał właściciel" — i interfejs
-- musiał ją wykrywać i podmieniać na własne 24/120 (UNCONFIGURED_BACKEND_DEFAULTS
-- w useLeads.ts). Od kiedy kolejka dzieli się na sekcje według tych progów,
-- zgadywanie po stronie przeglądarki przestaje wystarczać: liczba w nagłówku
-- sekcji musi znaczyć to samo, co liczba w analityce.
--
-- Przestawiamy WYŁĄCZNIE tę parę: studio, które ma cokolwiek innego, wybrało to
-- świadomie (endpoint owner-only istnieje) i nie wolno mu tego nadpisać.
--
-- 2. DŁUG STUDIA (owed_since) — ręczne „ruch jest u mnie".
--
-- „Czyj ruch" wynika z kierunku ostatniej wiadomości i w jednym przypadku myli
-- się zawsze: klient dzwoni i prosi o przesłanie oferty mailem. Odnotowanie tej
-- rozmowy stempluje reakcję studia, więc lead schodzi do „U klienta" — a prawda
-- jest odwrotna, bo klient czeka na coś, czego jeszcze nie wysłaliśmy.
--
-- owed_since jest ustawiane wprost przez człowieka i kasowane przez DOWÓD spłaty
-- (nasza wiadomość w wątku, kolejny kontakt z odpowiedzią „czekam na klienta",
-- rozstrzygnięcie sprawy). Pole, które trzeba czyścić ręcznie, zamieniłoby się
-- w drugą listę do pilnowania i umarłoby w trzy tygodnie.

ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS lead_stagnant_our_threshold_hours integer NOT NULL DEFAULT 24;

ALTER TABLE studio_settings
    ADD COLUMN IF NOT EXISTS lead_stagnant_client_threshold_hours integer NOT NULL DEFAULT 120;

ALTER TABLE studio_settings
    ALTER COLUMN lead_stagnant_our_threshold_hours SET DEFAULT 24;

ALTER TABLE studio_settings
    ALTER COLUMN lead_stagnant_client_threshold_hours SET DEFAULT 120;

UPDATE studio_settings
SET lead_stagnant_our_threshold_hours = 24,
    lead_stagnant_client_threshold_hours = 120
WHERE lead_stagnant_our_threshold_hours = 48
  AND lead_stagnant_client_threshold_hours = 72;

ALTER TABLE leads
    ADD COLUMN IF NOT EXISTS owed_since timestamp with time zone;

-- Notatka z rozmowy, w której padła obietnica („wysłać wycenę ceramiki na maila").
-- Ta sama treść trafia na oś czasu jako notatka kontaktu; tutaj jest po to, żeby
-- karta w kolejce mogła powiedzieć, CO jesteśmy winni, a nie tylko że jesteśmy.
ALTER TABLE leads
    ADD COLUMN IF NOT EXISTS owed_note varchar(500);

-- Sekcja „Czeka na Ciebie" pyta o to przy każdym odświeżeniu kolejki, a plakietka
-- w menu bocznym przy każdym wejściu do aplikacji. Indeks częściowy, bo długów
-- jest z definicji garstka na tle wszystkich leadów studia.
CREATE INDEX IF NOT EXISTS ix_leads_owed
    ON leads (studio_id)
    WHERE owed_since IS NOT NULL;

-- SMS „Upselling": pracownik dodał propozycję dodatkowych usług na Karcie Wizyty
-- i zaznaczył „powiadom klienta". Informacja z linkiem do karty — bez „odpisz TAK";
-- zgoda (upsell_consent_*) idzie osobną ścieżką, gdy klient sam wybierze usługę.

ALTER TABLE sms_automation_configs
    ADD COLUMN IF NOT EXISTS upsell_suggestion_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS upsell_suggestion_message_template TEXT NOT NULL DEFAULT '';

UPDATE sms_automation_configs
SET upsell_suggestion_message_template =
    '{{imie}}, do Twojej Karty Wizyty dodaliśmy propozycję dodatkowych usług: {{uslugi}}. Szczegóły i decyzja: {{link}}'
WHERE upsell_suggestion_message_template = '';

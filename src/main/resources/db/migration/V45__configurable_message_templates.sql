-- Every customer-facing message becomes a studio-owned template.
--
-- Three things happen here:
--   1. new template columns for the messages that used to be hardcoded
--      (reservation card link, upsell consent, signature request — SMS and e-mail);
--   2. the {{studio}}, {{telefon_studia}} and {{www}} placeholders are retired and
--      replaced, in place, with each studio's own values — they were never variable,
--      the studio knows them when it writes the template;
--   3. the delayed retention reminder stops being enabled behind the studio's back.

-- ── 1. SMS: new rules ────────────────────────────────────────────────────────

ALTER TABLE sms_automation_configs
    ADD COLUMN IF NOT EXISTS reservation_card_link_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS reservation_card_link_message_template TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS upsell_consent_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS upsell_consent_message_template TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS signature_request_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS signature_request_message_template TEXT NOT NULL DEFAULT '';

-- Seed the new rules with the wording that used to be compiled in, so studios keep
-- sending the same text. They stay disabled until the studio reviews and enables them.
UPDATE sms_automation_configs
SET reservation_card_link_message_template =
        'Szczegóły Twojej rezerwacji na {{data}} o godz. {{godzina}} znajdziesz tutaj: {{link}}'
WHERE reservation_card_link_message_template = '';

UPDATE sms_automation_configs
SET upsell_consent_message_template =
        'Odpisz TAK, żeby do rezerwacji dodać usługi: {{uslugi}}. Łącznie {{kwota}} PLN brutto.'
WHERE upsell_consent_message_template = '';

UPDATE sms_automation_configs
SET signature_request_message_template =
        'Dokument „{{dokument}}” czeka na Twój podpis. Otwórz link, zapoznaj się z treścią i podpisz: {{link}}'
WHERE signature_request_message_template = '';

-- ── 2. E-mail: new rules + backfill of the nullable batch-order columns ──────

ALTER TABLE email_automation_configs
    ADD COLUMN IF NOT EXISTS visit_card_link_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS visit_card_link_subject_template TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS visit_card_link_body_template TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS reservation_card_link_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS reservation_card_link_subject_template TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS reservation_card_link_body_template TEXT NOT NULL DEFAULT '';

UPDATE email_automation_configs
SET visit_card_link_subject_template = 'Karta wizyty {{numer_wizyty}}',
    visit_card_link_body_template = E'Dzień dobry {{imie}},\n\n'
        || E'przygotowaliśmy Kartę Wizyty dla Twojego pojazdu {{pojazd}} {{rejestracja}} '
        || E'(wizyta {{numer_wizyty}}, termin: {{data}} {{godzina}}).\n\n'
        || E'Znajdziesz na niej szczegóły rezerwacji, zakres usług z wyceną oraz — w trakcie '
        || E'wizyty — dokumentację zdjęciową i dokumenty:\n{{link}}\n\nPozdrawiamy'
WHERE visit_card_link_body_template = '';

UPDATE email_automation_configs
SET reservation_card_link_subject_template = 'Twoja rezerwacja {{data}} {{godzina}}',
    reservation_card_link_body_template = E'Dzień dobry {{imie}},\n\n'
        || E'przygotowaliśmy stronę Twojej rezerwacji (termin: {{data}} {{godzina}}).\n\n'
        || E'Znajdziesz na niej szczegóły rezerwacji i zakres usług z wyceną, a po przyjęciu '
        || E'pojazdu także dokumentację zdjęciową i dokumenty:\n{{link}}\n\nPozdrawiamy'
WHERE reservation_card_link_body_template = '';

-- The batch-order columns were nullable; NULL now means "no template", which would
-- silently stop the report e-mail. Backfill before tightening the contract in code.
UPDATE email_automation_configs
SET batch_order_close_subject_template = 'Zestawienie zbiorcze – {{kontrahent}} – {{okres}}'
WHERE batch_order_close_subject_template IS NULL;

UPDATE email_automation_configs
SET batch_order_close_body_template = E'Szanowni Państwo,\n\n'
        || E'Przesyłamy zestawienie zbiorcze za okres {{okres}} dla kontrahenta {{kontrahent}}.\n\n'
        || E'Liczba wpisów: {{liczba_wpisow}}\nŁączna kwota brutto: {{kwota_brutto}}\n\n'
        || E'Zestawienie w formacie PDF znajdą Państwo w załączniku.\n\nPozdrawiamy'
WHERE batch_order_close_body_template IS NULL;

-- ── 3. Retire {{studio}} by materialising each studio's own name ─────────────

UPDATE sms_automation_configs c
SET pre_visit_message_template =
        replace(c.pre_visit_message_template, '{{studio}}', coalesce(s.name, '')),
    post_visit_message_template =
        replace(c.post_visit_message_template, '{{studio}}', coalesce(s.name, '')),
    delayed_reminder_message_template =
        replace(c.delayed_reminder_message_template, '{{studio}}', coalesce(s.name, '')),
    booking_confirmation_message_template =
        replace(c.booking_confirmation_message_template, '{{studio}}', coalesce(s.name, '')),
    reschedule_confirmation_message_template =
        replace(c.reschedule_confirmation_message_template, '{{studio}}', coalesce(s.name, '')),
    visit_ready_for_pickup_message_template =
        replace(c.visit_ready_for_pickup_message_template, '{{studio}}', coalesce(s.name, '')),
    visit_card_link_message_template =
        replace(c.visit_card_link_message_template, '{{studio}}', coalesce(s.name, ''))
FROM studio_settings s
WHERE s.studio_id = c.studio_id;

UPDATE email_automation_configs c
SET visit_welcome_subject_template =
        replace(c.visit_welcome_subject_template, '{{studio}}', coalesce(s.name, '')),
    visit_welcome_body_template =
        replace(c.visit_welcome_body_template, '{{studio}}', coalesce(s.name, '')),
    visit_ready_for_pickup_subject_template =
        replace(c.visit_ready_for_pickup_subject_template, '{{studio}}', coalesce(s.name, '')),
    visit_ready_for_pickup_body_template =
        replace(c.visit_ready_for_pickup_body_template, '{{studio}}', coalesce(s.name, '')),
    batch_order_close_subject_template =
        replace(c.batch_order_close_subject_template, '{{studio}}', coalesce(s.name, '')),
    batch_order_close_body_template =
        replace(c.batch_order_close_body_template, '{{studio}}', coalesce(s.name, ''))
FROM studio_settings s
WHERE s.studio_id = c.studio_id;

-- Campaigns: {{studio}}, {{telefon_studia}} and {{www}} go the same way.
UPDATE campaigns c
SET sms_template = replace(replace(replace(
        coalesce(c.sms_template, ''),
        '{{studio}}', coalesce(s.name, '')),
        '{{telefon_studia}}', coalesce(s.phone, '')),
        '{{www}}', coalesce(s.website, '')),
    email_subject = replace(replace(replace(
        coalesce(c.email_subject, ''),
        '{{studio}}', coalesce(s.name, '')),
        '{{telefon_studia}}', coalesce(s.phone, '')),
        '{{www}}', coalesce(s.website, '')),
    email_body = replace(replace(replace(
        coalesce(c.email_body, ''),
        '{{studio}}', coalesce(s.name, '')),
        '{{telefon_studia}}', coalesce(s.phone, '')),
        '{{www}}', coalesce(s.website, ''))
FROM studio_settings s
WHERE s.studio_id = c.studio_id;

-- ── 4. No automation runs without the studio switching it on ────────────────

UPDATE sms_automation_configs
SET delayed_reminder_enabled = FALSE
WHERE delayed_reminder_enabled = TRUE;

-- ═══════════════════════════════════════════════════════════════════════════════
-- Rezerwacja-cień wizyty walk-in.
--
-- „Wizyta" w kalendarzu (/checkin/new) zakłada wizytę bez wcześniejszej rezerwacji,
-- ale wizyta musi mieć appointment_id, więc backend tworzy w tle rezerwację-cień.
-- Anulowanie szkicu („Przerwij przyjęcie" → „Anuluj wizytę") celowo zostawia
-- rezerwację - przy przyjęciu z prawdziwej rezerwacji ma ona wrócić do kalendarza.
-- Cień walk-inu nie był jednak niczyją rezerwacją, a zostawał w kalendarzu jako
-- termin, którego nikt nie umawiał (zgłoszenie biznesu z 03.10).
--
-- walk_in = true: rezerwację założyło przyjęcie walk-in; anulowanie jego szkicu
-- usuwa ją razem z wizytą. Starych cieni nie da się odróżnić od zwykłych rezerwacji,
-- więc zostają false.
-- ═══════════════════════════════════════════════════════════════════════════════

ALTER TABLE appointments ADD COLUMN IF NOT EXISTS walk_in BOOLEAN NOT NULL DEFAULT FALSE;

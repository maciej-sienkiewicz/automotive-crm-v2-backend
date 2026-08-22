-- Uzupełnienie kontekstu biznesowego w historycznych wpisach dziennika.
--
-- Filtr „wszystko wokół jednego obiektu" (karty klienta, pojazdu, wizyty) czyta
-- kolumny customer_id / vehicle_id / visit_id — a zdarzenia rezerwacji i wizyt
-- zapisywały id klienta co najwyżej w metadanych. Karta klienta nie widziała
-- więc jego rezerwacji. Nowe wpisy dostają kontekst w locie (AuditContextResolver);
-- ten UPDATE dopina go wstecz, z tabel źródłowych — bez dotykania rezerwacji
-- i wizyt usuniętych z bazy na twardo (tych nie ma już skąd rozwiązać).

UPDATE audit_logs a
SET customer_id    = ap.customer_id,
    vehicle_id     = COALESCE(a.vehicle_id, ap.vehicle_id),
    appointment_id = COALESCE(a.appointment_id, ap.id)
FROM appointments ap
WHERE a.module = 'APPOINTMENT'
  AND a.customer_id IS NULL
  AND a.studio_id = ap.studio_id
  AND a.entity_id = ap.id::text;

UPDATE audit_logs a
SET customer_id = v.customer_id,
    vehicle_id  = COALESCE(a.vehicle_id, v.vehicle_id),
    visit_id    = COALESCE(a.visit_id, v.id)
FROM visits v
WHERE a.module = 'VISIT'
  AND a.customer_id IS NULL
  AND a.studio_id = v.studio_id
  AND a.entity_id = v.id::text;

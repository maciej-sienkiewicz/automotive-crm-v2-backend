-- Naprawa leadów wskazujących na usunięte rezerwacje.
--
-- Usunięcie rezerwacji z kalendarza nie odpinało leada: jego podgląd dalej
-- twierdził „Rezerwacja została utworzona", a pobranie terminu kończyło się
-- komunikatem „Rezerwacja nie została znaleziona". Kod od tej migracji odpina
-- leada przy każdym usunięciu; ten UPDATE sprząta wskaźniki, które zdążyły
-- zawisnąć wcześniej.
--
-- Statusu nie ruszamy: migracja nie wie, dlaczego rezerwacja zniknęła, a zmiana
-- statusu bez wpisu w historii leada byłaby gorsza niż status do ręcznej poprawki.
UPDATE leads l
SET appointment_id = NULL
WHERE l.appointment_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM appointments a
      WHERE a.id = l.appointment_id
        AND a.deleted_at IS NULL
  );

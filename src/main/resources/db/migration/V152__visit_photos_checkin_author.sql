-- Autor zdjęć z przyjęcia pojazdu.
--
-- Przyjęcie pojazdu (rezerwacja → wizyta i wizyta z ulicy) zapisywało zdjęcia z sesji
-- zdjęć i z telefonu przez QR bez uploaded_by / uploaded_by_name, więc galeria pokazywała
-- przy nich puste „Dodał/a". Od tej wersji autorem jest osoba przyjmująca pojazd
-- (CreateVisitFromReservationHandler.checkinPhoto) - tu uzupełniamy to samo wstecz.
--
-- Osoba przyjmująca pojazd to twórca wizyty (visits.created_by). Uzupełniamy wyłącznie
-- zdjęcia bez żadnego autora, zapisane razem z wizytą (w ciągu 10 minut od jej utworzenia -
-- przeniesienie zdjęć z telefonu potrafi chwilę potrwać). Zdjęcie bez autora dodane
-- później nie pochodzi z przyjęcia i zostaje bez zmian: lepiej puste pole niż cudze
-- nazwisko. Nazwa jak w UserPrincipal.fullName: „imię nazwisko".

UPDATE visit_photos vp
SET uploaded_by      = v.created_by,
    -- left(): kolumna ma 200 znaków, a imię i nazwisko po 100 - razem ze spacją 201.
    uploaded_by_name = left(u.first_name || ' ' || u.last_name, 200)
FROM visits v
JOIN users u ON u.id = v.created_by
WHERE vp.visit_id = v.id
  AND vp.uploaded_by IS NULL
  AND vp.uploaded_by_name IS NULL
  AND vp.uploaded_at BETWEEN v.created_at - INTERVAL '10 minutes' AND v.created_at + INTERVAL '10 minutes';

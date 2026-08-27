-- Raport tygodnia zastąpiony digestem „Tydzień": jeden wiersz na obserwowany profil
-- zamiast powtórzenia metryk, pozycji i insightów z Przeglądu w drugim układzie.
--
-- instagram_reports zostaje jako cache wygenerowanej treści — zmienia się wyłącznie
-- kształt kolumny payload. Stare wiersze są nieczytelne dla nowego DTO i nie ma czego
-- z nich ratować: to wygenerowany cache tygodnia, nie dane wprowadzone przez studio.
-- Odtworzą się przy pierwszym wejściu na zakładkę.
DELETE FROM instagram_reports;

COMMENT ON TABLE instagram_reports IS
    'Cache digestu tygodnia (WeeklyDigestService). period_start = poniedziałek biezacego tygodnia; '
    'wiersz jest przeliczany, gdy synchronizacja przyniesie dane nowsze niz created_at.';

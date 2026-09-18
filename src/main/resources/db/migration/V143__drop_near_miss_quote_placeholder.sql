-- Zaślepka „Wycena indywidualna" przestaje być pozycją wyceny.
--
-- Przy werdykcie CATALOG_NEAR_MISS („cennik ma tę samą operację, ale na innej
-- części auta") sugestie usług zakładały pozycję bez ceny i bez wskazanej usługi,
-- z notatką tłumaczącą, czemu automat nie podał kwoty. Właściciel dostawał w szynie
-- puste pole „zł brutto", nie wiedząc, CZEGO ta kwota dotyczy — wyjaśnienie pracy
-- automatu było wystawione jako wiersz wyceny. Od teraz przy braku dopasowania
-- sekcja milczy, a usługę dodaje człowiek od początku.
--
-- Tutaj znikają zaślepki, które zdążyły powstać. Bez tego wiszą na leadach aż do
-- najbliższego przeliczenia sugestii, którego nikt nie ma powodu wywoływać.
--
-- Kasujemy WYŁĄCZNIE niezaakceptowane zaślepki automatu: bez wskazanej usługi,
-- bez ceny i o tej jednej nazwie. Pozycja, którą człowiek zdążył przyjąć (ACCEPTED),
-- ma już podaną kwotę i jest częścią wyceny — tej nie ruszamy.
DELETE FROM lead_service_items
WHERE status = 'SUGGESTED'
  AND source = 'AI'
  AND service_id IS NULL
  AND price_gross IS NULL
  AND name = 'Wycena indywidualna';

-- Sprzątnięcie zaślepek „Wycena indywidualna".
--
-- Automat podsuwał tę pozycję, gdy cennik miał tę samą OPERACJĘ, ale na innej
-- części auta (CATALOG_NEAR_MISS): wiersz bez ceny i bez `service_id`, z notatką
-- tłumaczącą, czemu nie podano kwoty. Zamysł był taki, żeby nie zgubić sygnału
-- „klient pyta o coś bliskiego" — skutek odwrotny: na liście usług leada stawała
-- nazwa, której w cenniku studia nie ma i nigdy nie było.
--
-- Kod przestał je tworzyć (LeadServiceSuggestionService). Ta migracja usuwa te,
-- które już powstały — inaczej wisiałyby na starych leadach do najbliższego
-- przeliczenia sugestii, czyli u większości z nich na zawsze.
--
-- Warunki są wąskie z rozmysłem:
--   • status SUGGESTED  — pozycji ZAAKCEPTOWANEJ nie ruszamy, nawet jeśli wyszła
--     z zaślepki: właściciel nadał jej wtedy cenę i uznał za część wyceny,
--   • source AI         — nie dotykamy niczego, co wpisał człowiek,
--   • service_id IS NULL— jedyne sugestie AI bez pozycji cennika to właśnie te,
--   • name              — ostatnia bariera, gdyby kiedyś powstał inny wariant.
DELETE FROM lead_service_items
WHERE status = 'SUGGESTED'
  AND source = 'AI'
  AND service_id IS NULL
  AND name = 'Wycena indywidualna';

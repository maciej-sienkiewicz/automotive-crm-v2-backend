-- Zatrzymanie krwawienia w „Podobnych zleceniach" — Etap 0 przebudowy
-- (docs/similar-visits-redesign.md, §5 i §7).
--
-- Dwa realne incydenty: przy zapytaniu o PPF full body (kotwica 18 450 zł) sekcja
-- podpowiadała folię na progu bagażnika za 850 zł, a przy naprawie tapicerki fotela —
-- mycie detailingowe. Wspólna przyczyna: algorytm zna wyłącznie RODZAJ roboty,
-- a pytanie właściciela dotyczy jej SKALI. Ta migracja daje trzy kolumny, bez których
-- bramka skali i unieważnianie zapisanych wyników nie mają na czym stanąć.
--
-- 1. visit_index_state.total_gross — kwota zlecenia W INDEKSIE. Dziś kwota pojawia się
--    dopiero przy hydratacji, PO przycięciu listy do dwunastki — więc bramki cenowej
--    nie da się wpiąć przed przycięciem, a dobry comp bywa odcięty zanim ktokolwiek
--    zobaczy jego cenę. DEFAULT 0 znaczy „jeszcze nieprzestemplowane", NIE „za darmo":
--    bramka skali pomija wiersze z zerem (brak danych nie jest zerem — dokładnie ta
--    sama pomyłka, którą naprawiamy w ratio() matchera). Wypełnia ją uzgadniacz po
--    podbiciu CURRENT_SIGNATURE_VERSION, regułą Visit.effectiveGrossAmount.
--
-- 2. lead_similar_matches.rules_version — wersja REGUŁ, którymi policzono zapisany
--    dobór. findFor() preferuje zapisany wiersz bezwarunkowo, więc bez tej kolumny
--    żadna zmiana bramek nie dociera do leada, który już ma wynik — w tym do dwóch
--    leadów, od których zaczęła się ta przebudowa. Wiersz z wersją niższą niż stała
--    w kodzie jest przeliczany przy najbliższym otwarciu.
--
-- 3. lead_service_intents.prompt_version — wersja PROMPTU, którym odczytano intencję.
--    Odcisk treści (query_fingerprint) nie widzi zmian promptu, więc poprawka promptu
--    bez tej kolumny obowiązywałaby wyłącznie nowe leady.

ALTER TABLE visit_index_state
    ADD COLUMN IF NOT EXISTS total_gross BIGINT NOT NULL DEFAULT 0;

ALTER TABLE lead_similar_matches
    ADD COLUMN IF NOT EXISTS rules_version SMALLINT NOT NULL DEFAULT 0;

ALTER TABLE lead_service_intents
    ADD COLUMN IF NOT EXISTS prompt_version VARCHAR(20) NOT NULL DEFAULT 'v0';

COMMENT ON COLUMN visit_index_state.total_gross IS
    'Kwota zlecenia regułą Visit.effectiveGrossAmount, w groszach. 0 = jeszcze nieprzestemplowane — bramka skali POMIJA takie wiersze.';
COMMENT ON COLUMN lead_similar_matches.rules_version IS
    'Wersja reguł doboru (SimilarVisitsHandler.CURRENT_RULES_VERSION). Niższa niż stała w kodzie = wiersz do przeliczenia.';
COMMENT ON COLUMN lead_service_intents.prompt_version IS
    'Wersja promptu odczytu intencji. Inna niż stała w kodzie = dziennik nieważny, model pytany od nowa.';

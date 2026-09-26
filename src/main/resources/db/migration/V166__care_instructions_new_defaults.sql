-- Nowe domyślne instrukcje pielęgnacji na certyfikacie jakości.
--
-- Cztery dotychczasowe zasady (mycie, osuszanie, zabrudzenia, chemia) były ogólnikami,
-- które klient zna bez certyfikatu. Zastępują je trzy instrukcje, po które klient
-- naprawdę wraca: myjnia bezdotykowa przy powłoce ceramicznej, mycie auta oklejonego
-- folią PPF (lanca, odległość, krawędzie) i kosmetyki, których unikać we wnętrzu.
--
-- Usuwamy WYŁĄCZNIE dawne wpisy domyślne, których studio nie ruszyło (ten sam tytuł
-- i ta sama treść). Instrukcja poprawiona własnym językiem albo dopisana przez studio
-- jest jego pracą i zostaje.
--
-- Nowe instrukcje nie są zaznaczane przy każdym certyfikacie: każda dotyczy innej
-- usługi i na certyfikacie samego prania tapicerki porada o folii PPF byłaby szumem.
-- Zamiast tego przypinamy je do aktywnych usług cennika po nazwie (ceram, PPF,
-- wnętrze, tapicerka, skóra), a wybranie takiej usługi zaznacza instrukcję samo.
-- Resztę przypisań studio ustawia w Ustawieniach, w Cenniku usług.
--
-- Studia, które słownika jeszcze nie dostały (care_instructions_seeded_at IS NULL),
-- nie są tu ruszane: zasieje je DefaultCareInstructionProvisioner, już nowymi wpisami.
-- Treść nowych wpisów musi być identyczna z DEFAULTS w provisionerze; pilnuje tego
-- CareInstructionDefaultsMigrationTest.

DELETE FROM service_care_instructions sci
USING care_instructions ci,
    (VALUES
    ('Mycie',
     'Myj pojazd metodą dwóch wiader, szamponem o neutralnym pH. Myjnie automatyczne ze szczotkami zostawiają na lakierze siatkę rys.'),
    ('Osuszanie',
     'Osuszaj miękką mikrofibrą lub sprężonym powietrzem. Woda pozostawiona do odparowania zostawia osad z kamienia.'),
    ('Zabrudzenia organiczne',
     'Odchody ptaków, owady i żywicę usuwaj możliwie szybko. Zaschnięte wytrawiają lakier i ślad po nich zostaje na stałe.'),
    ('Chemia',
     'Do bieżącej pielęgnacji używaj środków o neutralnym odczynie. Silnie alkaliczne i kwaśne skracają żywotność zabezpieczeń.')
    ) AS old_defaults(title, content)
WHERE sci.care_instruction_id = ci.id
  AND ci.title = old_defaults.title
  AND ci.content = old_defaults.content;

DELETE FROM care_instructions ci
USING (VALUES
    ('Mycie',
     'Myj pojazd metodą dwóch wiader, szamponem o neutralnym pH. Myjnie automatyczne ze szczotkami zostawiają na lakierze siatkę rys.'),
    ('Osuszanie',
     'Osuszaj miękką mikrofibrą lub sprężonym powietrzem. Woda pozostawiona do odparowania zostawia osad z kamienia.'),
    ('Zabrudzenia organiczne',
     'Odchody ptaków, owady i żywicę usuwaj możliwie szybko. Zaschnięte wytrawiają lakier i ślad po nich zostaje na stałe.'),
    ('Chemia',
     'Do bieżącej pielęgnacji używaj środków o neutralnym odczynie. Silnie alkaliczne i kwaśne skracają żywotność zabezpieczeń.')
    ) AS old_defaults(title, content)
WHERE ci.title = old_defaults.title
  AND ci.content = old_defaults.content;

INSERT INTO care_instructions (id, studio_id, title, content, is_default_selected, sort_order, created_at, updated_at)
SELECT gen_random_uuid(),
       s.studio_id,
       n.title,
       n.content,
       FALSE,
       COALESCE((SELECT MAX(c.sort_order) + 1 FROM care_instructions c WHERE c.studio_id = s.studio_id), 0) + n.ord,
       now(),
       now()
FROM studio_settings s
CROSS JOIN (VALUES
    (0, 'Myjnia bezdotykowa a powłoka ceramiczna',
     'Myjnia bezdotykowa jest dla powłoki ceramicznej bezpieczna, bo nic nie dotyka lakieru. Pierwsze mycie zrób najwcześniej 7 dni po aplikacji, kiedy powłoka się utwardzi. Wybieraj program bez wosku i nabłyszczacza: wosk przykrywa powłokę i odbiera jej efekt odpychania wody. Aktywną pianę nakładaj na chłodny lakier, nie w pełnym słońcu, i spłucz ją, zanim zaschnie. Mocna chemia myjni stosowana co tydzień skraca życie powłoki, dlatego co któreś mycie zrób ręcznie szamponem o neutralnym pH.'),
    (1, 'Folia PPF na myjni bezdotykowej',
     'Pierwsze mycie zrób najwcześniej 7 dni po oklejeniu, kiedy klej folii zwiąże z lakierem. Trzymaj lancę co najmniej 30 cm od auta, a przy krawędziach folii dalej, około 50 cm. Kieruj strumień prostopadle do powierzchni albo wzdłuż krawędzi, od środka folii na zewnątrz. Nigdy nie celuj pod krawędź: woda pod ciśnieniem wchodzi pod folię i ją podrywa. Nie używaj dyszy rotacyjnej na oklejonych elementach. Na folii matowej wybieraj program bez wosku, bo wosk zostawia na niej błyszczące plamy.'),
    (2, 'Kosmetyki do wnętrza, których unikać',
     'Do każdego materiału używaj środka przeznaczonego właśnie do niego. Na ekranach i szybkach zegarów nie stosuj płynów z amoniakiem ani alkoholem, bo niszczą powłokę antyrefleksyjną. Skóry nie czyść uniwersalnymi odtłuszczaczami ani płynem do naczyń: wysuszają ją i zmywają barwnik. Kokpitu nie nabłyszczaj środkami z silikonem, które dają odblaski na szybie i przyciągają kurz. Tapicerki nie czyść wybielaczem ani środkami z chlorem, a plastików i skóry chusteczkami do mebli.')
    ) AS n(ord, title, content)
WHERE s.care_instructions_seeded_at IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM care_instructions c WHERE c.studio_id = s.studio_id AND c.title = n.title);

INSERT INTO service_care_instructions (id, studio_id, service_id, care_instruction_id, created_at)
SELECT gen_random_uuid(), ci.studio_id, sv.id, ci.id, now()
FROM care_instructions ci
JOIN services sv ON sv.studio_id = ci.studio_id AND sv.is_active = TRUE
WHERE (ci.title = 'Myjnia bezdotykowa a powłoka ceramiczna' AND (LOWER(sv.name) LIKE '%ceram%'))
    OR (ci.title = 'Folia PPF na myjni bezdotykowej' AND (LOWER(sv.name) LIKE '%ppf%'))
    OR (ci.title = 'Kosmetyki do wnętrza, których unikać' AND (LOWER(sv.name) LIKE '%wnętrz%' OR LOWER(sv.name) LIKE '%wnetrz%' OR LOWER(sv.name) LIKE '%tapicer%' OR LOWER(sv.name) LIKE '%skór%' OR LOWER(sv.name) LIKE '%skor%'))
ON CONFLICT (service_id, care_instruction_id) DO NOTHING;

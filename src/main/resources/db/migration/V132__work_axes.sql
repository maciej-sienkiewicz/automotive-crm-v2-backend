-- Osie roboty: OPERACJA i CZĘŚĆ AUTA — Etap 2 przebudowy
-- (docs/similar-visits-redesign.md, §4.1).
--
-- Rodzina (ServiceFamily) grupuje po PRZEDMIOCIE, a wycena idzie po RZEMIOŚLE:
-- „Naprawa tapicerki drzwi" i „Wnętrze rozszerzone" to jedna rodzina INTERIOR
-- i dwa różne fachy — ekstraktor i chemia kontra igła, skóra i barwnik. Stąd dwa
-- nowe wymiary klasyfikacji NAZWY usługi:
--
--   operation  CLEAN | PROTECT | CORRECT | REPAIR | APPLY_FILM | TINT | REMOVE |
--              MOUNT | SANITIZE | INSPECT | OTHER_OP | UNKNOWN
--   part       FULL_BODY | BODY_FRONT | BODY_PANEL | TRIM_PIECE | LAMPS | GLASS |
--              WHEELS | ENGINE_BAY | CABIN | SEAT | DOOR_PANEL | DASHBOARD |
--              HEADLINER | CARPET | UNKNOWN
--
-- (bez CHECK-ów — patrz V101; źródłem prawdy jest WorkAxes.kt)
--
-- axes_version = 0 znaczy „sklasyfikowane promptem bez osi, do przeklasyfikowania".
-- BEZ TEJ KOLUMNY CAŁY KORPUS ZOSTAŁBY Z UNKNOWN NA ZAWSZE: classify() liczy
-- `missing = samples.keys - known.keys` i dla znanej nazwy nigdy nie pyta modelu
-- ponownie (pilnuje tego test „znana nazwa nie dotyka modelu"). Podbicie
-- CURRENT_SIGNATURE_VERSION przestemplowuje WIZYTY, ale nie przeklasyfikowuje NAZW —
-- to robi dopiero warunek axes_version < stała w kodzie. Wiersze MANUAL są z tego
-- wyłączone: ręczna poprawka człowieka nie ma prawa zostać nadpisana automatem.
--
-- Na sygnaturach wizyt dochodzą też PIENIĄDZE POZYCJI. Wiersz sygnatury to unikalna
-- NAZWA w zleceniu (serviceNames() robi .distinct()), więc:
--   line_price_gross = SUMA kwot niezodrzuconych pozycji o tej nazwie, liczona TĄ SAMĄ
--                      regułą co Visit.effectiveGrossAmount (PENDING/ADD i REJECTED
--                      wypadają, PENDING/EDIT bierze snapshot) — suma sygnatur zgadza
--                      się z kwotą zlecenia na ekranie,
--   line_count       = liczba tych pozycji.
-- To one zasilają bramkę skupienia liczoną na KWOTACH (dzisiejszy focus liczy pozycje,
-- więc zlecenie jednopozycyjne ma zawsze 1.0) i górne odcięcie bramki skali.

ALTER TABLE service_families
    ADD COLUMN IF NOT EXISTS operation    VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS part         VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS axes_version SMALLINT    NOT NULL DEFAULT 0;

ALTER TABLE visit_service_signatures
    ADD COLUMN IF NOT EXISTS operation        VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS part             VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN IF NOT EXISTS line_price_gross BIGINT   NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS line_count       SMALLINT NOT NULL DEFAULT 1;

-- Intencja leada rośnie do POTRZEBY: lista (operation:part:scope) zamiast samego
-- zbioru rodzin — lead „PPF na przód i ceramika na resztę" musi zostać wyrażalny.
-- anchor_price_gross liczy KOD (cennik po matchedServices), nigdy model.
-- catalog_hash: bez niego nie da się odtworzyć, CO model widział — query_fingerprint
-- liczy wyłącznie treść maila i jawnie nie widzi zmian cennika.
ALTER TABLE lead_service_intents
    ADD COLUMN IF NOT EXISTS needs               TEXT NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS anchor_price_gross  BIGINT,
    ADD COLUMN IF NOT EXISTS anchor_source       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS evidence_quote      VARCHAR(500),
    ADD COLUMN IF NOT EXISTS catalog_hash        VARCHAR(64) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS invalid_index_count SMALLINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS ix_vis_studio_price
    ON visit_index_state (studio_id, total_gross);
CREATE INDEX IF NOT EXISTS ix_vss_studio_axes
    ON visit_service_signatures (studio_id, operation, part);

COMMENT ON COLUMN service_families.axes_version IS
    '0 = klasyfikacja sprzed osi, do przeklasyfikowania (poza MANUAL). Stała: ServiceFamilyClassifier.CURRENT_AXES_VERSION.';
COMMENT ON COLUMN visit_service_signatures.line_price_gross IS
    'Suma kwot pozycji o tej nazwie regułą Visit.effectiveGrossAmount, w groszach. 0 = jeszcze nieprzestemplowane.';
COMMENT ON COLUMN lead_service_intents.needs IS
    'Potrzeby leada jako operation:part:scope rozdzielone |. Lista, nie jedna wartość — lead wielousługowy jest regułą przy najdroższych zapytaniach.';

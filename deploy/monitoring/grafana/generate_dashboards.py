"""Generuje dashboardy live-metrics dla Grafany (provisionowane z repo).

Uruchom po każdej zmianie:  python3 deploy/monitoring/grafana/generate_dashboards.py

JSON dashboardów jest artefaktem, nie źródłem prawdy — ręczna edycja rozjeżdża pliki,
bo dzielą ten sam zestaw paneli. Reguły, które łatwo złamać ręcznie, są tu wymuszone:
minimalny krok wykresów, `max by` zamiast `sum` na gauge'ach i filtrowanie po `tenant_id`,
nigdy po nazwie tenanta.

Cztery dashboardy, dwa różne pytania:

  crm-live-platform / crm-live-tenant   „ile dzieje się TERAZ"  — increase() na liczniku
  crm-engagement-tenant                 „ile łącznie od wdrożenia" — trwała suma z Redisa
  crm-adoption-platform                 „kto z tego korzysta"     — stany z bazy + ranking

Rozdział jest istotny: `increase()` rysuje impulsy (zdarzenie → pik → zero), a nie krzywą
narastającą. Mieszanie obu w jednym panelu było źródłem „krótkotrwałych pików", przez które
nie dało się odczytać, ile czegoś w ogóle jest.
"""
import json, os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'provisioning', 'dashboards')
DS = {"type": "prometheus", "uid": "prometheus"}

# ── Katalog metryk ──────────────────────────────────────────────────────────
#
# JEDNO źródło prawdy dla wszystkich czterech dashboardów. Kolejność modułów = kolejność
# wierszy; kolejność kluczy w środku = kolejność kafli. Dodanie metryki w backendzie
# sprowadza się tutaj do jednej linijki — panele, kolory i tabele wyprowadzają się same.
#
#   events     {TYP: etykieta}                  licznik „ile łącznie" (crm_business_events_all_time)
#   dims       {TYP: {WARTOŚĆ: etykieta}}       rozbicie na wymiar (crm_business_events_all_time_dim)
#   slices     [(etykieta, TYP, WARTOŚĆ)]       pojedyncza pod-seria jako osobny kafel
#   amounts    {TYP: etykieta}                  suma kwot w groszach (crm_business_events_sum_all_time)
#   states     {stan: etykieta}                 0/1 z bazy (crm_tenant_state)
#   inventory  {rodzaj: etykieta}               liczba z bazy (crm_tenant_inventory)
#
# Nazwy MUSZĄ zgadzać się z `BusinessEventType` oraz z zapytaniami w `TenantStateMetricsExporter`.
# Pilnuje tego test `LiveMetricsCatalogTest` — panel na nieistniejącą metrykę pokazuje „—",
# co wygląda identycznie jak awaria scrape'u.
MODULES = {
    "Tablica i kalendarz": {
        "events": {"TASK_CREATED": "Zadania", "RESERVATION_CREATED": "Rezerwacje",
                   "VISIT_CREATED": "Wizyty", "CALENDAR_EVENT_CREATED": "Wydarzenia"},
        "dims": {"VISIT_CREATED": {"DIRECT": "bezpośrednie", "FROM_RESERVATION": "z rezerwacji"}},
    },
    "Zlecenia zbiorcze": {
        "events": {"BATCH_CONTRACTOR_CREATED": "Kontrahenci", "BATCH_SERVICE_ADDED": "Usługi w zleceniach"},
    },
    "Galeria": {
        "events": {"PHOTO_UPLOADED": "Zdjęcia"},
        "dims": {"PHOTO_UPLOADED": {"VISIT": "wizyta", "VEHICLE": "pojazd",
                                    "CHECKIN": "check-in", "BATCH_ORDER": "zlecenie"}},
    },
    "Poczta": {
        "states": {"mailbox_configured": "Poczta skonfigurowana"},
        "events": {"MESSAGE_SENT": "Wysłane wiadomości"},
        "slices": [("Maile ręczne (Poczta)", "MESSAGE_SENT", "MAILBOX"),
                   ("Maile systemowe", "MESSAGE_SENT", "EMAIL")],
        "dims": {"MESSAGE_SENT": {"SMS": "SMS", "EMAIL": "e-mail systemowy", "MAILBOX": "e-mail ręczny"}},
    },
    "Leady": {
        "events": {"LEAD_CREATED": "Stworzone", "LEAD_COMPLETED": "Zakończone",
                   "LEAD_QUOTED": "Z wyceną", "LEAD_RESERVATION_LINKED": "Z rezerwacją"},
        "dims": {"LEAD_COMPLETED": {"COMPLETED": "zrealizowane", "LOST": "utracone", "NO_SHOW": "no-show"},
                 "LEAD_CREATED": {"PHONE": "telefon", "EMAIL": "e-mail", "FORM": "formularz", "MANUAL": "ręcznie"}},
    },
    "Klienci": {
        "events": {"CUSTOMER_CREATED": "Stworzeni", "CONSENT_SIGNED": "Podpisane zgody",
                   "CUSTOMER_NOTE_ADDED": "Notatki", "CUSTOMER_NIP_SET": "Z NIP",
                   "CUSTOMER_DELETED": "Usunięci"},
        "dims": {"CUSTOMER_CREATED": {"DIRECT": "z kartoteki", "APPOINTMENT": "przy rezerwacji"}},
    },
    "Pojazdy": {
        "events": {"VEHICLE_CREATED": "Stworzone", "VEHICLE_NOTE_ADDED": "Notatki"},
        "dims": {"VEHICLE_CREATED": {"DIRECT": "z kartoteki", "APPOINTMENT": "przy rezerwacji"}},
    },
    "Finanse": {
        "states": {"ksef_configured": "KSeF skonfigurowany", "ksef_token_valid": "Token KSeF ważny",
                   "ksef_can_issue": "KSeF: prawo wystawiania"},
        "amounts": {"FINANCIAL_DOC_ISSUED": "Łączny przychód", "EXPENSE_RECORDED": "Łączny koszt",
                    "CASH_OPERATION": "Obrót kasowy"},
        "events": {"FINANCIAL_DOC_ISSUED": "Dokumenty przychodowe", "EXPENSE_RECORDED": "Dokumenty kosztowe",
                   "CASH_OPERATION": "Operacje kasowe"},
        "slices": [("Faktury", "FINANCIAL_DOC_ISSUED", "INVOICE"),
                   ("Paragony", "FINANCIAL_DOC_ISSUED", "RECEIPT"),
                   ("Inne dokumenty", "FINANCIAL_DOC_ISSUED", "OTHER")],
        "dims": {"FINANCIAL_DOC_ISSUED": {"INVOICE": "faktury", "RECEIPT": "paragony", "OTHER": "inne"},
                 "CASH_OPERATION": {"PAYMENT_IN": "wpłaty", "PAYMENT_OUT": "wypłaty",
                                    "MANUAL_ADJUSTMENT": "korekty"}},
        "inventory": {"ksef_cost_grosze": "Koszty z KSeF (grosze)"},
    },
    "Statystyki": {
        "events": {"STATS_CATEGORY_CREATED": "Stworzone kategorie"},
        "dims": {"STATS_CATEGORY_CREATED": {"SERVICE": "przychodowe", "COST": "kosztowe"}},
        "inventory": {"service_categories": "Kategorie przychodowe", "cost_categories": "Kategorie kosztowe",
                      "services_unassigned": "Usługi bez kategorii",
                      "cost_items_assigned": "Faktury przypisane", "cost_items_unassigned": "Faktury bez kategorii"},
    },
    "Kampanie": {
        "events": {"CAMPAIGN_CREATED": "Stworzone kampanie"},
        "slices": [("Wysłane SMS-y", "MESSAGE_SENT", "SMS")],
        "dims": {"CAMPAIGN_CREATED": {"SMS": "SMS", "EMAIL": "e-mail", "BOTH": "oba kanały"}},
    },
    "Instagram": {
        "states": {"instagram_self_profile": "Własny profil wskazany"},
        "inventory": {"instagram_profiles": "Obserwowane profile"},
        "events": {"INSTAGRAM_PROFILE_ADDED": "Dodane profile", "INSTAGRAM_CONTENT_RATED": "Ocenione treści",
                   "INSTAGRAM_AD_DETAILS_VIEWED": "Podglądy kampanii"},
        "dims": {"INSTAGRAM_CONTENT_RATED": {"COMPETITOR": "konkurencji", "AI": "wygenerowane"}},
    },
    "Ustawienia": {
        "states": {"sms_sender_name_set": "Własna nazwa SMS", "sms_sender_confirmed": "Nazwa potwierdzona",
                   "tablet_paired": "Tablet sparowany", "signature_configured": "Podpis włączony",
                   "idle_lock_enabled": "Blokada bezczynności"},
        "inventory": {"services": "Usługi", "service_packages": "Pakiety", "employees": "Pracownicy",
                      "roles": "Role", "appointment_colors": "Kolory oznaczeń",
                      "sms_templates_enabled": "Szablony SMS włączone", "sms_credits": "Kredyty SMS"},
        "events": {"SERVICE_CREATED": "Dodane usługi", "EMPLOYEE_CREATED": "Dodani pracownicy",
                   "WORKTIME_ENTRY_SAVED": "Wpisy godzin", "ROLE_CREATED": "Dodane role",
                   "APPOINTMENT_COLOR_CREATED": "Dodane kolory", "SMS_TEMPLATE_UPDATED": "Edycje szablonów",
                   "TABLET_PAIRED": "Parowania tabletu"},
        "dims": {"SERVICE_CREATED": {"SERVICE": "usługa", "PACKAGE": "pakiet"}},
    },
    "Wizyty": {
        "events": {"VISIT_CARD_SENT": "Wysłane karty wizyt", "UPSELL_USED": "Upselling",
                   "VISIT_PRICE_EDITED": "Edycje ceny", "PRICE_CONFIRMATION_REQUESTED": "Prośby o cenę (SMS)",
                   "VISIT_COMMENT_ADDED": "Komentarze", "PROTOCOL_SIGNED": "Podpisane protokoły",
                   "DAMAGE_MAP_REFILLED": "Ponowne mapy uszkodzeń"},
        "dims": {"UPSELL_USED": {"REQUESTED": "wybrane przez klienta", "CONFIRMED": "potwierdzone SMS"},
                 "PROTOCOL_SIGNED": {"CHECK_IN": "przyjęcie", "CHECK_OUT": "wydanie"},
                 "VISIT_COMMENT_ADDED": {"INTERNAL": "wewnętrzne", "FOR_CUSTOMER": "dla klienta"},
                 "VISIT_CARD_SENT": {"EMAIL": "e-mail", "SMS": "SMS"}},
    },
    "Aktywność": {
        "events": {"ACTIVITY_LOGGED": "Wpisy w historii"},
    },
}

# Kolory dwunastu pierwotnych typów zostają, żeby dashboardy „na żywo" nie zmieniły wyglądu.
FIXED_COLORS = {
    "RESERVATION_CREATED": "blue", "VISIT_CREATED": "orange", "SERVICE_CREATED": "purple",
    "PHOTO_UPLOADED": "yellow", "ACTIVITY_LOGGED": "red", "LEAD_CREATED": "green",
    "MESSAGE_SENT": "light-blue", "CAMPAIGN_CREATED": "pink", "EMPLOYEE_CREATED": "super-light-purple",
    "VISIT_CARD_SENT": "semi-dark-orange", "INSTAGRAM_PROFILE_ADDED": "dark-purple",
    "MAILBOX_CONNECTED": "dark-green",
}
# Reszta dostaje kolor z palety cyklicznie. Ręczna mapa na czterdzieści typów byłaby
# martwa przy pierwszym dodaniu metryki.
PALETTE = ["blue", "green", "orange", "purple", "red", "yellow", "light-blue", "light-green",
           "light-orange", "light-purple", "semi-dark-blue", "semi-dark-green", "semi-dark-orange",
           "semi-dark-purple", "dark-blue", "dark-green", "dark-orange", "dark-purple"]

# Typy niewymienione w MODULES, ale istniejące w backendzie (żeby panel zbiorczy i tabele
# nie gubiły serii). MAILBOX_CONNECTED jest zdarzeniem historycznym — stan czytamy z bazy.
EXTRA_TYPES = {"MAILBOX_CONNECTED": "Podłączenia poczty"}

LABELS, COLORS = {}, {}
for _mod, _cfg in MODULES.items():
    for _t, _l in _cfg.get("events", {}).items():
        LABELS.setdefault(_t, _l)
for _t, _l in EXTRA_TYPES.items():
    LABELS.setdefault(_t, _l)
for _i, _t in enumerate(LABELS):
    COLORS[_t] = FIXED_COLORS.get(_t, PALETTE[_i % len(PALETTE)])
TYPES = list(LABELS)

STATE_LABELS = {s: l for c in MODULES.values() for s, l in c.get("states", {}).items()}
INVENTORY_LABELS = {k: l for c in MODULES.values() for k, l in c.get("inventory", {}).items()}

# Kafle „dziś" — tylko to, co realnie zmienia się w ciągu dnia. Lista MUSI być podzbiorem
# typów oznaczonych `daily = true` w BusinessEventType: dla pozostałych gauge „dziś"
# w ogóle nie jest eksportowany i panel pokazywałby „—".
TODAY_TYPES = ["RESERVATION_CREATED", "VISIT_CREATED", "LEAD_CREATED", "MESSAGE_SENT",
               "PHOTO_UPLOADED", "ACTIVITY_LOGGED"]
ALL_TIME_TYPES = ["LEAD_CREATED", "MESSAGE_SENT", "CAMPAIGN_CREATED", "EMPLOYEE_CREATED",
                  "VISIT_CARD_SENT", "INSTAGRAM_PROFILE_ADDED", "MAILBOX_CONNECTED"]
# Minimalny krok. Scrape trwa 15 s, a increase() potrzebuje >=2 próbek w oknie: bez tego
# $__interval na szerokim panelu schodzi do ~5 s i KAŻDY słupek jest pusty.
MIN_STEP = "1m"

_id = [0]
def nid():
    _id[0] += 1; return _id[0]

def target(expr, legend="", instant=False, fmt="time_series", ref="A"):
    return {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": ref,
            "editorMode": "code", "range": not instant, "instant": instant, "format": fmt}

def row(title, y, collapsed=False, panels=None):
    return {"id": nid(), "type": "row", "title": title, "collapsed": collapsed,
            "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": panels or []}

def stat(title, expr, color, x, y, w=4, h=4, desc="", no_value="—"):
    return {"id": nid(), "type": "stat", "title": title, "description": desc, "datasource": DS,
            "gridPos": {"h": h, "w": w, "x": x, "y": y}, "targets": [target(expr, instant=True)],
            "fieldConfig": {"defaults": {"unit": "short", "decimals": 0,
                                         "color": {"mode": "fixed", "fixedColor": color},
                                         "noValue": no_value}, "overrides": []},
            "options": {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "colorMode": "value", "graphMode": "none", "textMode": "value",
                        "orientation": "auto", "justifyMode": "auto", "wideLayout": True}}

def timeseries(title, targets, x, y, w=12, h=8, desc="", stacked=False, bars=False, overrides=None):
    custom = {"lineWidth": 2, "fillOpacity": 80 if bars else 12, "showPoints": "never",
              "spanNulls": False, "lineInterpolation": "linear",
              "drawStyle": "bars" if bars else "line",
              "stacking": {"mode": "normal" if stacked else "none", "group": "A"}, "axisSoftMin": 0}
    return {"id": nid(), "type": "timeseries", "title": title, "description": desc, "datasource": DS,
            "gridPos": {"h": h, "w": w, "x": x, "y": y}, "targets": targets,
            "interval": MIN_STEP,
            "fieldConfig": {"defaults": {"unit": "short", "decimals": 0, "min": 0,
                                         "color": {"mode": "palette-classic"}, "custom": custom},
                            "overrides": overrides or []},
            "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                        "tooltip": {"mode": "multi", "sort": "desc"}}}

def color_override(name, color):
    return {"matcher": {"id": "byName", "options": name},
            "properties": [{"id": "color", "value": {"mode": "fixed", "fixedColor": color}}]}

def type_override(t):
    """Surowa nazwa z etykiety `type` → polska nazwa i stały kolor serii.

    Jedno zapytanie z `{{type}}` w legendzie daje serie nazwane ACTIVITY_LOGGED,
    CAMPAIGN_CREATED, MAILBOX_CONNECTED… — czytelne dla Prometheusa, nie dla człowieka.
    Osobne zapytanie na każdy typ tylko po to, żeby wpisać legendę ręcznie, kosztowałoby
    tyleż razy więcej odpytań co odświeżenie; override robi to samo po stronie Grafany.
    """
    return {"matcher": {"id": "byName", "options": t},
            "properties": [{"id": "displayName", "value": LABELS[t]},
                           {"id": "color", "value": {"mode": "fixed", "fixedColor": COLORS[t]}}]}

def barchart(title, expr, x, y, w=12, h=8, desc="", color="blue"):
    return {"id": nid(), "type": "barchart", "title": title, "description": desc, "datasource": DS,
            "gridPos": {"h": h, "w": w, "x": x, "y": y},
            "targets": [target(expr, legend="{{hour}}", instant=True, fmt="table")],
            "transformations": [
                {"id": "organize", "options": {"excludeByName": {"Time": True},
                                               "renameByName": {"hour": "Godzina", "Value": "Zdarzenia"}}},
                {"id": "sortBy", "options": {"sort": [{"field": "Godzina"}]}}],
            "fieldConfig": {"defaults": {"color": {"mode": "fixed", "fixedColor": color},
                                         "decimals": 0, "min": 0, "noValue": "—"}, "overrides": []},
            "options": {"orientation": "vertical", "xField": "Godzina", "showValue": "auto",
                        "barWidth": 0.7, "groupWidth": 0.7, "stacking": "none",
                        "legend": {"showLegend": False, "displayMode": "list", "placement": "bottom"},
                        "tooltip": {"mode": "single", "sort": "none"}}}

def sel(extra):
    return ("," + extra) if extra else ""

# ── Zapytania kumulatywne ───────────────────────────────────────────────────

def cum(t, extra):
    """„Ile łącznie od wdrożenia" — trwała suma z Redisa, nie licznik instancji.

    `max by (tenant_id)` zamiast `sum`: każda instancja backendu eksportuje TĘ SAMĄ wartość
    odczytaną z Redisa, więc `sum` pomnożyłby wynik przez liczbę instancji — a przy rolling
    deployu dawałby chwilowy skok ×2, nie do odróżnienia od prawdziwego ruchu. Zewnętrzne
    `sum` dodaje tenantów (na dashboardzie tenanta jest ich i tak dokładnie jeden).
    """
    return f'sum(max by (tenant_id) (crm_business_events_all_time{{type="{t}"{sel(extra)}}}))'

def cum_dim(t, extra):
    """Rozbicie na wymiar. Osobna metryka niż `all_time` — pod-serie sumują się do bazowej,
    więc zmieszane w jednym zapytaniu liczyłyby każde zdarzenie z wymiarem dwa razy."""
    return (f'sum by (dimension) (max by (tenant_id, dimension) '
            f'(crm_business_events_all_time_dim{{type="{t}"{sel(extra)}}}))')

def cum_slice(t, dim, extra):
    return (f'sum(max by (tenant_id) '
            f'(crm_business_events_all_time_dim{{type="{t}",dimension="{dim}"{sel(extra)}}}))')

def cum_money(t, extra):
    """Suma kwot. Backend trzyma grosze (Long, bez floatów po drodze) — dzielimy dopiero tutaj."""
    return f'sum(max by (tenant_id) (crm_business_events_sum_all_time{{type="{t}"{sel(extra)}}})) / 100'

CUM_DESC = ("Suma od WDROŻENIA metryki (nie od założenia studia), czytana z Redisa bez TTL — "
            "restart aplikacji jej nie zeruje. Rośnie monotonicznie: płaski odcinek znaczy "
            "„nic się nie działo”, nie „brak danych”.")

def cumulative_chart(title, targets, x, y, w=12, h=8, desc="", overrides=None, stacked=False):
    """Wykres narastający — linia schodkowa, nigdy nie malejąca.

    `stepAfter` zamiast interpolacji liniowej, bo źródłem jest gauge odświeżany co 5 minut:
    skos między próbkami sugerowałby ciągły przyrost, którego nikt nie zmierzył. Schodek
    mówi prawdę — „między tymi odczytami przybyło tyle".
    """
    p = timeseries(title, targets, x, y, w, h, desc, stacked=stacked, overrides=overrides)
    p["fieldConfig"]["defaults"]["custom"]["lineInterpolation"] = "stepAfter"
    p["fieldConfig"]["defaults"]["custom"]["fillOpacity"] = 8
    p["interval"] = "5m"
    return p

def money_stat(title, t, extra, x, y, w=4):
    p = stat(title, cum_money(t, extra), "green", x, y, w=w,
             desc="Suma dokładnych kwot BRUTTO zapisanych na dokumentach (grosze / 100). "
                  "Kwoty nie są przeliczane z netta — brutto podane przez człowieka jest "
                  "źródłem prawdy. " + CUM_DESC)
    p["fieldConfig"]["defaults"].update({"unit": "currencyPLN", "decimals": 2})
    return p

def bool_stat(title, state, extra, x, y, w=3):
    p = stat(title, f'max(max by (tenant_id) (crm_tenant_state{{state="{state}"{sel(extra)}}}))',
             "green", x, y, w=w,
             desc="Stan bieżący czytany Z BAZY co 5 min — obejmuje też konfigurację sprzed "
                  "wdrożenia metryk i natychmiast pokazuje wyłączenie funkcji. "
                  "`—` znaczy, że eksporter stanów nie działa.")
    p["fieldConfig"]["defaults"]["mappings"] = [{"type": "value", "options": {
        "0": {"text": "Nie", "color": "red", "index": 0},
        "1": {"text": "Tak", "color": "green", "index": 1}}}]
    return p

def inventory_stat(title, kind, extra, x, y, w=3):
    return stat(title, f'max(max by (tenant_id) (crm_tenant_inventory{{kind="{kind}"{sel(extra)}}}))',
                "text", x, y, w=w,
                desc="Stan bieżący z bazy (co 5 min). Może MALEĆ — to nie licznik zdarzeń, "
                     "tylko odpowiedź na pytanie „ile jest teraz”.")

def adoption_stat(title, state, x, y, w=3):
    """Udział tenantów z włączoną funkcją — mianownik to liczba studiów, nie liczba serii."""
    expr = (f'sum(max by (tenant_id) (crm_tenant_state{{state="{state}"}})) / '
            f'count(max by (tenant_id) (crm_tenant_state{{state="{state}"}}))')
    p = stat(title, expr, "blue", x, y, w=w,
             desc="Odsetek studiów, które mają tę funkcję włączoną (liczone ze stanów w bazie).")
    p["fieldConfig"]["defaults"].update({"unit": "percentunit", "decimals": 0})
    p["options"]["colorMode"] = "background"
    return p

# ── Panele „na żywo" (dashboardy live-*) ────────────────────────────────────

def kpi_row(y, extra):
    return [stat(f"{LABELS[t]} dziś",
                 f'sum(max by (tenant_id) (crm_business_events_today{{type="{t}"{sel(extra)}}}))',
                 COLORS[t], i * 4, y,
                 desc="Licznik od północy (strefa studia), czytany z Redisa co 15 s. "
                      "`—` zamiast liczby oznacza, że metryka w ogóle nie dociera do Prometheusa.")
            for i, t in enumerate(TODAY_TYPES)]

def all_time_row(y, extra):
    """Stan, nie ruch: „ile w sumie", nie „ile dziś".

    Poczta konfigurowana raz w życiu i profil IG dodany w zeszłym miesiącu są na kaflu dziennym
    nie do odróżnienia od studia, które nie zrobiło nic — oba pokazują zero.
    """
    panels = [stat(LABELS[t], cum(t, extra), COLORS[t], i * 3, y, w=3, desc=CUM_DESC)
              for i, t in enumerate(ALL_TIME_TYPES)]
    # Siedem kafli po 3 zostawia dokładnie 3 kolumny — zdrowie potoku jedzie z nimi w jednej linii,
    # bo bez niego wszystkie liczby obok są bez gwarancji kompletności.
    panels.append(stat("Odrzucone przez potok", "sum(crm_live_metrics_pipeline_dropped)", "red", 21, y, w=3,
                       desc="Zdarzenia zgubione przez pełną kolejkę lub nieudany zapis do Redisa. "
                            "Cokolwiek > 0 znaczy, że liczby na tym dashboardzie są niepełne.", no_value="0"))
    return panels

def inc(t, extra, by=None):
    grp = f" by ({by})" if by else ""
    return f'sum{grp}(increase(crm_business_events_total{{type="{t}"{sel(extra)}}}[$__rate_interval]))'

def charts(y, extra, hod_extra):
    p = []
    p.append(timeseries("Rezerwacje na żywo", [target(inc("RESERVATION_CREATED", extra), "Rezerwacje")], 0, y,
                        desc="TEMPO, nie suma: ile rezerwacji utworzono w każdym przedziale. Wykres wraca "
                             "do zera, gdy nic się nie dzieje — „ile łącznie” jest na dashboardzie "
                             "Zaangażowanie. Krok minimalny 1 min, bo increase() na oknie krótszym "
                             "niż dwa scrape'y nie zwraca nic.",
                        bars=True, overrides=[color_override("Rezerwacje", "blue")]))
    p.append(barchart("O której klienci rezerwują (7 dni)",
                      f'sum by (hour) (max by (hour, tenant_id) (crm_business_events_hour_of_day{{type="RESERVATION_CREATED"{sel(hod_extra)}}}))',
                      12, y, desc="Rozkład godzinowy rezerwacji z ostatnich 7 dni, godzina lokalna studia (Europe/Warsaw)."))
    y += 8
    p.append(timeseries("Wizyty: bezpośrednie vs z rezerwacji",
                        [target(inc("VISIT_CREATED", extra, "dimension"), "{{dimension}}")], 0, y,
                        desc="Lejek konwersji: DIRECT = wizyta założona z palca (walk-in), "
                             "FROM_RESERVATION = przekształcenie istniejącej rezerwacji.",
                        stacked=True, bars=True,
                        overrides=[color_override("FROM_RESERVATION", "green"), color_override("DIRECT", "orange")]))
    p.append(timeseries("Zdjęcia i multimedia wg miejsca",
                        [target(inc("PHOTO_UPLOADED", extra, "dimension"), "{{dimension}}")], 12, y,
                        desc="Udane uploady zdjęć: VISIT (wizyta), VEHICLE (karta pojazdu), "
                             "CHECKIN (QR z telefonu), BATCH_ORDER (zlecenie zbiorcze).",
                        stacked=True, bars=True))
    y += 8
    p.append(timeseries("Leady wg źródła",
                        [target(inc("LEAD_CREATED", extra, "dimension"), "{{dimension}}")], 0, y,
                        desc="Skąd przychodzą leady: PHONE (telefon), EMAIL (poczta), "
                             "FORM (formularz na stronie), MANUAL (dodany ręcznie).",
                        stacked=True, bars=True,
                        overrides=[color_override("FORM", "green"), color_override("MANUAL", "blue"),
                                   color_override("PHONE", "orange"), color_override("EMAIL", "purple")]))
    p.append(timeseries("Wysłane wiadomości wg kanału",
                        [target(inc("MESSAGE_SENT", extra, "dimension"), "{{dimension}}")], 12, y,
                        desc="Tylko wysyłki, które naprawdę wyszły do dostawcy — blokady (brak modułu, "
                             "zgody, kredytów) się nie liczą. SMS i EMAIL to wysyłka systemowa "
                             "(przypomnienia, kampanie, karty wizyt), MAILBOX to mail napisany ręcznie "
                             "w module Poczta. Wszystkie maile razem = EMAIL + MAILBOX.",
                        stacked=True, bars=True,
                        overrides=[color_override("SMS", "green"), color_override("EMAIL", "light-blue"),
                                   color_override("MAILBOX", "purple")]))
    y += 8
    p.append(timeseries("Log aktywności", [target(inc("ACTIVITY_LOGGED", extra), "Wpisy")], 0, y,
                        desc="Przyrost rekordów w historii aktywności — sam fakt powstania wpisu systemowego.",
                        bars=True, overrides=[color_override("Wpisy", "red")]))
    p.append(timeseries("Wizyty: upselling i podpisy na żywo",
                        [target(inc("UPSELL_USED", extra), "Upselling", ref="A"),
                         target(inc("PROTOCOL_SIGNED", extra), "Podpisane protokoły", ref="B"),
                         target(inc("VISIT_PRICE_EDITED", extra), "Edycje ceny", ref="C")], 12, y,
                        desc="Co dzieje się w trakcie realizacji wizyt. Rozbicia (przyjęcie/wydanie, "
                             "wybrane/potwierdzone) są na dashboardzie Zaangażowanie.",
                        bars=True,
                        overrides=[color_override("Upselling", "green"),
                                   color_override("Podpisane protokoły", "blue"),
                                   color_override("Edycje ceny", "orange")]))
    return p, y + 8

def tenants_table(y):
    """Jedno zapytanie + pivot. Sześć osobnych zapytań sklejanych joinem po tenant_id
    dawało kolumny, które rozjeżdżały się przy braku którejkolwiek serii."""
    # groupingToMatrix nazywa pierwszą kolumnę "<rowField>\\<columnField>"; wariant z dwoma
    # ukośnikami trzymamy na wypadek innej wersji Grafany — nadmiarowy wpis jest ignorowany.
    rename = {"tenant\\type": "Tenant", "tenant\\\\type": "Tenant"}
    rename.update({t: LABELS[t] for t in TYPES})
    # Tylko typy o charakterze dziennym. Dosypanie tu poczty czy pracowników dałoby kolumny,
    # w których każdy tenant ma zero przez 364 dni w roku — szum kosztem czytelności tabeli.
    types_filter = "|".join(TODAY_TYPES)
    return {"id": nid(), "type": "table", "title": "Tenanci — dziś", "datasource": DS,
            "description": "Liczniki od północy per tenant. Jedno zapytanie, przestawione na kolumny.",
            "gridPos": {"h": 12, "w": 24, "x": 0, "y": y},
            "targets": [target(f'max by (tenant, type) (crm_business_events_today{{type=~"{types_filter}"}})',
                               instant=True, fmt="table")],
            "transformations": [
                {"id": "groupingToMatrix", "options": {"columnField": "type", "rowField": "tenant",
                                                       "valueField": "Value", "emptyValue": "zero"}},
                {"id": "organize", "options": {"renameByName": rename}},
                {"id": "sortBy", "options": {"sort": [{"field": LABELS["RESERVATION_CREATED"], "desc": True}]}}],
            "fieldConfig": {"defaults": {"decimals": 0, "noValue": "0",
                                         "custom": {"align": "auto", "filterable": True}},
                            "overrides": [{"matcher": {"id": "byName", "options": "Tenant"},
                                           "properties": [{"id": "custom.width", "value": 320}]}]},
            "options": {"showHeader": True, "cellHeight": "sm", "footer": {"show": False}}}

def pipeline_row(y):
    return [stat("W kolejce", "sum(crm_live_metrics_pipeline_queued)", "text", 0, y,
                 desc="Zdarzenia czekające na zapis (suma instancji).", no_value="0"),
            stat("Przyjęte", "sum(crm_live_metrics_pipeline_accepted)", "text", 4, y, no_value="0"),
            stat("Zapisane do Redisa", "sum(crm_live_metrics_pipeline_written)", "green", 8, y, no_value="0"),
            stat("Nieudane partie", "sum(crm_live_metrics_pipeline_failed_batches)", "red", 12, y, no_value="0"),
            stat("Rozgłoszone (SSE)", "sum(crm_live_metrics_pipeline_broadcast)", "text", 16, y, no_value="0"),
            stat("Subskrybenci SSE", "sum(crm_live_metrics_sse_subscribers)", "text", 20, y, no_value="0")]

def state_health_stat(x, y, w=4):
    p = stat("Wiek stanów z bazy", "time() - max(crm_tenant_state_refreshed_seconds)", "text", x, y, w=w,
             desc="Ile sekund temu eksporter stanów ostatnio się domknął. Nieudany cykl ZOSTAWIA "
                  "poprzednie wartości, więc bez tego kafla „wszyscy mają zero usług” i „eksporter "
                  "nie żyje od godziny” wyglądałyby identycznie. Powyżej ~5 min = awaria.",
             no_value="—")
    p["fieldConfig"]["defaults"].update({"unit": "s", "decimals": 0})
    p["fieldConfig"]["defaults"]["thresholds"] = {"mode": "absolute", "steps": [
        {"color": "green", "value": None}, {"color": "orange", "value": 400}, {"color": "red", "value": 900}]}
    p["fieldConfig"]["defaults"]["color"] = {"mode": "thresholds"}
    return p

# ── Dashboard „Zaangażowanie" (kumulatywny, per moduł) ──────────────────────

def pack(tiles, y):
    """Układa kafle od lewej, zawijając po 24 kolumnach. Zwraca (panele, kolejne y)."""
    x, out = 0, []
    for make, w in tiles:
        if x + w > 24:
            x, y = 0, y + 4
        out.append(make(x, y, w))
        x += w
    return out, (y + 4 if out else y)

def module_panels(name, cfg, extra, y0):
    """Jeden moduł: kafle (stany, inwentarz, kwoty, liczniki) + wykresy narastające."""
    tiles = []
    for state, label in cfg.get("states", {}).items():
        tiles.append((lambda x, y, w, s=state, l=label: bool_stat(l, s, extra, x, y, w), 3))
    for t, label in cfg.get("amounts", {}).items():
        tiles.append((lambda x, y, w, tt=t, l=label: money_stat(l, tt, extra, x, y, w), 4))
    for kind, label in cfg.get("inventory", {}).items():
        tiles.append((lambda x, y, w, k=kind, l=label: inventory_stat(l, k, extra, x, y, w), 3))
    for t, label in cfg.get("events", {}).items():
        tiles.append((lambda x, y, w, tt=t, l=label: stat(l, cum(tt, extra), COLORS[tt], x, y, w,
                                                          desc=CUM_DESC), 3))
    for label, t, dim in cfg.get("slices", []):
        tiles.append((lambda x, y, w, tt=t, d=dim, l=label:
                      stat(l, cum_slice(tt, d, extra), COLORS[tt], x, y, w,
                           desc=f"Pod-seria `{d}` typu {tt}. " + CUM_DESC), 3))

    panels, y = pack(tiles, y0)

    events = list(cfg.get("events", {}))
    if events:
        expr = ('sum by (type) (max by (tenant_id, type) '
                f'(crm_business_events_all_time{{type=~"{"|".join(events)}"{sel(extra)}}}))')
        panels.append(cumulative_chart(f"{name} — narastająco", [target(expr, "{{type}}")], 0, y, w=24,
                                       desc=CUM_DESC, overrides=[type_override(t) for t in events]))
        y += 8

    dim_charts = list(cfg.get("dims", {}).items())
    for i, (t, values) in enumerate(dim_charts):
        x = 0 if i % 2 == 0 else 12
        if i % 2 == 0 and i > 0:
            y += 8
        panels.append(cumulative_chart(
            f"{LABELS.get(t, t)} — wg wymiaru", [target(cum_dim(t, extra), "{{dimension}}")], x, y,
            desc="Rozbicie sumy od wdrożenia na wartości wymiaru. Suma słupków odpowiada "
                 "kaflowi zbiorczemu tego typu. " + CUM_DESC,
            stacked=True,
            overrides=[color_override(v, PALETTE[j % len(PALETTE)]) for j, v in enumerate(values)]))
    if dim_charts:
        y += 8
    return panels, y

def engagement_dashboard(uid, title, desc, extra, templating, header_panels=None):
    _id[0] = 0
    panels, y = [], 0
    if header_panels:
        hp, y = header_panels(y)
        panels += hp
    for i, (name, cfg) in enumerate(MODULES.items()):
        # Pierwszy moduł rozwinięty, reszta zwinięta: trzynaście rozwiniętych wierszy to
        # kilkadziesiąt zapytań przy każdym odświeżeniu, z czego widać jeden ekran.
        # Panele zwiniętego wiersza siedzą w `row.panels` i Grafana rozkłada je dopiero
        # po rozwinięciu — dlatego liczą się od y=0, a sam wiersz zajmuje jeden rząd.
        if i > 0:
            inner, _ = module_panels(name, cfg, extra, 0)
            panels.append(row(name, y, collapsed=True, panels=inner))
            y += 1
        else:
            panels.append(row(name, y))
            inner, y = module_panels(name, cfg, extra, y + 1)
            panels += inner
    return dashboard(uid, title, desc, panels, templating, refresh="1m", time_from="now-30d")

# ── Dashboard „Adopcja" (platforma) ─────────────────────────────────────────

def matrix_table(title, expr, column_field, rename_map, y, desc="", h=14, mappings=None):
    rename = {f"tenant\\{column_field}": "Tenant", f"tenant\\\\{column_field}": "Tenant"}
    rename.update(rename_map)
    defaults = {"decimals": 0, "noValue": "0", "custom": {"align": "auto", "filterable": True}}
    if mappings:
        defaults["mappings"] = mappings
    return {"id": nid(), "type": "table", "title": title, "datasource": DS, "description": desc,
            "gridPos": {"h": h, "w": 24, "x": 0, "y": y},
            "targets": [target(expr, instant=True, fmt="table")],
            "transformations": [
                {"id": "groupingToMatrix", "options": {"columnField": column_field, "rowField": "tenant",
                                                       "valueField": "Value", "emptyValue": "zero"}},
                {"id": "organize", "options": {"renameByName": rename}}],
            "fieldConfig": {"defaults": defaults,
                            "overrides": [{"matcher": {"id": "byName", "options": "Tenant"},
                                           "properties": [{"id": "custom.width", "value": 300}]}]},
            "options": {"showHeader": True, "cellHeight": "sm", "footer": {"show": False}}}

def ranking_panel(y):
    expr = "topk(15, sum by (tenant) (max by (tenant_id, tenant, type) (crm_business_events_all_time)))"
    return {"id": nid(), "type": "bargauge", "title": "Najbardziej zaangażowane studia (top 15)",
            "datasource": DS, "gridPos": {"h": 12, "w": 24, "x": 0, "y": y},
            "description": "Suma WSZYSTKICH zdarzeń biznesowych od wdrożenia metryk, per studio. "
                           "Nie mierzy wielkości firmy, tylko intensywność korzystania z CRM-a.",
            "targets": [target(expr, legend="{{tenant}}", instant=True)],
            "fieldConfig": {"defaults": {"decimals": 0, "min": 0, "noValue": "—",
                                         "color": {"mode": "continuous-BlPu"}}, "overrides": []},
            "options": {"displayMode": "gradient", "orientation": "horizontal",
                        "reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                        "showUnfilled": True, "valueMode": "color"}}

def adoption_header(y):
    panels = [row("Adopcja funkcji — cała platforma", y)]
    y += 1
    tiles = [(lambda x, yy, w, s=s, l=l: adoption_stat(l, s, x, yy, w), 3)
             for s, l in STATE_LABELS.items()]
    tiled, y = pack(tiles, y)
    panels += tiled
    panels.append(row("Kto co ma", y)); y += 1
    panels.append(matrix_table(
        "Tenanci × funkcje", "max by (tenant, state) (crm_tenant_state)", "state",
        STATE_LABELS, y,
        desc="Stan każdej funkcji u każdego studia, prosto z bazy. Jedno zapytanie i pivot — "
             "sklejanie kilkunastu zapytań joinem po tenant_id rozjeżdżało kolumny przy "
             "brakującej serii.",
        mappings=[{"type": "value", "options": {"0": {"text": "—", "index": 0},
                                                "1": {"text": "✓", "index": 1}}}]))
    y += 14
    panels.append(matrix_table(
        "Tenanci × zdarzenia od wdrożenia", "max by (tenant, type) (crm_business_events_all_time)",
        "type", {t: LABELS[t] for t in TYPES}, y,
        desc="Ile każde studio zrobiło w każdym module od wdrożenia metryk. Kolumny sortowalne — "
             "zero w kolumnie znaczy „nigdy nie użyli tej funkcji”."))
    y += 14
    panels.append(ranking_panel(y)); y += 12
    panels.append(row("Zdrowie telemetrii", y)); y += 1
    panels += pipeline_row(y)
    panels.append(state_health_stat(0, y + 4))
    y += 8
    return panels, y

# ── Szkielet dashboardu ─────────────────────────────────────────────────────

def dashboard(uid, title, desc, panels, templating, refresh="10s", time_from="now-6h"):
    return {"uid": uid, "title": title, "description": desc, "tags": ["crm", "live-metrics"],
            "timezone": "Europe/Warsaw", "schemaVersion": 39, "version": 1, "editable": False,
            "refresh": refresh, "graphTooltip": 1,
            "time": {"from": time_from, "to": "now"},
            "timepicker": {"refresh_intervals": ["10s", "30s", "1m", "5m", "15m"]},
            "templating": {"list": templating}, "annotations": {"list": []},
            "links": [{"title": "Platforma — na żywo", "type": "link", "url": "/d/crm-live-platform"},
                      {"title": "Tenant — na żywo", "type": "link", "url": "/d/crm-live-tenant"},
                      {"title": "Zaangażowanie tenanta", "type": "link", "url": "/d/crm-engagement-tenant"},
                      {"title": "Adopcja — platforma", "type": "link", "url": "/d/crm-adoption-platform"}],
            "panels": panels}

def query_var(name, label, query, hide=0):
    return {"name": name, "label": label, "type": "query", "datasource": DS,
            "query": {"query": query, "refId": name}, "definition": query,
            "refresh": 2, "sort": 1, "includeAll": False, "multi": False,
            "current": {}, "options": [], "hide": hide}

def write(name, doc):
    with open(os.path.join(OUT, name), "w") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=2)

# Filtrujemy po tenant_id (UUID), nigdy po nazwie studia.
#
# Grafana escapuje wartość zmiennej wstawianą do zapytania Prometheusa — apostrof
# w "Maciej Sienkiewicz's Detailing Studio" wjeżdża do matchera jako \', więc
# `tenant="$tenant"` nie pasuje do niczego. Tytuł wiersza wyglądał przy tym poprawnie,
# bo tam interpolacja jest zwykłym tekstem, i to właśnie mylnie sugerowało, że zmienna
# działa. Identyfikator nie ma znaków, które cokolwiek escapuje, i nie zmienia się przy
# zmianie nazwy studia.
TENANT_SEL = 'tenant_id="$tenant_id"'

# Wybierak zaangażowania czyta tenantów ze STANÓW, nie ze zdarzeń: `crm_tenant_state`
# obejmuje wszystkie studia z bazy, także te, które nie zrobiły jeszcze nic — czyli
# dokładnie te, o które pyta się przy ocenie adopcji.
ENGAGEMENT_VARS = [
    query_var("tenant_id", "Studio (ID)", "label_values(crm_tenant_state, tenant_id)"),
    query_var("tenant_name", "Studio",
              'label_values(crm_tenant_state{tenant_id="$tenant_id"}, tenant)', hide=2),
]
LIVE_VARS = [
    query_var("tenant_id", "Studio (ID)", "label_values(crm_business_events_today, tenant_id)"),
    query_var("tenant_name", "Studio",
              'label_values(crm_business_events_today{tenant_id="$tenant_id"}, tenant)', hide=2),
]

# ── live — platforma ────────────────────────────────────────────────────────
_id[0] = 0
p = [row("Dziś — cała platforma", 0)] + kpi_row(1, "")
p.append(row("Od początku — cała platforma", 5)); p += all_time_row(6, "")
p.append(row("Na żywo", 10))
c, y = charts(11, "", 'tenant_id="_platform"'); p += c
p.append(row("Tenanci", y)); p.append(tenants_table(y + 1)); y += 13
p.append(row("Potok metryk (ingest → Redis → Prometheus / WebSocket)", y)); p += pipeline_row(y + 1)
write("live-platform.json", dashboard(
    "crm-live-platform", "Live metrics — platforma",
    "TEMPO zdarzeń biznesowych wszystkich tenantów. Pytanie „ile łącznie” obsługuje dashboard "
    "Zaangażowanie, a „kto z czego korzysta” — Adopcja. Źródło: /actuator/prometheus.", p, []))

# ── live — tenant ───────────────────────────────────────────────────────────
_id[0] = 0
p = [row("Dziś — $tenant_name", 0)] + kpi_row(1, TENANT_SEL)
p.append(row("Od początku — $tenant_name", 5)); p += all_time_row(6, TENANT_SEL)
p.append(row("Na żywo — $tenant_name", 10))
c, y = charts(11, TENANT_SEL, TENANT_SEL); p += c
write("live-tenant.json", dashboard(
    "crm-live-tenant", "Live metrics — tenant",
    "Te same metryki tempa co na dashboardzie platformy, dla jednego wybranego studia. "
    "Wybierak operuje na tenant_id; nazwa studia jest w tytułach wierszy.", p, LIVE_VARS))

# ── zaangażowanie — tenant ──────────────────────────────────────────────────
write("engagement-tenant.json", engagement_dashboard(
    "crm-engagement-tenant", "Zaangażowanie — tenant",
    "Ile studio ZROBIŁO od wdrożenia metryk, moduł po module: liczniki narastające (nigdy nie "
    "maleją), stany funkcji czytane z bazy i sumy kwot. Wiersze poza pierwszym są zwinięte — "
    "rozwiń ten moduł, który Cię interesuje.",
    TENANT_SEL, ENGAGEMENT_VARS))

# ── adopcja — platforma ─────────────────────────────────────────────────────
write("adoption-platform.json", engagement_dashboard(
    "crm-adoption-platform", "Adopcja — platforma",
    "Kto z czego korzysta: odsetek studiów z włączoną funkcją, tabela tenant × funkcja, "
    "ranking zaangażowania i te same liczniki narastające dla całej platformy.",
    "", [], header_panels=adoption_header))

print(f"ok — {len(TYPES)} typów, {len(STATE_LABELS)} stanów, {len(INVENTORY_LABELS)} wielkości, 4 dashboardy")

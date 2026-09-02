"""Generuje dashboardy live-metrics dla Grafany (provisionowane z repo).

Uruchom po każdej zmianie:  python3 deploy/monitoring/grafana/generate_dashboards.py

JSON dashboardów jest artefaktem, nie źródłem prawdy — ręczna edycja rozjeżdża oba pliki,
bo dzielą ten sam zestaw paneli. Reguły, które łatwo złamać ręcznie, są tu wymuszone:
minimalny krok wykresów i filtrowanie po `tenant_id`, nigdy po nazwie tenanta.
"""
import json, os
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'provisioning', 'dashboards')
DS = {"type": "prometheus", "uid": "prometheus"}
LABELS = {"RESERVATION_CREATED": "Rezerwacje", "VISIT_CREATED": "Wizyty", "SERVICE_CREATED": "Nowe usługi",
          "PHOTO_UPLOADED": "Zdjęcia", "ACTIVITY_LOGGED": "Aktywność"}
COLORS = {"RESERVATION_CREATED": "blue", "VISIT_CREATED": "orange", "SERVICE_CREATED": "purple",
          "PHOTO_UPLOADED": "yellow", "ACTIVITY_LOGGED": "red"}
TYPES = list(LABELS)
# Minimalny krok. Scrape trwa 15 s, a increase() potrzebuje >=2 próbek w oknie: bez tego
# $__interval na szerokim panelu schodzi do ~5 s i KAŻDY słupek jest pusty.
MIN_STEP = "1m"

_id = [0]
def nid():
    _id[0] += 1; return _id[0]

def target(expr, legend="", instant=False, fmt="time_series", ref="A"):
    return {"datasource": DS, "expr": expr, "legendFormat": legend, "refId": ref,
            "editorMode": "code", "range": not instant, "instant": instant, "format": fmt}

def row(title, y):
    return {"id": nid(), "type": "row", "title": title, "collapsed": False,
            "gridPos": {"h": 1, "w": 24, "x": 0, "y": y}, "panels": []}

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

def kpi_row(y, extra):
    panels = [stat(f"{LABELS[t]} dziś",
                   f'sum(max by (tenant_id) (crm_business_events_today{{type="{t}"{sel(extra)}}}))',
                   COLORS[t], i * 4, y,
                   desc="Licznik od północy (strefa studia), czytany z Redisa co 15 s. "
                        "`—` zamiast liczby oznacza, że metryka w ogóle nie dociera do Prometheusa.")
              for i, t in enumerate(TYPES)]
    panels.append(stat("Odrzucone przez potok", "sum(crm_live_metrics_pipeline_dropped)", "red", 20, y,
                       desc="Zdarzenia zgubione przez pełną kolejkę lub nieudany zapis do Redisa. "
                            "Cokolwiek > 0 znaczy, że liczby na tym dashboardzie są niepełne.", no_value="0"))
    return panels

def inc(t, extra, by=None):
    grp = f" by ({by})" if by else ""
    return f'sum{grp}(increase(crm_business_events_total{{type="{t}"{sel(extra)}}}[$__rate_interval]))'

def charts(y, extra, hod_extra):
    p = []
    p.append(timeseries("Rezerwacje na żywo", [target(inc("RESERVATION_CREATED", extra), "Rezerwacje")], 0, y,
                        desc="Ile rezerwacji utworzono w każdym przedziale. Krok minimalny 1 min — "
                             "increase() na oknie krótszym niż dwa scrape'y nie zwraca nic.",
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
    p.append(timeseries("Katalog usług — nowości",
                        [target(inc("SERVICE_CREATED", extra, "dimension"), "{{dimension}}")], 0, y,
                        desc="Kiedy studio rozszerza ofertę: SERVICE = pojedyncza usługa, PACKAGE = pakiet.",
                        stacked=True, bars=True,
                        overrides=[color_override("SERVICE", "purple"), color_override("PACKAGE", "pink")]))
    p.append(timeseries("Log aktywności", [target(inc("ACTIVITY_LOGGED", extra), "Wpisy")], 12, y,
                        desc="Przyrost rekordów w historii aktywności — sam fakt powstania wpisu systemowego.",
                        bars=True, overrides=[color_override("Wpisy", "red")]))
    y += 8
    p.append(timeseries("Wszystkie zdarzenia — tempo na minutę",
                        [target(f'sum by (type) (rate(crm_business_events_total{{{extra}}}[$__rate_interval])) * 60',
                                "{{type}}")], 0, y, w=24,
                        desc="Zdarzeń na minutę per typ (wygładzone rate)."))
    return p, y + 8

def tenants_table(y):
    """Jedno zapytanie + pivot. Sześć osobnych zapytań sklejanych joinem po tenant_id
    dawało kolumny, które rozjeżdżały się przy braku którejkolwiek serii."""
    # groupingToMatrix nazywa pierwszą kolumnę "<rowField>\\<columnField>"; wariant z dwoma
    # ukośnikami trzymamy na wypadek innej wersji Grafany — nadmiarowy wpis jest ignorowany.
    rename = {"tenant\\type": "Tenant", "tenant\\\\type": "Tenant"}
    rename.update({t: LABELS[t] for t in TYPES})
    return {"id": nid(), "type": "table", "title": "Tenanci — dziś", "datasource": DS,
            "description": "Liczniki od północy per tenant. Jedno zapytanie, przestawione na kolumny.",
            "gridPos": {"h": 12, "w": 24, "x": 0, "y": y},
            "targets": [target("max by (tenant, type) (crm_business_events_today)", instant=True, fmt="table")],
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
            stat("Rozgłoszone (WS/SSE)", "sum(crm_live_metrics_pipeline_broadcast)", "text", 16, y, no_value="0"),
            stat("Subskrybenci SSE", "sum(crm_live_metrics_sse_subscribers)", "text", 20, y, no_value="0")]

def dashboard(uid, title, desc, panels, templating):
    return {"uid": uid, "title": title, "description": desc, "tags": ["crm", "live-metrics"],
            "timezone": "Europe/Warsaw", "schemaVersion": 39, "version": 1, "editable": False,
            "refresh": "10s", "graphTooltip": 1,
            "time": {"from": "now-6h", "to": "now"},
            "timepicker": {"refresh_intervals": ["10s", "30s", "1m", "5m", "15m"]},
            "templating": {"list": templating}, "annotations": {"list": []},
            "links": [{"title": "Platforma", "type": "link", "url": "/d/crm-live-platform"},
                      {"title": "Tenant", "type": "link", "url": "/d/crm-live-tenant"}],
            "panels": panels}

# ── platforma ───────────────────────────────────────────────────────────────
_id[0] = 0
p = [row("Dziś — cała platforma", 0)] + kpi_row(1, "")
p.append(row("Na żywo", 5))
c, y = charts(6, "", 'tenant_id="_platform"'); p += c
p.append(row("Tenanci", y)); p.append(tenants_table(y + 1)); y += 13
p.append(row("Potok metryk (ingest → Redis → Prometheus / WebSocket)", y)); p += pipeline_row(y + 1)
json.dump(dashboard("crm-live-platform", "Live metrics — platforma",
                    "Zdarzenia biznesowe wszystkich tenantów w czasie rzeczywistym: rezerwacje, wizyty "
                    "(wg źródła), nowe usługi, zdjęcia, log aktywności. Źródło: /actuator/prometheus.",
                    p, []),
          open(os.path.join(OUT, "live-platform.json"), "w"), ensure_ascii=False, indent=2)

# ── tenant ──────────────────────────────────────────────────────────────────
_id[0] = 0
# Filtrujemy po tenant_id (UUID), nigdy po nazwie studia.
#
# Grafana escapuje wartość zmiennej wstawianą do zapytania Prometheusa — apostrof
# w "Maciej Sienkiewicz's Detailing Studio" wjeżdża do matchera jako \', więc
# `tenant="$tenant"` nie pasuje do niczego. Tytuł wiersza wyglądał przy tym poprawnie,
# bo tam interpolacja jest zwykłym tekstem, i to właśnie mylnie sugerowało, że zmienna
# działa. Identyfikator nie ma znaków, które cokolwiek escapuje, i nie zmienia się przy
# zmianie nazwy studia.
extra = 'tenant_id="$tenant_id"' 
p = [row("Dziś — $tenant_name", 0)] + kpi_row(1, extra)
p.append(row("Na żywo — $tenant_name", 5))
c, y = charts(6, extra, extra); p += c
def query_var(name, label, query, hide=0):
    return {"name": name, "label": label, "type": "query", "datasource": DS,
            "query": {"query": query, "refId": name}, "definition": query,
            "refresh": 2, "sort": 1, "includeAll": False, "multi": False,
            "current": {}, "options": [], "hide": hide}

tenant_vars = [
    # Wybierak operuje na identyfikatorach — patrz komentarz przy `extra`.
    query_var("tenant_id", "Studio (ID)", "label_values(crm_business_events_today, tenant_id)"),
    # Nazwa doklejana do tytułów, żeby po wybraniu ID było widać, o które studio chodzi.
    # Ukryta (hide=2): nie jest do wybierania, jest wyprowadzona z tenant_id.
    query_var("tenant_name", "Studio",
              'label_values(crm_business_events_today{tenant_id="$tenant_id"}, tenant)', hide=2),
]
json.dump(dashboard("crm-live-tenant", "Live metrics — tenant",
                    "Te same metryki co na dashboardzie platformy, dla jednego wybranego tenanta "
                    "(studia). Wybierak operuje na tenant_id; nazwa studia jest w tytułach wierszy.",
                    p, tenant_vars),
          open(os.path.join(OUT, "live-tenant.json"), "w"), ensure_ascii=False, indent=2)
print("ok")

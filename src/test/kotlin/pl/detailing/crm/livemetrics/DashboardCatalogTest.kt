package pl.detailing.crm.livemetrics

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import java.io.File

/**
 * Pilnuje, żeby katalog metryk w generatorze dashboardów nie rozjechał się z kodem.
 *
 * Rozjazd jest tu wyjątkowo cichy w OBIE strony:
 *
 *  - dodany typ zdarzenia bez panelu = metryka, która trafia do Prometheusa i nigdy nie jest
 *    oglądana; nikt się nie dowie, bo nic nie jest zepsute;
 *  - panel pytający o typ, którego już nie ma, pokazuje `—` — czyli dokładnie to samo,
 *    co awaria scrape'u. Administrator widzi „brak danych" i szuka problemu w Prometheusie.
 *
 * Kompilator nie wyłapie żadnego z tych przypadków, bo po drugiej stronie jest JSON.
 * Test czyta wygenerowane dashboardy i domyka pętlę.
 *
 * Gdy ten test padnie po dodaniu typu: dopisz go do `MODULES` w
 * `deploy/monitoring/grafana/generate_dashboards.py` i przegeneruj dashboardy.
 */
class DashboardCatalogTest {

    /**
     * Treść dashboardów z rozescapowanymi cudzysłowami.
     *
     * Zapytania PromQL siedzą w JSON-ie jako wartości pól `expr`, więc w pliku wyglądają tak:
     * `type=\"TASK_CREATED\"`. Bez tej normalizacji każdy regex poniżej nie trafiałby w nic —
     * i test „przechodziłby” przy dowolnym rozjeździe, bo zbiór znalezionych typów byłby pusty.
     */
    private val dashboards = File("deploy/monitoring/grafana/provisioning/dashboards")
        .listFiles { f -> f.extension == "json" }
        ?.associate { it.name to it.readText().replace("\\\"", "\"") }
        ?: emptyMap()

    /** Nazwy typów cytowane w zapytaniach PromQL: `type="X"` oraz `type=~"X|Y"`. */
    private val referencedTypes: Set<String> by lazy {
        val single = Regex("""type="([A-Z_]+)"""")
        val multi = Regex("""type=~"([A-Z_|]+)"""")
        dashboards.values.flatMapTo(HashSet()) { json ->
            single.findAll(json).map { it.groupValues[1] } +
                multi.findAll(json).flatMap { it.groupValues[1].split("|") }
        }.filter { it.isNotBlank() }.toSet()
    }

    @Test
    fun `dashboards were generated at all`() {
        assertTrue(dashboards.size >= 4) {
            "Oczekiwano co najmniej 4 dashboardów, znaleziono ${dashboards.keys}. " +
                "Uruchom: python3 deploy/monitoring/grafana/generate_dashboards.py"
        }
    }

    @Test
    fun `every business event type has a panel somewhere`() {
        val missing = BusinessEventType.entries.map { it.name } - referencedTypes
        assertTrue(missing.isEmpty()) {
            "Typy zdarzeń bez żadnego panelu: $missing. Dopisz je do MODULES w " +
                "deploy/monitoring/grafana/generate_dashboards.py i przegeneruj dashboardy."
        }
    }

    @Test
    fun `no panel asks for a type that no longer exists`() {
        val known = BusinessEventType.entries.map { it.name }.toSet()
        val unknown = referencedTypes - known
        assertTrue(unknown.isEmpty()) {
            "Dashboardy pytają o nieistniejące typy: $unknown. Taki panel pokazuje `—`, " +
                "czyli wygląda jak awaria scrape'u. Zaktualizuj MODULES i przegeneruj."
        }
    }

    /**
     * Kafel „dziś" czyta `crm_business_events_today`, który eksporter wystawia WYŁĄCZNIE dla
     * typów `daily = true`. Panel dla pozostałych pokazywałby `—` na zawsze.
     */
    @Test
    fun `today tiles only reference daily types`() {
        val today = Regex("""crm_business_events_today\{type="([A-Z_]+)"""")
        val nonDaily = BusinessEventType.entries.filter { !it.daily }.map { it.name }.toSet()
        val offenders = dashboards.flatMap { (name, json) ->
            today.findAll(json).map { name to it.groupValues[1] }
        }.filter { it.second in nonDaily }
        assertTrue(offenders.isEmpty()) {
            "Kafle „dziś” dla typów bez daily=true (gauge nie jest dla nich eksportowany): $offenders"
        }
    }

    /**
     * Suma kwot istnieje tylko dla typów `monetary`. Pozostałe mają w Redisie pusty hash sum,
     * więc panel pokazałby zero i sugerował „nic nie zarobili", zamiast „to nie jest metryka kwotowa".
     */
    @Test
    fun `money panels only reference monetary types`() {
        val money = Regex("""crm_business_events_sum_all_time\{type="([A-Z_]+)"""")
        val nonMonetary = BusinessEventType.entries.filter { !it.monetary }.map { it.name }.toSet()
        val offenders = dashboards.flatMap { (name, json) ->
            money.findAll(json).map { name to it.groupValues[1] }
        }.filter { it.second in nonMonetary }
        assertTrue(offenders.isEmpty()) {
            "Panele kwotowe dla typów bez monetary=true: $offenders"
        }
    }

    /**
     * Rozbicie na wymiar ma sens tylko dla typów, które wymiar mają — i tylko dla wartości
     * z ich zamkniętego zbioru. Literówka w wartości daje pustą serię bez żadnego błędu.
     */
    @Test
    fun `dimension slices reference declared dimension values`() {
        val slice = Regex("""crm_business_events_all_time_dim\{type="([A-Z_]+)",dimension="([A-Z_]+)"""")
        val byName = BusinessEventType.entries.associateBy { it.name }
        val offenders = dashboards.flatMap { (name, json) ->
            slice.findAll(json).map { Triple(name, it.groupValues[1], it.groupValues[2]) }
        }.filter { (_, type, dim) -> byName[type]?.dimensions?.contains(dim) != true }
        assertTrue(offenders.isEmpty()) {
            "Pod-serie o nieznanym wymiarze (pusta seria, brak błędu w Grafanie): $offenders"
        }
    }

    /** Filtrowanie po nazwie studia rozbija się o escapowanie apostrofu — wolno tylko po `tenant_id`. */
    @Test
    fun `tenant filtering never uses the studio name`() {
        val forbidden = """tenant="${'$'}tenant"""
        val offenders = dashboards.filterValues { it.contains(forbidden) }.keys
        assertTrue(offenders.isEmpty()) {
            "Dashboardy filtrują po nazwie studia zamiast po tenant_id: $offenders. " +
                "Grafana escapuje apostrof w nazwie i matcher przestaje pasować do czegokolwiek."
        }
    }
}

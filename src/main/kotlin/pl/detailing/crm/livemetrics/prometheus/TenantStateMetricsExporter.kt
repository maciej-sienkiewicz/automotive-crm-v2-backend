package pl.detailing.crm.livemetrics.prometheus

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.livemetrics.config.LiveMetricsProperties
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Stany i wielkości bieżące tenanta czytane WPROST Z BAZY — druga połowa telemetrii
 * zaangażowania, obok zdarzeń z [LiveMetricsPrometheusExporter].
 *
 *  - `crm_tenant_state{tenant_id, tenant, state}` → 0/1,
 *  - `crm_tenant_inventory{tenant_id, tenant, kind}` → liczba,
 *  - `crm_tenant_state_refreshed_seconds` → epoch ostatniego udanego odświeżenia.
 *
 * ### Dlaczego z bazy, a nie ze zdarzeń
 *
 * Zdarzenie odpowiada na pytanie „ile razy", stan na pytanie „czy teraz". Te dwa pytania
 * rozjeżdżają się przy pierwszym cofnięciu: pocztę można odłączyć, usługę skasować,
 * tablet odparować, a kredyty SMS maleją z każdym SMS-em. Suma zdarzeń `MAILBOX_CONNECTED`
 * dalej pokazywałaby wtedy 1. Odczyt z bazy jest samonaprawialny — każdy cykl liczy stan
 * od nowa, więc żaden dryf się nie kumuluje — i obejmuje konfiguracje sprzed wdrożenia
 * metryk, których zdarzenia z definicji nie widzą.
 *
 * ### Dlaczego jedno zapytanie na metrykę, a nie pętla po tenantach
 *
 * Każde zapytanie jest zagregowane `GROUP BY studio_id` i zwraca komplet tenantów naraz.
 * Kilkanaście zapytań co 5 minut zamiast kilkunastu tysięcy — koszt nie rośnie z liczbą
 * studiów. Jedyny wyjątek (`services_unassigned`) to przepisany na wersję zbiorczą
 * odpowiednik `StatsRepository.findUnassignedServiceIds`; liczby MUSZĄ zgadzać się z tym,
 * co pokazuje moduł Statystyki, bo dashboard obok niego traci sens.
 *
 * ### Awaria jest widoczna, nie cicha
 *
 * Nieudane odświeżenie zostawia POPRZEDNIE wartości (MultiGauge ich nie zeruje), więc bez
 * dodatkowego sygnału „wszyscy mają 0 usług" i „eksporter nie żyje od godziny" wyglądałyby
 * identycznie. `crm_tenant_state_refreshed_seconds` rośnie tylko po udanym cyklu — alert
 * `TenantStateGaugesStale` pilnuje jego wieku.
 */
@Component
class TenantStateMetricsExporter(
    private val registry: MeterRegistry,
    private val jdbc: JdbcTemplate,
    private val properties: LiveMetricsProperties
) {
    private val log = LoggerFactory.getLogger(TenantStateMetricsExporter::class.java)

    companion object {
        const val STATE = "crm.tenant.state"
        const val INVENTORY = "crm.tenant.inventory"
        const val REFRESHED = "crm.tenant.state.refreshed.seconds"
        const val REFRESH_MS = 5L * 60 * 1000
    }

    private lateinit var stateGauge: MultiGauge
    private lateinit var inventoryGauge: MultiGauge
    private val refreshedAt = AtomicLong()

    /**
     * Stany binarne: nazwa etykiety → zapytanie zwracające `studio_id` tych tenantów,
     * u których stan jest włączony. Brak wiersza znaczy 0 — każdy tenant dostaje wiersz
     * tak czy inaczej, bo „nie ma" i „nie wiadomo" muszą wyglądać inaczej.
     */
    private val stateQueries: Map<String, String> = linkedMapOf(
        // Poczta: konto w stanie innym niż ACTIVE (błąd auth, wyłączone) nie jest działającą konfiguracją.
        "mailbox_configured" to
            "SELECT DISTINCT studio_id FROM mail_accounts WHERE status = 'ACTIVE'",

        // Trzy poziomy KSeF zamiast jednej skali liczbowej: „są dane", „token przeszedł weryfikację",
        // „token ma prawo wystawiać". To dokładnie rozróżnienie z KsefController.getInvoicingStatus().
        "ksef_configured" to
            "SELECT DISTINCT studio_id FROM ksef_credentials",
        "ksef_token_valid" to
            "SELECT DISTINCT studio_id FROM ksef_credentials WHERE verified_token_valid IS TRUE",
        "ksef_can_issue" to
            "SELECT DISTINCT studio_id FROM ksef_credentials WHERE verified_permissions LIKE '%InvoiceWrite%'",

        "instagram_self_profile" to
            "SELECT DISTINCT studio_id FROM studio_instagram_profiles WHERE is_self IS TRUE",

        // Tablet odparowany (revoked_at) przestaje być konfiguracją, mimo że wiersz zostaje.
        "tablet_paired" to
            "SELECT DISTINCT studio_id FROM signing_tablets WHERE revoked_at IS NULL",

        "sms_sender_name_set" to
            "SELECT DISTINCT studio_id FROM sms_automation_configs WHERE sms_sender_name IS NOT NULL",
        "sms_sender_confirmed" to
            "SELECT DISTINCT studio_id FROM sms_automation_configs WHERE sms_api_name_confirmed IS TRUE",
        "signature_configured" to
            "SELECT DISTINCT studio_id FROM sms_automation_configs WHERE signature_request_enabled IS TRUE",

        // Blokada bezczynności nie ma flagi enabled — zero znaczy „wyłączona".
        "idle_lock_enabled" to
            "SELECT studio_id FROM studio_settings WHERE idle_timeout_seconds > 0"
    )

    /** Wielkości bieżące: nazwa etykiety → zapytanie `(studio_id, wartość)`. */
    private val inventoryQueries: Map<String, String> = linkedMapOf(
        "services" to """
            SELECT studio_id, COUNT(*) FROM services
            WHERE is_active IS TRUE AND is_package IS FALSE GROUP BY studio_id
        """,
        "service_packages" to """
            SELECT studio_id, COUNT(*) FROM services
            WHERE is_active IS TRUE AND is_package IS TRUE GROUP BY studio_id
        """,
        "employees" to
            "SELECT studio_id, COUNT(*) FROM employees GROUP BY studio_id",
        "roles" to
            "SELECT studio_id, COUNT(*) FROM studio_roles GROUP BY studio_id",
        "appointment_colors" to
            "SELECT studio_id, COUNT(*) FROM appointment_colors WHERE is_active IS TRUE GROUP BY studio_id",
        "instagram_profiles" to
            "SELECT studio_id, COUNT(*) FROM studio_instagram_profiles WHERE status = 'ACTIVE' GROUP BY studio_id",

        // Ile automatów SMS studio faktycznie włączyło — suma flag, nie liczba wierszy
        // (konfiguracja to jeden wiersz na studio, więc COUNT(*) dałby zawsze 1).
        "sms_templates_enabled" to """
            SELECT studio_id,
                   (pre_visit_enabled::int + post_visit_enabled::int + delayed_reminder_enabled::int
                  + booking_confirmation_enabled::int + reschedule_confirmation_enabled::int
                  + visit_ready_for_pickup_enabled::int + visit_card_link_enabled::int
                  + reservation_card_link_enabled::int + upsell_consent_enabled::int
                  + upsell_suggestion_enabled::int + signature_request_enabled::int)
            FROM sms_automation_configs
        """,
        "sms_credits" to
            "SELECT studio_id, available_credits FROM sms_credit_balances",

        // Kategorie: przychodowe (usługowe) i kosztowe żyją w osobnych tabelach.
        "service_categories" to
            "SELECT studio_id, COUNT(*) FROM service_categories WHERE is_active IS TRUE GROUP BY studio_id",
        "cost_categories" to
            "SELECT studio_id, COUNT(*) FROM cost_categories WHERE is_active IS TRUE GROUP BY studio_id",

        // Zbiorczy odpowiednik StatsRepository.findUnassignedServiceIds: usługa liczy się jako
        // przypisana także wtedy, gdy przypisana była jej starsza wersja (łańcuch replaces_service_id),
        // a przypisanie do skasowanej (nieaktywnej) kategorii nie liczy się wcale.
        "services_unassigned" to """
            WITH RECURSIVE assigned_family AS (
                SELECT csa.service_id AS id
                FROM category_service_assignments csa
                INNER JOIN service_categories sc ON sc.id = csa.category_id AND sc.is_active IS TRUE
                UNION ALL
                SELECT sv.id
                FROM services sv
                INNER JOIN assigned_family af ON sv.replaces_service_id = af.id
            )
            SELECT s.studio_id, COUNT(*)
            FROM services s
            WHERE s.is_active IS TRUE
              AND s.id NOT IN (SELECT id FROM assigned_family)
            GROUP BY s.studio_id
        """,

        // Pozycje kosztowe z faktur zakupowych KSeF. Faktury anulowane i wyłączone ze statystyk
        // nie są kosztem — ten sam filtr co w CostItemAssignmentRepository.
        "cost_items_assigned" to """
            SELECT ki.studio_id, COUNT(*)
            FROM cost_item_assignments cia
            JOIN ksef_invoice_items kii ON kii.id = cia.ksef_item_id
            JOIN ksef_invoices ki ON ki.id = kii.invoice_id
            WHERE ki.status NOT IN ('CANCELLED', 'EXCLUDED')
            GROUP BY ki.studio_id
        """,
        "cost_items_unassigned" to """
            SELECT ki.studio_id, COUNT(*)
            FROM ksef_invoice_items kii
            JOIN ksef_invoices ki ON ki.id = kii.invoice_id
            LEFT JOIN cost_item_assignments cia ON cia.ksef_item_id = kii.id
            WHERE ki.status NOT IN ('CANCELLED', 'EXCLUDED') AND cia.id IS NULL
            GROUP BY ki.studio_id
        """,

        // Koszt z KSeF w groszach — jednostka jak w całym module finansowym, dzielenie robi Grafana.
        "ksef_cost_grosze" to """
            SELECT ki.studio_id, COALESCE(SUM(kii.gross_value), 0)
            FROM cost_item_assignments cia
            JOIN ksef_invoice_items kii ON kii.id = cia.ksef_item_id
            JOIN ksef_invoices ki ON ki.id = kii.invoice_id
            WHERE ki.status NOT IN ('CANCELLED', 'EXCLUDED')
            GROUP BY ki.studio_id
        """
    )

    @PostConstruct
    fun register() {
        stateGauge = MultiGauge.builder(STATE)
            .description("Stan binarny funkcji u tenanta (1 = włączona), czytany z bazy").register(registry)
        inventoryGauge = MultiGauge.builder(INVENTORY)
            .description("Wielkości bieżące tenanta (usługi, pracownicy, kredyty SMS…)").register(registry)
        Gauge.builder(REFRESHED) { refreshedAt.get() }
            .description("Epoch (s) ostatniego udanego odświeżenia stanów — starszy niż kilka minut znaczy awarię")
            .register(registry)
    }

    @Scheduled(fixedDelay = REFRESH_MS, initialDelay = 30_000)
    fun refresh() {
        if (!properties.enabled) return
        try {
            val tenants = loadTenants()
            if (tenants.isEmpty()) return

            val stateRows = ArrayList<MultiGauge.Row<Number>>(tenants.size * stateQueries.size)
            for ((state, sql) in stateQueries) {
                val enabled = queryIdSet(sql, state)
                for ((id, tags) in tenants) {
                    stateRows += MultiGauge.Row.of(tags.and("state", state), if (id in enabled) 1 else 0)
                }
            }

            val inventoryRows = ArrayList<MultiGauge.Row<Number>>(tenants.size * inventoryQueries.size)
            for ((kind, sql) in inventoryQueries) {
                val counts = queryCounts(sql, kind)
                for ((id, tags) in tenants) {
                    inventoryRows += MultiGauge.Row.of(tags.and("kind", kind), counts[id] ?: 0L)
                }
            }

            stateGauge.register(stateRows, true)
            inventoryGauge.register(inventoryRows, true)
            refreshedAt.set(System.currentTimeMillis() / 1000)
        } catch (e: Exception) {
            log.warn("[LIVE-METRICS] tenant state refresh failed: {}", e.toString())
        }
    }

    /**
     * Wszystkie studia, nie tylko te ze zdarzeniami.
     *
     * `LiveMetricsStore.tenants()` zna wyłącznie tenantów, u których COŚ się wydarzyło po
     * wdrożeniu metryk — czyli pomija dokładnie tych, o których pyta dashboard adopcji:
     * studia, które niczego nie skonfigurowały i nic nie robią.
     */
    private fun loadTenants(): List<Pair<UUID, Tags>> =
        jdbc.query("SELECT id, name FROM studios") { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            id to Tags.of("tenant_id", id.toString(), "tenant", rs.getString("name") ?: id.toString().take(8))
        }

    /** Pojedyncze zapytanie nie ma prawa wywrócić całego cyklu — brakująca metryka to mniej niż żadna. */
    private fun queryIdSet(sql: String, label: String): Set<UUID> = try {
        jdbc.query(sql) { rs, _ -> rs.getObject(1, UUID::class.java) }.filterNotNull().toHashSet()
    } catch (e: Exception) {
        log.warn("[LIVE-METRICS] state query '{}' failed: {}", label, e.toString())
        emptySet()
    }

    private fun queryCounts(sql: String, label: String): Map<UUID, Long> = try {
        val out = HashMap<UUID, Long>()
        jdbc.query(sql) { rs ->
            rs.getObject(1, UUID::class.java)?.let { out[it] = rs.getLong(2) }
        }
        out
    } catch (e: Exception) {
        log.warn("[LIVE-METRICS] inventory query '{}' failed: {}", label, e.toString())
        emptyMap()
    }
}

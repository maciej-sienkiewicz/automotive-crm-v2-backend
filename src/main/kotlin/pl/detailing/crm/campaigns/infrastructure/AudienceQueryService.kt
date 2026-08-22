package pl.detailing.crm.campaigns.infrastructure

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.campaigns.domain.AudienceCriteria
import pl.detailing.crm.campaigns.domain.CustomerTypeFilter
import pl.detailing.crm.campaigns.domain.RecipientChannel
import pl.detailing.crm.shared.StudioId
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Row returned by audience materialization — carries everything the template
 * renderer needs, so dispatch never goes back to the database per recipient.
 */
data class AudienceRow(
    val customerId: UUID,
    val firstName: String?,
    val lastName: String?,
    val phone: String?,
    val email: String?,
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val lastVisitDate: Instant?,
    val lastServiceName: String?,
    /** Classification against system filters; ELIGIBLE rows may be dispatched. */
    val eligibility: Eligibility
) {
    /**
     * [EXCLUDED_MANUALLY] występuje wyłącznie w wyniku [AudienceQueryService.estimate]:
     * kreator musi widzieć klienta, którego użytkownik odznaczył, bo inaczej odznaczenie
     * byłoby ruchem nieodwracalnym — wiersz znikałby z tabeli i nie dałoby się go
     * zaznaczyć z powrotem. Wysyłka ([materialize]) takich wierszy nie dostaje w ogóle.
     */
    enum class Eligibility { ELIGIBLE, OPTED_OUT, NO_CONSENT, NO_ADDRESS, FREQUENCY_CAP, EXCLUDED_MANUALLY }
}

data class AudienceEstimate(
    val matched: Int,
    val optedOut: Int,
    val noConsent: Int,
    val noAddress: Int,
    val frequencyCapped: Int,
    val eligible: Int,
    /** Odznaczeni ręcznie w kreatorze — wliczeni do [matched], ale nie do [eligible]. */
    val excludedManually: Int,
    /** Strona listy odbiorców — wycinek [matched] wyznaczony przez offset i limit. */
    val sample: List<AudienceRow>
) {
    companion object {
        /** Nikt nie pasuje — używane, gdy warunek kampanii nie wskazuje żadnej wizyty. */
        val EMPTY = AudienceEstimate(0, 0, 0, 0, 0, 0, 0, emptyList())
    }
}

/**
 * Builds and executes the audience query for campaigns.
 *
 * Business criteria (visits, revenue, services, vehicles, customer type) come from
 * [AudienceCriteria]; system filters (active, opt-out, marketing consent per channel,
 * address present, frequency cap) are always applied and reported as a breakdown —
 * the UI shows the user exactly why the final count is lower than the match count.
 *
 * The consent condition mirrors [pl.detailing.crm.customer.consent.MarketingConsentChecker]:
 * if the studio has no active consent definition for the channel, everyone passes;
 * otherwise a non-revoked consent for an active template of such a definition is required.
 */
@Service
class AudienceQueryService(
    private val jdbc: NamedParameterJdbcTemplate
) {

    /**
     * Podsumowanie grupy odbiorców plus jedna strona listy.
     *
     * Kreator kampanii pokazuje tę listę jako tabelę z polami wyboru, więc potrzebuje
     * nie próbki, tylko wycinka wskazanego przez [sampleOffset] i [sampleLimit].
     * Liczniki dotyczą zawsze całości — stronicowanie nie ma prawa zmieniać odpowiedzi
     * na pytanie „ilu ludzi to dostanie".
     *
     * [candidateCustomerIds] zawęża pytanie do konkretnych klientów: tak liczy się
     * prognoza dla kampanii automatycznej, gdzie punktem wyjścia jest warunek
     * (usługa + liczba dni), a kryteria odbiorców są dopiero drugim sitem.
     */
    fun estimate(
        studioId: StudioId,
        criteria: AudienceCriteria,
        channel: RecipientChannel,
        frequencyCapDays: Int,
        sampleLimit: Int = 50,
        sampleOffset: Int = 0,
        candidateCustomerIds: Collection<UUID>? = null
    ): AudienceEstimate {
        if (candidateCustomerIds != null && candidateCustomerIds.isEmpty()) return AudienceEstimate.EMPTY
        val (sql, params) = buildQuery(
            studioId, criteria, channel, frequencyCapDays, candidateCustomerIds,
            keepExcludedVisible = true
        )
        val rows = jdbc.query(sql, params) { rs, _ -> mapRow(rs) }

        val byEligibility = rows.groupingBy { it.eligibility }.eachCount()
        return AudienceEstimate(
            matched = rows.size,
            optedOut = byEligibility[AudienceRow.Eligibility.OPTED_OUT] ?: 0,
            noConsent = byEligibility[AudienceRow.Eligibility.NO_CONSENT] ?: 0,
            noAddress = byEligibility[AudienceRow.Eligibility.NO_ADDRESS] ?: 0,
            frequencyCapped = byEligibility[AudienceRow.Eligibility.FREQUENCY_CAP] ?: 0,
            eligible = byEligibility[AudienceRow.Eligibility.ELIGIBLE] ?: 0,
            excludedManually = byEligibility[AudienceRow.Eligibility.EXCLUDED_MANUALLY] ?: 0,
            sample = rows.drop(sampleOffset.coerceAtLeast(0)).take(sampleLimit)
        )
    }

    /** Full materialization — every matched row with its eligibility classification. */
    fun materialize(
        studioId: StudioId,
        criteria: AudienceCriteria,
        channel: RecipientChannel,
        frequencyCapDays: Int,
        candidateCustomerIds: Collection<UUID>? = null
    ): List<AudienceRow> {
        if (candidateCustomerIds != null && candidateCustomerIds.isEmpty()) return emptyList()
        val (sql, params) = buildQuery(studioId, criteria, channel, frequencyCapDays, candidateCustomerIds)
        return jdbc.query(sql, params) { rs, _ -> mapRow(rs) }
    }

    /**
     * Candidate visits for an AUTOMATIC campaign trigger: completed pickups whose
     * pickup_date falls inside [windowStart, windowEnd) shifted back by afterDays,
     * containing at least one of [serviceIds]. Optionally requires no later visit.
     */
    fun findTriggeredVisits(
        studioId: StudioId,
        serviceIds: List<UUID>,
        pickupFrom: Instant,
        pickupTo: Instant,
        onlyIfNoVisitSince: Boolean
    ): List<TriggeredVisit> {
        val sql = """
            SELECT v.id AS visit_id, v.customer_id
            FROM visits v
            WHERE v.studio_id = :studioId
              AND v.deleted_at IS NULL
              AND v.status IN ('COMPLETED', 'ARCHIVED')
              AND v.pickup_date >= :pickupFrom AND v.pickup_date < :pickupTo
              AND EXISTS (
                  SELECT 1 FROM visit_service_items si
                  WHERE si.visit_id = v.id
                    AND si.status IN ('APPROVED', 'CONFIRMED')
                    AND si.service_id IN (:serviceIds)
              )
              ${if (onlyIfNoVisitSince) """
              AND NOT EXISTS (
                  SELECT 1 FROM visits v2
                  WHERE v2.customer_id = v.customer_id
                    AND v2.studio_id = :studioId
                    AND v2.deleted_at IS NULL
                    AND v2.status NOT IN ('REJECTED')
                    AND v2.id <> v.id
                    AND v2.scheduled_date > v.pickup_date
              )""" else ""}
        """.trimIndent()

        val params = MapSqlParameterSource()
            .addValue("studioId", studioId.value)
            .addValue("pickupFrom", java.sql.Timestamp.from(pickupFrom))
            .addValue("pickupTo", java.sql.Timestamp.from(pickupTo))
            .addValue("serviceIds", serviceIds)

        return jdbc.query(sql, params) { rs, _ ->
            TriggeredVisit(
                visitId = rs.getObject("visit_id", UUID::class.java),
                customerId = rs.getObject("customer_id", UUID::class.java)
            )
        }
    }

    data class TriggeredVisit(val visitId: UUID, val customerId: UUID)

    // ─── Query builder ───────────────────────────────────────────────────────

    private fun buildQuery(
        studioId: StudioId,
        criteria: AudienceCriteria,
        channel: RecipientChannel,
        frequencyCapDays: Int,
        candidateCustomerIds: Collection<UUID>?,
        /**
         * Kreator (`true`) chce widzieć odznaczonych klientów jako wiersze o statusie
         * EXCLUDED_MANUALLY; wysyłka (`false`) ma ich nie dostać w ogóle.
         */
        keepExcludedVisible: Boolean = false
    ): Pair<String, MapSqlParameterSource> {
        val params = MapSqlParameterSource()
            .addValue("studioId", studioId.value)
            .addValue("channel", channel.name)
            .addValue("capSince", java.sql.Timestamp.from(Instant.now().minusSeconds(frequencyCapDays * 86_400L)))

        val filters = StringBuilder()

        // ── Wizyty ──
        criteria.visitCountMin?.let { filters.append(" AND vs.cnt >= :visitCountMin"); params.addValue("visitCountMin", it) }
        criteria.visitCountMax?.let { filters.append(" AND vs.cnt <= :visitCountMax"); params.addValue("visitCountMax", it) }
        criteria.lastVisitOlderThanDays?.let {
            filters.append(" AND (vs.last_visit IS NOT NULL AND vs.last_visit < :lastVisitBefore)")
            params.addValue("lastVisitBefore", java.sql.Timestamp.from(Instant.now().minusSeconds(it * 86_400L)))
        }
        criteria.lastVisitNewerThanDays?.let {
            filters.append(" AND vs.last_visit >= :lastVisitAfter")
            params.addValue("lastVisitAfter", java.sql.Timestamp.from(Instant.now().minusSeconds(it * 86_400L)))
        }

        // ── Przychody ──
        criteria.revenueTotalGrossMin?.let { filters.append(" AND rev.total >= :revMin"); params.addValue("revMin", it) }
        criteria.revenueTotalGrossMax?.let { filters.append(" AND rev.total <= :revMax"); params.addValue("revMax", it) }

        // ── Usługi ──
        if (criteria.servicesUsedAnyOf.isNotEmpty()) {
            params.addValue("svcAny", criteria.servicesUsedAnyOf)
            if (criteria.serviceLastUsedOlderThanDays != null) {
                filters.append("""
                     AND (SELECT max(v.scheduled_date) FROM visits v
                          JOIN visit_service_items si ON si.visit_id = v.id
                          WHERE v.customer_id = c.id AND v.studio_id = :studioId
                            AND v.deleted_at IS NULL AND v.status IN ('COMPLETED','ARCHIVED')
                            AND si.status IN ('APPROVED','CONFIRMED') AND si.service_id IN (:svcAny)
                         ) < :svcLastUsedBefore""")
                params.addValue(
                    "svcLastUsedBefore",
                    java.sql.Timestamp.from(Instant.now().minusSeconds(criteria.serviceLastUsedOlderThanDays * 86_400L))
                )
            } else {
                filters.append("""
                     AND EXISTS (SELECT 1 FROM visits v
                          JOIN visit_service_items si ON si.visit_id = v.id
                          WHERE v.customer_id = c.id AND v.studio_id = :studioId
                            AND v.deleted_at IS NULL AND v.status IN ('COMPLETED','ARCHIVED')
                            AND si.status IN ('APPROVED','CONFIRMED') AND si.service_id IN (:svcAny))""")
            }
        }
        if (criteria.servicesUsedNoneOf.isNotEmpty()) {
            params.addValue("svcNone", criteria.servicesUsedNoneOf)
            filters.append("""
                 AND NOT EXISTS (SELECT 1 FROM visits v
                      JOIN visit_service_items si ON si.visit_id = v.id
                      WHERE v.customer_id = c.id AND v.studio_id = :studioId
                        AND v.deleted_at IS NULL AND v.status IN ('COMPLETED','ARCHIVED')
                        AND si.status IN ('APPROVED','CONFIRMED') AND si.service_id IN (:svcNone))""")
        }

        // ── Pojazdy ──
        if (criteria.vehicleBrands.isNotEmpty() || criteria.vehicleYearMin != null || criteria.vehicleYearMax != null) {
            val vehicleConds = StringBuilder("ve.deleted_at IS NULL")
            if (criteria.vehicleBrands.isNotEmpty()) {
                val brandParts = criteria.vehicleBrands.mapIndexed { i, bf ->
                    params.addValue("brand$i", bf.brand)
                    if (bf.model != null) {
                        params.addValue("model$i", bf.model)
                        "(ve.brand ILIKE :brand$i AND ve.model ILIKE :model$i)"
                    } else {
                        "(ve.brand ILIKE :brand$i)"
                    }
                }
                vehicleConds.append(" AND (${brandParts.joinToString(" OR ")})")
            }
            criteria.vehicleYearMin?.let { vehicleConds.append(" AND ve.year_of_production >= :vehYearMin"); params.addValue("vehYearMin", it) }
            criteria.vehicleYearMax?.let { vehicleConds.append(" AND ve.year_of_production <= :vehYearMax"); params.addValue("vehYearMax", it) }
            filters.append("""
                 AND EXISTS (SELECT 1 FROM vehicle_owners vo
                      JOIN vehicles ve ON ve.id = vo.vehicle_id
                      WHERE vo.customer_id = c.id AND $vehicleConds)""")
        }

        // ── Klient ──
        when (criteria.customerType) {
            CustomerTypeFilter.COMPANY -> filters.append(" AND c.company_name IS NOT NULL")
            CustomerTypeFilter.INDIVIDUAL -> filters.append(" AND c.company_name IS NULL")
            CustomerTypeFilter.ALL -> {}
        }
        criteria.customerCreatedAfter?.let {
            filters.append(" AND c.created_at >= :createdAfter")
            params.addValue("createdAfter", java.sql.Timestamp.from(it))
        }

        // Ręczne dopisania omijają filtry biznesowe (ale nie systemowe).
        val businessBlock = if (criteria.includeCustomerIds.isNotEmpty()) {
            params.addValue("includeIds", criteria.includeCustomerIds)
            "AND (c.id IN (:includeIds) OR (true $filters))"
        } else {
            "AND (true $filters)"
        }

        val hasExclusions = criteria.excludeCustomerIds.isNotEmpty()
        if (hasExclusions) params.addValue("excludeIds", criteria.excludeCustomerIds)
        val excludeBlock = if (hasExclusions && !keepExcludedVisible) "AND c.id NOT IN (:excludeIds)" else ""
        val excludedCase = if (hasExclusions && keepExcludedVisible) {
            "WHEN c.id IN (:excludeIds) THEN 'EXCLUDED_MANUALLY'"
        } else ""

        val candidateBlock = if (!candidateCustomerIds.isNullOrEmpty()) {
            params.addValue("candidateIds", candidateCustomerIds)
            "AND c.id IN (:candidateIds)"
        } else ""

        val addressColumn = if (channel == RecipientChannel.SMS) "c.phone" else "c.email"

        val sql = """
            SELECT
                c.id AS customer_id, c.first_name, c.last_name, c.phone, c.email,
                lastv.brand_snapshot AS vehicle_brand, lastv.model_snapshot AS vehicle_model,
                vs.last_visit AS last_visit_date, lastsvc.service_name AS last_service_name,
                CASE
                    $excludedCase
                    WHEN EXISTS (SELECT 1 FROM campaign_opt_outs oo
                                 WHERE oo.studio_id = :studioId AND oo.customer_id = c.id)
                        THEN 'OPTED_OUT'
                    WHEN NOT (
                        NOT EXISTS (
                            SELECT 1 FROM consent_definitions cd
                            JOIN consent_definition_marketing_channels ch ON ch.definition_id = cd.id
                            WHERE cd.studio_id = :studioId AND cd.is_active = true AND ch.channel = :channel
                        )
                        OR EXISTS (
                            SELECT 1 FROM consent_definitions cd
                            JOIN consent_definition_marketing_channels ch ON ch.definition_id = cd.id
                            JOIN consent_templates ct ON ct.definition_id = cd.id AND ct.is_active = true
                            JOIN customer_consents cc ON cc.template_id = ct.id
                                 AND cc.customer_id = c.id AND cc.revoked_at IS NULL
                            WHERE cd.studio_id = :studioId AND cd.is_active = true AND ch.channel = :channel
                        )
                    ) THEN 'NO_CONSENT'
                    WHEN $addressColumn IS NULL OR btrim($addressColumn) = '' THEN 'NO_ADDRESS'
                    WHEN EXISTS (SELECT 1 FROM communication_log cl
                                 WHERE cl.studio_id = :studioId AND cl.customer_id = c.id
                                   AND cl.message_type IN ('CAMPAIGN_SMS','CAMPAIGN_EMAIL')
                                   AND cl.sent_at > :capSince)
                        THEN 'FREQUENCY_CAP'
                    ELSE 'ELIGIBLE'
                END AS eligibility
            FROM customers c
            LEFT JOIN LATERAL (
                SELECT count(*) AS cnt, max(v.scheduled_date) AS last_visit
                FROM visits v
                WHERE v.customer_id = c.id AND v.studio_id = :studioId
                  AND v.deleted_at IS NULL AND v.status IN ('COMPLETED','ARCHIVED')
            ) vs ON true
            LEFT JOIN LATERAL (
                SELECT coalesce(sum(si.final_price_gross), 0) AS total
                FROM visits v
                JOIN visit_service_items si ON si.visit_id = v.id
                WHERE v.customer_id = c.id AND v.studio_id = :studioId
                  AND v.deleted_at IS NULL AND v.status IN ('COMPLETED','ARCHIVED')
                  AND si.status IN ('APPROVED','CONFIRMED')
            ) rev ON true
            LEFT JOIN LATERAL (
                SELECT v.id, v.brand_snapshot, v.model_snapshot
                FROM visits v
                WHERE v.customer_id = c.id AND v.studio_id = :studioId
                  AND v.deleted_at IS NULL AND v.status IN ('COMPLETED','ARCHIVED')
                ORDER BY v.scheduled_date DESC LIMIT 1
            ) lastv ON true
            LEFT JOIN LATERAL (
                SELECT si.service_name
                FROM visit_service_items si
                WHERE si.visit_id = lastv.id AND si.status IN ('APPROVED','CONFIRMED')
                ORDER BY si.final_price_gross DESC LIMIT 1
            ) lastsvc ON true
            WHERE c.studio_id = :studioId
              AND c.is_active = true
              $businessBlock
              $excludeBlock
              $candidateBlock
            ORDER BY c.last_name NULLS LAST, c.first_name NULLS LAST
        """.trimIndent()

        return sql to params
    }

    private fun mapRow(rs: ResultSet): AudienceRow = AudienceRow(
        customerId = rs.getObject("customer_id", UUID::class.java),
        firstName = rs.getString("first_name"),
        lastName = rs.getString("last_name"),
        phone = rs.getString("phone"),
        email = rs.getString("email"),
        vehicleBrand = rs.getString("vehicle_brand"),
        vehicleModel = rs.getString("vehicle_model"),
        lastVisitDate = rs.getTimestamp("last_visit_date")?.toInstant(),
        lastServiceName = rs.getString("last_service_name"),
        eligibility = AudienceRow.Eligibility.valueOf(rs.getString("eligibility"))
    )
}

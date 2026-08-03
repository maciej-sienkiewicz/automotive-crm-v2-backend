package pl.detailing.crm.campaigns.application

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.campaigns.domain.CampaignOptOutRepository
import pl.detailing.crm.campaigns.domain.OptOutSource
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/**
 * Handles marketing opt-outs.
 *
 * An inbound "STOP" reply (SMSAPI webhook) opts the customer out of campaign
 * messages in every studio that has this phone number on file. The opt-out is
 * appended to campaign_opt_outs and checked both at audience estimation and
 * again at dispatch time — it always wins over an active consent.
 */
@Service
class CampaignOptOutService(
    private val optOuts: CampaignOptOutRepository,
    private val jdbc: NamedParameterJdbcTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun processInboundReply(rawPhone: String, text: String) {
        val normalized = text.trim().uppercase()
        if (normalized != "STOP" && !normalized.startsWith("STOP ")) return

        val digits = rawPhone.filter { it.isDigit() }
        if (digits.length < 9) return
        val last9 = digits.takeLast(9)

        val matches = jdbc.query(
            """SELECT id, studio_id FROM customers
               WHERE is_active = true
                 AND regexp_replace(coalesce(phone, ''), '[^0-9]', '', 'g') LIKE :suffix""",
            MapSqlParameterSource("suffix", "%$last9")
        ) { rs, _ ->
            rs.getObject("studio_id", UUID::class.java) to rs.getObject("id", UUID::class.java)
        }

        matches.forEach { (studioId, customerId) ->
            optOuts.optOut(studioId, customerId, OptOutSource.SMS_STOP)
        }
        if (matches.isNotEmpty()) {
            logger.info("STOP received — opted out {} customer record(s) for phone ending {}", matches.size, last9.takeLast(3))
        }
    }

    @Transactional
    fun manualOptOut(studioId: StudioId, customerId: UUID) {
        optOuts.optOut(studioId.value, customerId, OptOutSource.MANUAL)
    }
}

package pl.detailing.crm.ownerreport.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MetricsMedianTest {

    private fun week(
        gross: Long,
        visits: Int,
        replyMinutes: Long? = null,
        instagram: InstagramMetrics? = null
    ) = PeriodMetrics(
        visitsStarted = visits,
        reservationsCreated = visits,
        closed = ClosedVisits(count = visits, grossCents = gross, netCents = gross * 100 / 123),
        costsNetCents = 0,
        emails = EmailMetrics(0, 0, ReplyTimes(inquiries = 2, answered = 2, answeredWithinHour = 1, medianMinutes = replyMinutes)),
        upsell = UpsellMetrics(0, 0, 0, 0, 0),
        visitCardsSent = 0,
        batch = BatchMetrics(0, 0, 0),
        instagram = instagram
    )

    @Test
    fun `jeden tydzien z flota nie przesuwa punktu odniesienia`() {
        val weeks = listOf(
            week(gross = 400000, visits = 8),
            week(gross = 420000, visits = 9),
            week(gross = 3500000, visits = 40), // zlecenie flotowe
            week(gross = 380000, visits = 7),
            week(gross = 410000, visits = 8)
        )
        val median = MetricsMedian.of(weeks)
        assertEquals(410000L, median.closed.grossCents)
        assertEquals(8, median.visitsStarted)
    }

    @Test
    fun `przy parzystej liczbie okresow srednia dwoch srodkowych, zaokraglona do grosza`() {
        assertEquals(15L, MetricsMedian.median(listOf(10L, 20L)))
        assertEquals(16L, MetricsMedian.median(listOf(10L, 21L)))
        assertNull(MetricsMedian.median(emptyList()))
    }

    @Test
    fun `mediana czasu odpowiedzi i Instagrama tylko z okresow, w ktorych byly dane`() {
        val median = MetricsMedian.of(
            listOf(
                week(gross = 0, visits = 0, replyMinutes = 30, instagram = null),
                week(gross = 0, visits = 0, replyMinutes = null, instagram = InstagramMetrics(4, 100, 10)),
                week(gross = 0, visits = 0, replyMinutes = 90, instagram = InstagramMetrics(2, 50, 4))
            )
        )
        assertEquals(60L, median.emails.replies.medianMinutes)
        assertEquals(InstagramMetrics(posts = 3, likes = 75, comments = 7), median.instagram)
        assertNull(MetricsMedian.of(listOf(week(0, 0))).instagram)
    }
}

package pl.detailing.crm.ownerreport.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.ownerreport.domain.BatchMetrics
import pl.detailing.crm.ownerreport.domain.ClosedVisits
import pl.detailing.crm.ownerreport.domain.CompetitorMetrics
import pl.detailing.crm.ownerreport.domain.EmailMetrics
import pl.detailing.crm.ownerreport.domain.InstagramMetrics
import pl.detailing.crm.ownerreport.domain.OwnerReport
import pl.detailing.crm.ownerreport.domain.PeriodMetrics
import pl.detailing.crm.ownerreport.domain.ReplyTimes
import pl.detailing.crm.ownerreport.domain.ReportComparison
import pl.detailing.crm.ownerreport.domain.ReportLength
import pl.detailing.crm.ownerreport.domain.ReportPeriod
import pl.detailing.crm.ownerreport.domain.SnapshotMetrics
import pl.detailing.crm.ownerreport.domain.UpsellMetrics
import java.time.Instant
import java.time.LocalDate

/**
 * Raport rysuje się w kodzie — ten test jest jedynym miejscem, które łapie, że PDF
 * w ogóle powstaje, niesie polskie znaki i pokazuje kwoty co do grosza.
 */
class OwnerReportPdfRendererTest {

    private val renderer = OwnerReportPdfRenderer()

    private fun metrics(
        closedGross: Long = 190000,
        closedNet: Long = 154472,
        instagram: InstagramMetrics? = InstagramMetrics(posts = 4, likes = 312, comments = 18)
    ) = PeriodMetrics(
        visitsStarted = 17,
        reservationsCreated = 22,
        closed = ClosedVisits(count = 1, grossCents = closedGross, netCents = closedNet),
        costsNetCents = 250000,
        emails = EmailMetrics(sentByTeam = 41, sentAutomated = 63, replies = ReplyTimes.of(listOf(12L, 95L, null))),
        upsell = UpsellMetrics(suggested = 9, suggestedGrossCents = 540000, visitsWithSuggestions = 5, accepted = 3, acceptedGrossCents = 129900),
        visitCardsSent = 14,
        batch = BatchMetrics(vehicles = 11, grossCents = 840000, contractors = 2),
        instagram = instagram
    )

    private fun report(
        current: PeriodMetrics = metrics(),
        previous: PeriodMetrics = metrics(closedGross = 150000, closedNet = 121951),
        comparison: ReportComparison = ReportComparison.PREVIOUS,
        length: ReportLength = ReportLength.WEEK,
        competitors: CompetitorMetrics? = CompetitorMetrics(
            advertisers = 7,
            activeAds = 23,
            campaignsStartedInPeriod = 3,
            newAdvertisers = listOf("Połysk Źródło Detailing"),
            top = listOf("Auto Spa Łódź" to 9, "Połysk Źródło Detailing" to 4)
        )
    ) = OwnerReport(
        studioName = "Studio Detailingu Żółć Sp. z o.o.",
        studioAddress = "ul. Kwiatowa 5, 30-001 Kraków",
        logoPng = null,
        period = ReportPeriod.containing(length, LocalDate.of(2026, 9, 14)),
        current = current,
        comparison = comparison,
        baseline = previous,
        snapshot = SnapshotMetrics(batchUnsettledVehicles = 6, batchUnsettledGrossCents = 2340000, competitors = competitors),
        generatedAt = Instant.parse("2026-09-21T05:00:00Z")
    )

    private fun textOf(bytes: ByteArray): String =
        Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    @Test
    fun `rysuje raport tygodniowy ze wszystkimi modulami`() {
        val text = textOf(renderer.render(report()))

        assertTrue(text.contains("RAPORT TYGODNIOWY"), "brak tytułu w: $text")
        assertTrue(text.contains("Studio Detailingu Żółć"), "zgubione polskie znaki w nazwie studia")
        listOf("FINANSE I SPRZEDAŻ", "OPERACJE NA HALI", "KOMUNIKACJA Z KLIENTAMI", "MARKETING").forEach {
            assertTrue(text.contains(it), "brak modułu $it")
        }
        assertTrue(text.contains("14.09–20.09.2026"), "brak okresu")
        assertTrue(text.contains("Wizyty rozpoczęte"))
        assertTrue(text.contains("Karty wizyt wysłane klientom"))
        assertTrue(text.contains("Nowi w rejonie: Połysk Źródło Detailing"))
    }

    @Test
    fun `kwota wpisana jako brutto wychodzi na raporcie co do grosza`() {
        // 1900,00 zł brutto przy 23% VAT to 1544,72 zł netto; brutto odtworzone z netto
        // dałoby 1900,01 zł — raport ma pokazać to, co zapłacił klient (CLAUDE.md §1).
        val text = textOf(renderer.render(report()))
        assertTrue(text.contains("1 900,00 zł"), "brak dokładnego brutto w: $text")
        assertFalse(text.contains("1 900,01 zł"), "brutto przeliczone z netto")
        assertTrue(text.contains("1 544,72 zł"), "brak netto")
    }

    @Test
    fun `kafel mowi, z czym porownuje - takze gdy poprzednio bylo zero`() {
        val zero = metrics(closedGross = 0, closedNet = 0)
        val text = textOf(renderer.render(report(previous = zero)))
        assertTrue(text.contains("poprzednio 0,00 zł"), "brak wartości odniesienia w kaflu: $text")
        assertFalse(text.contains("nowe"), "słowo nowe bez punktu odniesienia nic nie mówi")
        assertFalse(text.contains("vs poprzedni okres"))
    }

    @Test
    fun `bez sredniej wartosci wizyty i bez sprzedazy minus koszty`() {
        val text = textOf(renderer.render(report()))
        assertFalse(text.contains("Średnia wartość wizyty"))
        assertFalse(text.contains("minus koszty"))
    }

    @Test
    fun `porownanie z mediana i raport miesieczny`() {
        val text = textOf(renderer.render(report(comparison = ReportComparison.MEDIAN, length = ReportLength.MONTH)))
        assertTrue(text.contains("RAPORT MIESIĘCZNY"), "brak tytułu: $text")
        assertTrue(text.contains("Mediana 6 poprzednich miesięcy"))
        assertTrue(text.contains("MEDIANA"))
        assertTrue(text.contains("mediana 1 500,00 zł"), "kafel ma mówić o medianie: $text")
        assertTrue(text.contains("01.09–30.09.2026"))
    }

    @Test
    fun `bez profilu na Instagramie i bez rejonu raport mowi, co ustawic, zamiast pokazac zera`() {
        val text = textOf(
            renderer.render(
                report(
                    current = metrics(instagram = null),
                    previous = metrics(instagram = null),
                    competitors = null
                )
            )
        )
        assertTrue(text.contains("wskaż własny profil"), "brak podpowiedzi o Instagramie")
        assertTrue(text.contains("ustaw swój rejon"), "brak podpowiedzi o rejonie")
        assertFalse(text.contains("Posty na Instagramie"))
    }

    @Test
    fun `pusty okres nie wywraca generatora`() {
        val empty = PeriodMetrics(
            visitsStarted = 0,
            reservationsCreated = 0,
            closed = ClosedVisits(0, 0, 0),
            costsNetCents = 0,
            emails = EmailMetrics(0, 0, ReplyTimes.EMPTY),
            upsell = UpsellMetrics(0, 0, 0, 0, 0),
            visitCardsSent = 0,
            batch = BatchMetrics(0, 0, 0),
            instagram = null
        )
        val text = textOf(renderer.render(report(current = empty, previous = empty, competitors = null)))
        assertTrue(text.contains("RAPORT TYGODNIOWY"))
        assertTrue(text.contains("bez zmian"))
    }
}

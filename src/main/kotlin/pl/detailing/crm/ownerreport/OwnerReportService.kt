package pl.detailing.crm.ownerreport

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.finance.reporting.FinanceReportQuery
import pl.detailing.crm.finance.reporting.FinanceReportingHandler
import pl.detailing.crm.instagram.ads.discovery.AdDiscoveryReadService
import pl.detailing.crm.ownerreport.domain.ClosedVisits
import pl.detailing.crm.ownerreport.domain.CompetitorMetrics
import pl.detailing.crm.ownerreport.domain.EmailMetrics
import pl.detailing.crm.ownerreport.domain.MetricsMedian
import pl.detailing.crm.ownerreport.domain.OwnerReport
import pl.detailing.crm.ownerreport.domain.PeriodMetrics
import pl.detailing.crm.ownerreport.domain.ReplyTimes
import pl.detailing.crm.ownerreport.domain.ReportComparison
import pl.detailing.crm.ownerreport.domain.ReportPeriod
import pl.detailing.crm.ownerreport.domain.SnapshotMetrics
import pl.detailing.crm.ownerreport.infrastructure.OwnerReportQueries
import pl.detailing.crm.ownerreport.pdf.OwnerReportPdfRenderer
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.logo.CompanyLogoService
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Gotowy plik raportu. */
class OwnerReportFile(val bytes: ByteArray, val fileName: String)

/**
 * Składa raport właściciela: zbiera liczby za okres i okres poprzedni, stan „na dziś"
 * i rysuje PDF.
 *
 * Źródła celowo różne dla różnych liczb — każda idzie z miejsca, które jest jej
 * źródłem prawdy, a nie z liczników telemetrii (livemetrics trzyma ~30 dni i nie jest
 * ewidencją). Opis każdej liczby jest przy polu w [PeriodMetrics].
 */
@Service
class OwnerReportService(
    private val queries: OwnerReportQueries,
    private val visitRepository: VisitRepository,
    private val financeReportingHandler: FinanceReportingHandler,
    private val adDiscoveryReadService: AdDiscoveryReadService,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val companyLogoService: CompanyLogoService,
    private val renderer: OwnerReportPdfRenderer,
    private val transactionTemplate: TransactionTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun generate(studioId: StudioId, period: ReportPeriod, comparison: ReportComparison): OwnerReportFile {
        val report = build(studioId, period, comparison)
        val bytes = renderer.render(report)
        logger.info(
            "Owner report generated: studio={} period={}..{} comparison={} bytes={}",
            studioId.value, period.from, period.to, comparison, bytes.size
        )
        return OwnerReportFile(bytes, fileName(period))
    }

    fun build(studioId: StudioId, period: ReportPeriod, comparison: ReportComparison): OwnerReport {
        val settings = studioSettingsRepository.findById(studioId.value).orElse(null)
        return OwnerReport(
            studioName = settings?.name?.trim()?.takeIf { it.isNotBlank() } ?: "Twoje studio",
            studioAddress = listOfNotNull(
                settings?.street?.trim()?.takeIf { it.isNotBlank() },
                listOfNotNull(settings?.postalCode?.trim(), settings?.city?.trim())
                    .filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotBlank() }
            ).joinToString(", ").takeIf { it.isNotBlank() },
            logoPng = loadLogo(studioId),
            period = period,
            current = metrics(studioId, period),
            comparison = comparison,
            baseline = when (comparison) {
                ReportComparison.PREVIOUS -> metrics(studioId, period.previous())
                ReportComparison.MEDIAN -> MetricsMedian.of(
                    period.precedingPeriods(ReportPeriod.MEDIAN_PERIODS).map { metrics(studioId, it) }
                )
            },
            snapshot = snapshot(studioId, period),
            generatedAt = Instant.now()
        )
    }

    private fun metrics(studioId: StudioId, period: ReportPeriod): PeriodMetrics {
        val sid = studioId.value
        val from = period.startInclusive
        val to = period.endExclusive

        // Koszty z dokumentów wystawionych w okresie — opłacone i nieopłacone, bo koszt
        // powstaje z fakturą, a nie z przelewem. Netto: VAT od kosztów studio odlicza.
        val finance = financeReportingHandler.getSummary(FinanceReportQuery(studioId, period.from, period.to))

        return PeriodMetrics(
            visitsStarted = queries.countVisitsStarted(sid, from, to),
            reservationsCreated = queries.countReservationsCreated(sid, from, to),
            closed = closedVisits(studioId, period),
            costsNetCents = finance.totalCosts + finance.pendingPayables,
            emails = EmailMetrics(
                sentByTeam = queries.countTeamEmailsSent(sid, from, to),
                sentAutomated = queries.countAutomatedEmailsSent(sid, from, to),
                replies = ReplyTimes.of(queries.customerWaitMinutes(sid, from, to))
            ),
            upsell = queries.upsell(sid, from, to),
            visitCardsSent = queries.countVisitCardsSent(sid, from, to),
            batch = queries.batch(sid, period.from, period.to),
            instagram = queries.instagram(sid, from, to)
        )
    }

    /**
     * Wartość wizyt wydanych w okresie — suma brutto i netto z tej samej reguły, której
     * używa ekran wizyty ([pl.detailing.crm.visit.domain.Visit.calculateTotalGross]):
     * zapisane brutto każdej pozycji, bez przeliczania z netto.
     *
     * Transakcja, bo mapowanie wizyty na domenę dociąga leniwe kolekcje.
     */
    private fun closedVisits(studioId: StudioId, period: ReportPeriod): ClosedVisits =
        transactionTemplate.execute {
            val visits = visitRepository
                .findHandedOverByStudioIdAndPickupRange(studioId.value, period.startInclusive, period.endExclusive)
                .map { it.toDomain(withPhotos = false) }
            ClosedVisits(
                count = visits.size,
                grossCents = visits.sumOf { it.calculateTotalGross().amountInCents },
                netCents = visits.sumOf { it.calculateTotalNet().amountInCents }
            )
        }!!

    private fun snapshot(studioId: StudioId, period: ReportPeriod): SnapshotMetrics {
        val (unsettledVehicles, unsettledGross) = queries.batchUnsettled(studioId.value)
        val competitors = runCatching { adDiscoveryReadService.periodSnapshot(studioId, period.from, period.to) }
            .onFailure { logger.warn("Owner report: brak danych o konkurencji dla studia {}: {}", studioId.value, it.message) }
            .getOrNull()
        return SnapshotMetrics(
            batchUnsettledVehicles = unsettledVehicles,
            batchUnsettledGrossCents = unsettledGross,
            competitors = competitors?.let {
                CompetitorMetrics(
                    advertisers = it.advertisers,
                    activeAds = it.activeAds,
                    campaignsStartedInPeriod = it.campaignsStartedInPeriod,
                    newAdvertisers = it.newAdvertisers,
                    top = it.top
                )
            }
        )
    }

    private fun loadLogo(studioId: StudioId): ByteArray? = runCatching {
        companyLogoService.loadDocumentLogo(studioId.value)?.printPng
    }.onFailure {
        logger.warn("Nie udało się wczytać logo studia na raport: ${it.message}")
    }.getOrNull()

    private fun fileName(period: ReportPeriod): String =
        "raport-${FILE_DATE.format(period.from)}-${FILE_DATE.format(period.to)}.pdf"

    private companion object {
        val FILE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}

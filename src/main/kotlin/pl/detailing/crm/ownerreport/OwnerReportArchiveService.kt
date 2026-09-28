package pl.detailing.crm.ownerreport

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.ownerreport.domain.ClosedSale
import pl.detailing.crm.ownerreport.domain.ReportArchive
import pl.detailing.crm.ownerreport.domain.ReportArchiveRow
import pl.detailing.crm.ownerreport.domain.ReportComparison
import pl.detailing.crm.ownerreport.domain.ReportLength
import pl.detailing.crm.ownerreport.domain.ReportPeriod
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.visit.domain.Visit
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.VisitServiceItemEntity
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Tabela raportów i dzień założenia konta, od którego się zaczyna. */
class OwnerReportArchive(val since: LocalDate, val rows: List<ReportArchiveRow>)

/**
 * Tabela raportów w Statystykach: wszystkie okresy od założenia konta.
 *
 * Dwa lekkie zapytania na całą historię (daty wydania + pozycje), zamiast PDF-owego
 * „wczytaj wizyty okresu" powtórzonego dla każdego z setek tygodni. Kwota wizyty
 * z pozycji liczy się tą samą funkcją co w PDF-ie ([Visit.settledGross]).
 */
@Service
class OwnerReportArchiveService(
    private val visitRepository: VisitRepository,
    private val studioRepository: StudioRepository
) {

    @Transactional(readOnly = true)
    fun archive(studioId: StudioId, length: ReportLength, comparison: ReportComparison, today: LocalDate): OwnerReportArchive {
        val since = studioRepository.findByStudioId(studioId.value)?.createdAt
            ?.atZone(ReportPeriod.ZONE)?.toLocalDate()
            ?: today
        val from = ReportArchive.dataFrom(length, comparison, since, today)
        val to = ReportPeriod.containing(length, today).endExclusive
        return OwnerReportArchive(since, ReportArchive.build(length, comparison, since, today, sales(studioId.value, from, to)))
    }

    private fun sales(studioId: UUID, from: Instant, to: Instant): List<ClosedSale> {
        val grossByVisit = visitRepository.findHandedOverItems(studioId, from, to)
            .groupBy({ it[0] as UUID }, { it[1] as VisitServiceItemEntity })
            .mapValues { (_, items) -> items.sumOf { Visit.settledGross(it.toDomain())?.amountInCents ?: 0L } }
        // Wizyta bez zaliczonych pozycji też jest wizytą zamkniętą — tak liczy PDF.
        return visitRepository.findHandedOverPickups(studioId, from, to).map { row ->
            ClosedSale(pickupAt = row[1] as Instant, grossCents = grossByVisit[row[0] as UUID] ?: 0L)
        }
    }
}

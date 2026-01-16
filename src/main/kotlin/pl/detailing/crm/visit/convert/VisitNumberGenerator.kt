package pl.detailing.crm.visit.convert

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Year

/**
 * Generates unique visit numbers in format: VIS-YYYY-NNNNN
 * Example: VIS-2025-00042
 */
@Service
class VisitNumberGenerator(
    private val visitRepository: VisitRepository
) {
    fun generateVisitNumber(studioId: StudioId): String {
        val currentYear = Year.now().value
        val yearPattern = "VIS-$currentYear-%"

        // Find latest visit number for current year
        val latestNumbers = visitRepository.findLatestVisitNumberForYear(
            studioId.value,
            yearPattern
        )

        val nextSequence = if (latestNumbers.isEmpty()) {
            1
        } else {
            // Extract sequence number from latest visit number
            val latestNumber = latestNumbers.first()
            val sequencePart = latestNumber.substringAfterLast("-")
            sequencePart.toIntOrNull()?.plus(1) ?: 1
        }

        // Format: VIS-YYYY-NNNNN (5 digits, zero-padded)
        return "VIS-$currentYear-${nextSequence.toString().padStart(5, '0')}"
    }
}

package pl.detailing.crm.ksef.exclusion

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException

data class ExcludeKsefInvoiceCommand(val studioId: StudioId, val ksefNumber: String)
data class RestoreKsefInvoiceCommand(val studioId: StudioId, val ksefNumber: String)

/**
 * Obsługuje mechanizm "ukrywania" faktur kosztowych.
 *
 * Gdy użytkownik oznaczy fakturę jako prywatną/nieistotną biznesowo (np. zakup soundbara
 * do domu), faktura otrzymuje status EXCLUDED. Pozostaje w bazie (spójność synchronizacji
 * z KSeF), ale jest odfiltrowana ze wszystkich widoków kosztowych i statystyk.
 *
 * Operacje są idempotentne – wielokrotne wywołanie exclude/restore nie rzuca błędu.
 *
 * Status EXCLUDED działa jako miękka blokada:
 * - Faktura jest pomijana w statystykach finansowych (findMonthlyStatistics, findTotalStatistics)
 * - Faktura nie pojawia się w domyślnym listingu kosztów (findActiveExpensesByStudioId)
 * - Faktura pozostaje dostępna przez bezpośredni lookup (findByStudioIdAndKsefNumber)
 *
 * Nie można wykluczyć faktury o statusie CANCELLED – jej wartości są już zerem
 * po stronie KSeF. Faktury CORRECTED można wykluczyć (np. gdy korekta dotyczy zakupu prywatnego).
 */
@Service
class ExcludeKsefInvoiceHandler(private val invoiceRepository: KsefInvoiceRepository) {

    private val log = LoggerFactory.getLogger(ExcludeKsefInvoiceHandler::class.java)

    @Transactional
    fun exclude(command: ExcludeKsefInvoiceCommand) {
        val entity = invoiceRepository.findByStudioIdAndKsefNumber(command.studioId.value, command.ksefNumber)
            ?: throw NotFoundException("Invoice not found: ${command.ksefNumber}")

        if (entity.status == "CANCELLED") {
            throw ValidationException("Cannot exclude a CANCELLED invoice (${command.ksefNumber})")
        }

        if (entity.status == "EXCLUDED") {
            log.debug("Invoice {} is already EXCLUDED, skipping", command.ksefNumber)
            return
        }

        invoiceRepository.updateStatus(command.studioId.value, command.ksefNumber, "EXCLUDED")
        log.info("Invoice {} marked as EXCLUDED for studio={}", command.ksefNumber, command.studioId)
    }

    @Transactional
    fun restore(command: RestoreKsefInvoiceCommand) {
        val entity = invoiceRepository.findByStudioIdAndKsefNumber(command.studioId.value, command.ksefNumber)
            ?: throw NotFoundException("Invoice not found: ${command.ksefNumber}")

        if (entity.status != "EXCLUDED") {
            log.debug("Invoice {} is not EXCLUDED (status={}), skipping restore", command.ksefNumber, entity.status)
            return
        }

        // Przywracamy do ACTIVE – oryginalny status przed wykluczeniem nie jest przechowywany,
        // ale faktury CORRECTED i tak mają aktualny status po stronie KSeF nadpisany w etapie fetch.
        invoiceRepository.updateStatus(command.studioId.value, command.ksefNumber, "ACTIVE")
        log.info("Invoice {} restored from EXCLUDED to ACTIVE for studio={}", command.ksefNumber, command.studioId)
    }
}

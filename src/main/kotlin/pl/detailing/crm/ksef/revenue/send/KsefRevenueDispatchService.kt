package pl.detailing.crm.ksef.revenue.send

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceEntity
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.shared.StudioId

/**
 * Jedno miejsce przejść maszyny stanów wysyłki — używane przy wystawieniu faktury,
 * ręcznym ponowieniu i przez scheduler retry. Idempotentne: faktura w stanie
 * SENDING/SUBMITTED/ACCEPTED nigdy nie jest wysyłana drugi raz (ochrona przed
 * podwójnym wystawieniem tej samej faktury w KSeF, np. po timeout'cie i retry).
 */
@Service
class KsefRevenueDispatchService(
    private val sender: KsefInvoiceSender,
    private val repository: KsefRevenueInvoiceRepository
) {
    private val log = LoggerFactory.getLogger(KsefRevenueDispatchService::class.java)

    /**
     * Próbuje wysłać fakturę do KSeF i zapisuje wynikowy stan.
     * Wywołanie dla faktury w stanie nie-retryowalnym jest no-opem (zwraca encję bez zmian).
     */
    fun dispatch(invoice: KsefRevenueInvoiceEntity): KsefRevenueInvoiceEntity {
        if (!invoice.ksefStatus.isRetryable()) {
            log.info(
                "Dispatch pominięty — faktura {} w stanie {} (idempotencja)",
                invoice.invoiceNumber, invoice.ksefStatus
            )
            return invoice
        }
        val xml = invoice.invoiceXml
            ?: run {
                invoice.markRejected("Brak wygenerowanego XML faktury — nie można wysłać")
                return repository.save(invoice)
            }

        invoice.markSending()
        repository.save(invoice)

        val outcome = sender.send(StudioId(invoice.studioId), xml)
        applySendOutcome(invoice, outcome)
        return repository.save(invoice)
    }

    /**
     * Dokańcza fakturę SUBMITTED: odpytuje status w sesji, a po przyjęciu dociąga UPO.
     * Faktura ACCEPTED bez UPO dostaje tylko uzupełnienie UPO.
     */
    fun complete(invoice: KsefRevenueInvoiceEntity): KsefRevenueInvoiceEntity {
        val studioId = StudioId(invoice.studioId)

        if (invoice.ksefStatus == KsefRevenueStatus.ACCEPTED) {
            if (invoice.upoXml == null && invoice.sessionReference != null && invoice.ksefNumber != null) {
                sender.fetchUpo(studioId, invoice.sessionReference!!, invoice.ksefNumber!!)?.let {
                    invoice.upoXml = it
                    return repository.save(invoice)
                }
            }
            return invoice
        }

        val sessionRef = invoice.sessionReference
        val invoiceRef = invoice.invoiceReference
        if (invoice.ksefStatus != KsefRevenueStatus.SUBMITTED || sessionRef == null || invoiceRef == null) {
            return invoice
        }

        when (val status = sender.checkStatus(studioId, sessionRef, invoiceRef)) {
            is KsefStatusOutcome.Accepted -> {
                invoice.markAccepted(status.ksefNumber)
                invoice.upoXml = status.upoXml
                status.invoiceHash?.let { invoice.invoiceHash = it }
            }

            is KsefStatusOutcome.Rejected -> invoice.markRejected(status.error)

            is KsefStatusOutcome.StillProcessing -> return invoice

            is KsefStatusOutcome.TransientFailure -> {
                log.warn("Nie udało się sprawdzić statusu faktury {}: {}", invoice.invoiceNumber, status.error)
                return invoice
            }
        }
        return repository.save(invoice)
    }

    private fun applySendOutcome(invoice: KsefRevenueInvoiceEntity, outcome: KsefSendOutcome) {
        when (outcome) {
            is KsefSendOutcome.Accepted -> {
                invoice.markAccepted(outcome.ksefNumber)
                invoice.upoXml = outcome.upoXml
                outcome.invoiceHash?.let { invoice.invoiceHash = it }
                log.info("Faktura {} przyjęta w KSeF: {}", invoice.invoiceNumber, outcome.ksefNumber)
            }

            is KsefSendOutcome.Submitted -> {
                invoice.markSubmitted(outcome.sessionReference, outcome.invoiceReference, outcome.invoiceHash)
                log.info("Faktura {} przyjęta do sesji, oczekuje na numer KSeF", invoice.invoiceNumber)
            }

            is KsefSendOutcome.Rejected -> {
                invoice.markRejected(outcome.error)
                log.warn("Faktura {} odrzucona przez KSeF: {}", invoice.invoiceNumber, outcome.error)
            }

            is KsefSendOutcome.TransientFailure -> {
                invoice.markQueuedForRetry(outcome.error)
                log.warn(
                    "Faktura {} w kolejce retry (offline24): {}",
                    invoice.invoiceNumber, outcome.error
                )
            }
        }
    }
}

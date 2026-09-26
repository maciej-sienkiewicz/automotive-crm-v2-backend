package pl.detailing.crm.finance.document

import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.shared.ValidationException

/**
 * Storno i dokument zastąpiony w poprawce rozliczenia to para, która sumuje się do zera.
 * Ręczna edycja albo usunięcie jednej połowy rozjechałoby kasę, raport form płatności
 * i historię wizyty — takie dokumenty zmienia się wyłącznie kolejną poprawką rozliczenia.
 */
internal fun requireNotPartOfSettlementCorrection(document: FinancialDocumentEntity) {
    if (document.documentType == DocumentType.CORRECTION) {
        throw ValidationException(
            "Dokument ${document.documentNumber} to korekta — nie zmienia się go ręcznie. " +
                "Popraw rozliczenie wizyty jeszcze raz."
        )
    }
    if (document.supersededAt != null) {
        throw ValidationException(
            "Dokument ${document.documentNumber} został zastąpiony w poprawce rozliczenia wizyty — " +
                "zostaje w historii bez zmian."
        )
    }
}

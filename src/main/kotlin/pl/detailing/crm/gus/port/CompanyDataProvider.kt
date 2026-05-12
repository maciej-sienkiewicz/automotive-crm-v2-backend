package pl.detailing.crm.gus.port

import pl.detailing.crm.gus.domain.CompanyInfo

/**
 * Port oddzielający logikę biznesową od konkretnej implementacji dostawcy danych firmowych.
 * Aktualny adapter: GUS BIR SOAP API (wersja 1.1).
 * Wymiana na inny protokół/dostawcę wymaga jedynie nowej implementacji tego interfejsu.
 */
interface CompanyDataProvider {
    fun findByNip(nip: String): CompanyInfo
}

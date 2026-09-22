package pl.detailing.crm.visit.services

import pl.detailing.crm.appointment.domain.AdjustmentType

data class ServicesChangesPayload(
    val notifyCustomer: Boolean,
    val requireConfirmation: Boolean = true,
    val added: List<AddedService>,
    val updated: List<UpdatedService>,
    val deleted: List<DeletedService>,
    /**
     * Treść SMS-a zatwierdzona przez użytkownika CRM-a (propozycja z CRM-a, po ewentualnej edycji).
     * Puste = wysyłamy treść z szablonu. Wezwanie do odpowiedzi "TAK" jest doklejane
     * przy wysyłce i nie pochodzi z tego pola.
     */
    val smsMessage: String? = null,
    /**
     * true = wysyłamy SMS z polskimi znakami (UCS-2, drożej).
     * Domyślnie false — treść jest transliterowana na ASCII tuż przed wysyłką.
     */
    val smsUsePolishCharacters: Boolean = false
)

data class AddedService(
    val serviceId: String?,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val adjustment: ServiceAdjustment?,
    val note: String?,
    /**
     * Dokładne brutto ceny bazowej, gdy użytkownik wpisał ją od strony brutto (CLAUDE.md §1).
     * `null` = cena od strony netta (albo stary klient API) — wtedy brutto z katalogu,
     * o ile cena bazowa jest katalogowa, a w przeciwnym razie z netta.
     */
    val basePriceGross: Long? = null
)

data class ServiceAdjustment(
    val type: AdjustmentType,
    val value: Double  // Double to support decimal percentages like -49.19
)

data class UpdatedService(
    val serviceLineItemId: String,
    val basePriceNet: Long,
    val vatRate: Int? = null,
    val adjustment: ServiceAdjustment? = null,
    /**
     * Dokładne brutto nowej ceny bazowej, gdy użytkownik wpisał ją od strony brutto — także
     * przy zmianie stawki VAT, która ma zachować wpisane brutto. `null` przy niezmienionej
     * cenie i stawce = pozycja zachowuje swoje dotychczasowe brutto.
     */
    val basePriceGross: Long? = null
)

data class DeletedService(
    val serviceLineItemId: String
)

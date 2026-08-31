package pl.detailing.crm.visit.drafts

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Ustawienia cyklu życia nieukończonych przyjęć (wizyt w statusie DRAFT).
 *
 * Wartości są decyzją operacyjną, nie szczegółem implementacji: mówią, po jakim czasie
 * otwarte przyjęcie uznajemy za zapomniane, a po jakim za porzucone. Dlatego są w
 * konfiguracji, a nie jako stałe rozsiane po trzech klasach.
 */
@ConfigurationProperties(prefix = "crm.visits.drafts")
data class DraftVisitProperties(

    /**
     * Po ilu godzinach nieukończone przyjęcie ma być w kolejce wyróżnione jako zaległe.
     * Domyślne 2 h to mniej więcej długość zmiany: dłużej otwarte przyjęcie znaczy, że
     * ktoś o nim zapomniał, a nie że właśnie je prowadzi.
     */
    val staleAfterHours: Long = 2,

    /** Włącznik zadania sprzątającego. */
    val cleanupEnabled: Boolean = true,

    /**
     * Po ilu godzinach porzucone przyjęcie jest anulowane automatycznie (twarde
     * usunięcie szkicu, tak jak przy ręcznym „Anuluj wizytę"; rezerwacja zostaje i można
     * przyjąć auto od nowa).
     *
     * Wartość jest celowo ostrożna. Szkic niesie zdjęcia, mapę uszkodzeń i wygenerowane
     * protokoły — kasowanie go po godzinie zabierałoby pracę obsłudze, która przyjęcie
     * przerwała i wraca do niego po przerwie. Doba z hakiem przechodzi przez noc i przez
     * zmianę, a kolejka nieukończonych przyjęć daje ludziom szansę domknąć je wcześniej.
     */
    val expireAfterHours: Long = 48,

    /** Ile szkiców zamyka jedno uruchomienie zadania. Chroni przed długim przebiegiem. */
    val cleanupBatchSize: Int = 100,

    /** Wyrażenie cron zadania sprzątającego (domyślnie: codziennie o 3:50). */
    val cleanupCron: String = "0 50 3 * * *"
)

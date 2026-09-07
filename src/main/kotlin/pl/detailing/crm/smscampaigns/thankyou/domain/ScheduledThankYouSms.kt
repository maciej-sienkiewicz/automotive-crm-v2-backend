package pl.detailing.crm.smscampaigns.thankyou.domain

import java.time.Instant
import java.util.UUID

enum class ScheduledThankYouSmsStatus {
    /** Czeka na wysyłkę o [ScheduledThankYouSms.scheduledFor]. */
    PENDING,
    SENT,
    FAILED,

    /**
     * Studio świadomie zrezygnowało z podziękowania przy wydaniu pojazdu — albo
     * odznaczyło pole, albo nie było dokąd wysłać (klient bez numeru).
     *
     * Wpis powstaje mimo braku wysyłki, bo to on wyłącza automat: bez niego
     * [pl.detailing.crm.smscampaigns.automation.SmsAutomationScheduler] wysłałby
     * podziękowanie mimo odznaczenia pola.
     */
    SKIPPED,

    /**
     * Wizyta została usunięta, zanim podziękowanie zdążyło wyjść.
     *
     * Wpis zostaje (nadal wyłącza automat), ale nic już z niego nie pójdzie: klient
     * nie ma dostać podziękowania za wizytę, której studio u siebie nie widzi.
     */
    CANCELLED
}

/**
 * Decyzja o podziękowaniu po wizycie, podjęta w oknie „Wydanie pojazdu".
 *
 * Jedna decyzja na wizytę i to ona rozstrzyga: reguła POST_VISIT z ustawień mówi
 * tylko, *czy* studio w ogóle takie SMS-y wysyła i jakiej treści. Kiedy wysłać,
 * ustala człowiek przy ladzie — dlatego automat pomija wizyty, dla których taki
 * wpis istnieje, niezależnie od jego statusu.
 *
 * [phoneNumber] i [messageContent] są rozstrzygane w chwili planowania: późniejsza
 * zmiana numeru w kartotece czy szablonu w ustawieniach nie przepisuje wiadomości,
 * na którą ktoś już się zgodził. Oba pola są puste dla decyzji [ScheduledThankYouSmsStatus.SKIPPED],
 * bo nie ma czego zamrażać.
 */
data class ScheduledThankYouSms(
    val id: UUID,
    val studioId: UUID,
    val visitId: UUID,
    val appointmentId: UUID,
    val customerId: UUID,
    val phoneNumber: String?,
    val messageContent: String?,
    val scheduledFor: Instant?,
    val status: ScheduledThankYouSmsStatus,
    val sentAt: Instant?,
    val externalMessageId: String?,
    val errorMessage: String?,
    val createdBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant
)

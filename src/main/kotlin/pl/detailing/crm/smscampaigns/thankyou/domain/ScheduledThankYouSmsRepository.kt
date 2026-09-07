package pl.detailing.crm.smscampaigns.thankyou.domain

import java.time.Instant
import java.util.UUID

interface ScheduledThankYouSmsRepository {

    fun save(sms: ScheduledThankYouSms): ScheduledThankYouSms

    /**
     * Czy dla tej rezerwacji zapadła już decyzja o podziękowaniu — w dowolnym statusie.
     *
     * Pytanie zadaje automat POST_VISIT: decyzja z okna wydania (także odmowna)
     * zdejmuje wizytę z jego kolejki.
     */
    fun existsByAppointmentId(appointmentId: UUID): Boolean

    /** Aktywna (PENDING) decyzja dla wizyty — jedna albo żadna. */
    fun findPendingByVisitId(visitId: UUID): ScheduledThankYouSms?

    /** Wszystkie decyzje PENDING, których termin już minął. */
    fun findDueForDispatch(now: Instant): List<ScheduledThankYouSms>

    /**
     * Wycofuje oczekujące podziękowanie dla wizyty. Wywoływane przy usunięciu wizyty;
     * brak oczekującego wpisu to nie błąd, tylko najczęstszy przypadek.
     */
    fun cancelPendingForVisit(visitId: UUID)
}

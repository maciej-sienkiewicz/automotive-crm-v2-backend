package pl.detailing.crm.subscription.lifecycle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.shared.SubscriptionStatus.ACTIVE
import pl.detailing.crm.shared.SubscriptionStatus.EXPIRED
import pl.detailing.crm.shared.SubscriptionStatus.NO_PLAN
import pl.detailing.crm.shared.SubscriptionStatus.PAST_DUE
import pl.detailing.crm.shared.SubscriptionStatus.TRIALING
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * [SubscriptionLifecycle] to jedyna definicja „czy studio może działać" — pyta ją interceptor
 * HTTP, zadania w tle i job wygaszający. Dawniej każdy z nich miał własną granicę czasu
 * (`<` kontra `isAfter`) i `PAST_DUE` bez daty dawał dostęp bez końca (audyt, S5, S8), więc
 * te testy pilnują przede wszystkim GRANIC: dokładnie w chwili końca terminu dostępu już nie ma.
 */
class SubscriptionLifecycleTest {

    private val now: Instant = Instant.parse("2026-10-01T12:00:00Z")
    private val grace: Duration = Duration.ofDays(7)
    private val noGrace: Duration = Duration.ZERO
    private val oneNano: Duration = Duration.ofNanos(1)

    private fun billing(
        status: SubscriptionStatus,
        trialEndsAt: Instant? = null,
        subscriptionEndsAt: Instant? = null,
        graceEndsAt: Instant? = null
    ) = BillingSnapshot(status, trialEndsAt, subscriptionEndsAt, graceEndsAt)

    private fun days(n: Long): Duration = Duration.ofDays(n)

    // ─── isAccessible / accessEndsAt ──────────────────────────────────────────

    @Nested
    inner class Dostep {

        @Test
        fun `NO_PLAN nie ma dostepu nawet z datami w przyszlosci`() {
            // Status rozstrzyga, nie daty: studio bez planu z resztkami dat po starym zakupie
            // nie może dostać dostępu tylko dlatego, że kolumna nie została wyczyszczona.
            val b = billing(NO_PLAN, trialEndsAt = now + days(5), subscriptionEndsAt = now + days(5))
            assertNull(SubscriptionLifecycle.accessEndsAt(b, grace))
            assertFalse(SubscriptionLifecycle.isAccessible(b, now, grace))
        }

        @Test
        fun `EXPIRED nie ma dostepu nawet z data konca okresu w przyszlosci`() {
            val b = billing(EXPIRED, subscriptionEndsAt = now + days(5), graceEndsAt = now + days(10))
            assertNull(SubscriptionLifecycle.accessEndsAt(b, grace))
            assertFalse(SubscriptionLifecycle.isAccessible(b, now, grace))
        }

        @Test
        fun `TRIALING ma dostep do konca triala i karencja go nie wydluza`() {
            // Karencja jest dla płacących, którzy spóźnili się z odnowieniem — trial nie
            // dostaje 7 darmowych dni ekstra.
            val b = billing(TRIALING, trialEndsAt = now + days(3), subscriptionEndsAt = now + days(30))
            assertEquals(now + days(3), SubscriptionLifecycle.accessEndsAt(b, grace))
            assertTrue(SubscriptionLifecycle.isAccessible(b, now, grace))
            assertTrue(SubscriptionLifecycle.isAccessible(b, now + days(3) - oneNano, grace))
            assertFalse(SubscriptionLifecycle.isAccessible(b, now + days(4), grace))
        }

        @Test
        fun `TRIALING dokladnie w chwili konca triala nie ma dostepu`() {
            val b = billing(TRIALING, trialEndsAt = now)
            assertFalse(SubscriptionLifecycle.isAccessible(b, now, grace))
        }

        @Test
        fun `TRIALING bez daty konca nie ma dostepu`() {
            val b = billing(TRIALING, trialEndsAt = null)
            assertNull(SubscriptionLifecycle.accessEndsAt(b, grace))
            assertFalse(SubscriptionLifecycle.isAccessible(b, now, grace))
        }

        @Test
        fun `ACTIVE ma dostep do konca okresu plus karencja`() {
            val end = now + days(10)
            val b = billing(ACTIVE, subscriptionEndsAt = end)
            assertEquals(end + grace, SubscriptionLifecycle.accessEndsAt(b, grace))
            assertTrue(SubscriptionLifecycle.isAccessible(b, now, grace))
        }

        @Test
        fun `ACTIVE po koncu okresu, ktorego job jeszcze nie przestawil, nadal ma dostep w karencji`() {
            // Opóźnienie joba nie może zmieniać odpowiedzi: studio ACTIVE z minionym okresem
            // jest traktowane dokładnie tak, jak PAST_DUE, w które za chwilę przejdzie.
            val b = billing(ACTIVE, subscriptionEndsAt = now - days(2))
            assertTrue(SubscriptionLifecycle.isAccessible(b, now, grace))
        }

        @Test
        fun `ACTIVE dokladnie w chwili konca karencji nie ma dostepu`() {
            val end = now - grace
            val b = billing(ACTIVE, subscriptionEndsAt = end)
            assertFalse(SubscriptionLifecycle.isAccessible(b, now, grace))
            assertTrue(SubscriptionLifecycle.isAccessible(b, now - oneNano, grace))
        }

        @Test
        fun `ACTIVE bez karencji traci dostep co do chwili konca okresu`() {
            val b = billing(ACTIVE, subscriptionEndsAt = now)
            assertEquals(now, SubscriptionLifecycle.accessEndsAt(b, noGrace))
            assertFalse(SubscriptionLifecycle.isAccessible(b, now, noGrace))
            assertTrue(SubscriptionLifecycle.isAccessible(b, now - oneNano, noGrace))
        }

        @Test
        fun `ACTIVE bez daty konca okresu nie ma dostepu`() {
            val b = billing(ACTIVE, subscriptionEndsAt = null)
            assertNull(SubscriptionLifecycle.accessEndsAt(b, grace))
            assertFalse(SubscriptionLifecycle.isAccessible(b, now, grace))
        }

        @Test
        fun `PAST_DUE ma dostep do zapisanego konca karencji, nie do przeliczonego z biezacej konfiguracji`() {
            // grace_ends_at ustalono przy wejściu w PAST_DUE — zmiana konfiguracji karencji
            // w trakcie nie skraca ani nie wydłuża terminu, który studio już zna.
            val b = billing(PAST_DUE, subscriptionEndsAt = now - days(1), graceEndsAt = now + days(2))
            assertEquals(now + days(2), SubscriptionLifecycle.accessEndsAt(b, grace))
            assertEquals(now + days(2), SubscriptionLifecycle.accessEndsAt(b, noGrace))
            assertTrue(SubscriptionLifecycle.isAccessible(b, now, noGrace))
        }

        @Test
        fun `PAST_DUE dokladnie w chwili konca karencji nie ma dostepu`() {
            val b = billing(PAST_DUE, subscriptionEndsAt = now - grace, graceEndsAt = now)
            assertFalse(SubscriptionLifecycle.isAccessible(b, now, grace))
            assertTrue(SubscriptionLifecycle.isAccessible(b, now - oneNano, grace))
        }

        @Test
        fun `PAST_DUE bez grace_ends_at liczy karencje od konca okresu`() {
            // Wiersze sprzed V172 nie mają grace_ends_at. Fallback na koniec okresu + karencja
            // zamiast dawnego „dostępu bez końca".
            val b = billing(PAST_DUE, subscriptionEndsAt = now - days(2), graceEndsAt = null)
            assertEquals(now + days(5), SubscriptionLifecycle.accessEndsAt(b, grace))
            assertTrue(SubscriptionLifecycle.isAccessible(b, now, grace))

            val stale = billing(PAST_DUE, subscriptionEndsAt = now - days(8), graceEndsAt = null)
            assertFalse(SubscriptionLifecycle.isAccessible(stale, now, grace))
        }

        @Test
        fun `PAST_DUE bez zadnej daty nie ma dostepu - dawniej mial go bezterminowo (audyt S5)`() {
            val b = billing(PAST_DUE, subscriptionEndsAt = null, graceEndsAt = null)
            assertNull(SubscriptionLifecycle.accessEndsAt(b, grace))
            assertFalse(SubscriptionLifecycle.isAccessible(b, now, grace))
        }
    }

    // ─── isInGrace ────────────────────────────────────────────────────────────

    @Nested
    inner class Karencja {

        @Test
        fun `PAST_DUE przed koncem karencji jest w karencji, po nim juz nie`() {
            val b = billing(PAST_DUE, subscriptionEndsAt = now - days(1), graceEndsAt = now + days(6))
            assertTrue(SubscriptionLifecycle.isInGrace(b, now, grace))
            assertFalse(SubscriptionLifecycle.isInGrace(b, now + days(6), grace))
        }

        @Test
        fun `ACTIVE w trakcie okresu nie jest w karencji`() {
            val b = billing(ACTIVE, subscriptionEndsAt = now + oneNano)
            assertFalse(SubscriptionLifecycle.isInGrace(b, now, grace))
        }

        @Test
        fun `ACTIVE dokladnie w chwili konca okresu jest juz w karencji`() {
            // Granica „termin w przyszłości = isAfter(now)" — w chwili końca okres nie trwa,
            // więc to już karencja (inaczej przez jedną chwilę nie byłoby ani okresu, ani karencji).
            val b = billing(ACTIVE, subscriptionEndsAt = now)
            assertTrue(SubscriptionLifecycle.isInGrace(b, now, grace))
            assertFalse(SubscriptionLifecycle.hasRunningPaidPeriod(b, now))
        }

        @Test
        fun `ACTIVE po karencji nie jest w karencji`() {
            val b = billing(ACTIVE, subscriptionEndsAt = now - grace)
            assertFalse(SubscriptionLifecycle.isInGrace(b, now, grace))
        }

        @Test
        fun `bez karencji koniec okresu nie zostawia ani chwili karencji`() {
            val b = billing(ACTIVE, subscriptionEndsAt = now)
            assertFalse(SubscriptionLifecycle.isInGrace(b, now, noGrace))
        }

        @Test
        fun `TRIALING, NO_PLAN i EXPIRED nigdy nie sa w karencji`() {
            listOf(
                billing(TRIALING, trialEndsAt = now - days(1)),
                billing(TRIALING, trialEndsAt = now + days(1)),
                billing(NO_PLAN, subscriptionEndsAt = now - days(1)),
                billing(EXPIRED, subscriptionEndsAt = now - days(1), graceEndsAt = now + days(3))
            ).forEach { assertFalse(SubscriptionLifecycle.isInGrace(it, now, grace), "$it") }
        }
    }

    // ─── hasRunningPaidPeriod / isTrialRunning ────────────────────────────────

    @Nested
    inner class TrwajacyOkres {

        @Test
        fun `opłacony okres trwa tylko w ACTIVE przed koncem okresu`() {
            assertTrue(SubscriptionLifecycle.hasRunningPaidPeriod(billing(ACTIVE, subscriptionEndsAt = now + days(1)), now))
            assertFalse(SubscriptionLifecycle.hasRunningPaidPeriod(billing(ACTIVE, subscriptionEndsAt = now), now))
            assertFalse(SubscriptionLifecycle.hasRunningPaidPeriod(billing(ACTIVE, subscriptionEndsAt = null), now))
        }

        @Test
        fun `PAST_DUE, TRIALING, NO_PLAN i EXPIRED nie maja trwajacego oplaconego okresu nawet z przyszla data`() {
            // Proracja ma sens tylko w opłaconym okresie: w trialu zmiana jest darmowa,
            // a w pozostałych stanach najpierw trzeba odnowić (audyt, S4 — upgrade za 0 zł).
            listOf(PAST_DUE, TRIALING, NO_PLAN, EXPIRED).forEach { status ->
                val b = billing(status, trialEndsAt = now + days(5), subscriptionEndsAt = now + days(5), graceEndsAt = now + days(9))
                assertFalse(SubscriptionLifecycle.hasRunningPaidPeriod(b, now), "$status")
            }
        }

        @Test
        fun `trial trwa do chwili konca, wylacznie`() {
            assertTrue(SubscriptionLifecycle.isTrialRunning(billing(TRIALING, trialEndsAt = now + oneNano), now))
            assertFalse(SubscriptionLifecycle.isTrialRunning(billing(TRIALING, trialEndsAt = now), now))
            assertFalse(SubscriptionLifecycle.isTrialRunning(billing(TRIALING, trialEndsAt = null), now))
            assertFalse(SubscriptionLifecycle.isTrialRunning(billing(ACTIVE, trialEndsAt = now + days(1)), now))
        }
    }

    // ─── dueTransition ────────────────────────────────────────────────────────

    @Nested
    inner class NaleznePrzejscie {

        @Test
        fun `TRIALING przed koncem triala - nic`() {
            assertNull(SubscriptionLifecycle.dueTransition(billing(TRIALING, trialEndsAt = now + oneNano), now, grace))
        }

        @Test
        fun `TRIALING dokladnie w chwili konca triala - EXPIRED bez karencji`() {
            val t = SubscriptionLifecycle.dueTransition(billing(TRIALING, trialEndsAt = now), now, grace)
            assertEquals(LifecycleTransition(EXPIRED, null), t)
        }

        @Test
        fun `ACTIVE przed koncem okresu - nic`() {
            assertNull(SubscriptionLifecycle.dueTransition(billing(ACTIVE, subscriptionEndsAt = now + oneNano), now, grace))
        }

        @Test
        fun `ACTIVE dokladnie w chwili konca okresu - PAST_DUE z koncem karencji liczonym od konca okresu`() {
            // Koniec karencji liczony od końca okresu, nie od chwili przebiegu joba — inaczej
            // każde opóźnienie joba wydłużałoby darmowy dostęp.
            val t = SubscriptionLifecycle.dueTransition(billing(ACTIVE, subscriptionEndsAt = now), now, grace)
            assertEquals(LifecycleTransition(PAST_DUE, now + grace), t)
        }

        @Test
        fun `ACTIVE, ktorego job nie przestawil przez kilka dni - PAST_DUE z karencja od konca okresu`() {
            val end = now - days(3)
            val t = SubscriptionLifecycle.dueTransition(billing(ACTIVE, subscriptionEndsAt = end), now, grace)
            assertEquals(LifecycleTransition(PAST_DUE, end + grace), t)
        }

        @Test
        fun `ACTIVE po calej karencji - od razu EXPIRED, bez przystanku w PAST_DUE`() {
            val t = SubscriptionLifecycle.dueTransition(billing(ACTIVE, subscriptionEndsAt = now - grace), now, grace)
            assertEquals(LifecycleTransition(EXPIRED, null), t)
        }

        @Test
        fun `ACTIVE przy karencji zero - od razu EXPIRED w chwili konca okresu`() {
            val t = SubscriptionLifecycle.dueTransition(billing(ACTIVE, subscriptionEndsAt = now), now, noGrace)
            assertEquals(LifecycleTransition(EXPIRED, null), t)
        }

        @Test
        fun `PAST_DUE przed koncem karencji - nic`() {
            val b = billing(PAST_DUE, subscriptionEndsAt = now - days(1), graceEndsAt = now + oneNano)
            assertNull(SubscriptionLifecycle.dueTransition(b, now, grace))
        }

        @Test
        fun `PAST_DUE dokladnie w chwili konca karencji - EXPIRED i czyszczenie grace_ends_at`() {
            val b = billing(PAST_DUE, subscriptionEndsAt = now - grace, graceEndsAt = now)
            assertEquals(LifecycleTransition(EXPIRED, null), SubscriptionLifecycle.dueTransition(b, now, grace))
        }

        @Test
        fun `PAST_DUE bez grace_ends_at korzysta z konca okresu plus karencja`() {
            val inGrace = billing(PAST_DUE, subscriptionEndsAt = now - days(2), graceEndsAt = null)
            assertNull(SubscriptionLifecycle.dueTransition(inGrace, now, grace))

            val afterGrace = billing(PAST_DUE, subscriptionEndsAt = now - grace, graceEndsAt = null)
            assertEquals(LifecycleTransition(EXPIRED, null), SubscriptionLifecycle.dueTransition(afterGrace, now, grace))
        }

        @Test
        fun `PAST_DUE bez zadnej daty - stan uszkodzony zamykany jako EXPIRED`() {
            val b = billing(PAST_DUE, subscriptionEndsAt = null, graceEndsAt = null)
            assertEquals(LifecycleTransition(EXPIRED, null), SubscriptionLifecycle.dueTransition(b, now, grace))
        }

        @Test
        fun `NO_PLAN i EXPIRED nigdy nie maja naleznego przejscia`() {
            listOf(
                billing(NO_PLAN, trialEndsAt = now - days(1), subscriptionEndsAt = now - days(30)),
                billing(EXPIRED, trialEndsAt = now - days(1), subscriptionEndsAt = now - days(30), graceEndsAt = now - days(23))
            ).forEach { assertNull(SubscriptionLifecycle.dueTransition(it, now, grace), "$it") }
        }

        @Test
        fun `przejscie nie zmienia odpowiedzi o dostep i jest jednorazowe`() {
            // Sedno projektu: interceptor pyta o dostęp bez czekania na job, więc wykonanie
            // należnego przejścia nie może zmienić odpowiedzi w tej samej chwili. A po
            // przejściu nic już nie jest należne — inaczej job przestawiałby studio w kółko.
            val ends = listOf(null, now - days(30), now - grace, now - grace + oneNano, now - days(1), now, now + days(1))
            val graces = listOf(grace, noGrace)
            val snapshots = SubscriptionStatus.entries.flatMap { status ->
                ends.flatMap { end ->
                    listOf(null, now - days(1), now, now + days(1)).flatMap { graceEnd ->
                        listOf(null, now - days(1), now, now + days(1)).map { trialEnd ->
                            billing(status, trialEndsAt = trialEnd, subscriptionEndsAt = end, graceEndsAt = graceEnd)
                        }
                    }
                }
            }

            var checked = 0
            for (g in graces) for (before in snapshots) {
                val transition = SubscriptionLifecycle.dueTransition(before, now, g) ?: continue
                val after = before.copy(status = transition.to, graceEndsAt = transition.graceEndsAt)
                assertEquals(
                    SubscriptionLifecycle.isAccessible(before, now, g),
                    SubscriptionLifecycle.isAccessible(after, now, g),
                    "dostęp zmienił się po przejściu $before → $after (karencja $g)"
                )
                assertNull(SubscriptionLifecycle.dueTransition(after, now, g), "kolejne przejście po $after (karencja $g)")
                assertNotEquals(before.status, after.status, "przejście w ten sam status $before")
                checked++
            }
            assertTrue(checked > 50, "za mało sprawdzonych przejść ($checked)")
        }

        @Test
        fun `studio bez dostepu zawsze ma nalezne przejscie do EXPIRED albo juz jest wygasle`() {
            // Wyjątek: stany bez daty (ACTIVE/TRIALING bez terminu) — job ich nie wybiera
            // (zapytanie `<= :now` na NULL), a dostęp i tak jest zamknięty.
            val samples = listOf(
                billing(TRIALING, trialEndsAt = now - days(1)),
                billing(ACTIVE, subscriptionEndsAt = now - days(8)),
                billing(PAST_DUE, subscriptionEndsAt = now - days(8), graceEndsAt = now - days(1)),
                billing(PAST_DUE)
            )
            samples.forEach {
                assertFalse(SubscriptionLifecycle.isAccessible(it, now, grace), "$it")
                assertEquals(EXPIRED, SubscriptionLifecycle.dueTransition(it, now, grace)?.to, "$it")
            }
        }
    }

    // ─── paidPeriodStart ──────────────────────────────────────────────────────

    @Nested
    inner class PoczatekOplaconegoOkresu {

        @Test
        fun `zakup w trakcie triala liczy okres od konca triala`() {
            // Zakup nie przepala reszty triala — klient płaci za 30 dni PO trialu.
            val trialEnd = now + days(4)
            val b = billing(TRIALING, trialEndsAt = trialEnd)
            assertEquals(trialEnd, SubscriptionLifecycle.paidPeriodStart(b, now, grace))
        }

        @Test
        fun `zakup po koncu triala, ktorego job jeszcze nie wygasil, liczy sie od zaplaty`() {
            val b = billing(TRIALING, trialEndsAt = now - days(1))
            assertEquals(now, SubscriptionLifecycle.paidPeriodStart(b, now, grace))
        }

        @Test
        fun `zakup dokladnie w chwili konca triala liczy sie od zaplaty`() {
            val b = billing(TRIALING, trialEndsAt = now)
            assertEquals(now, SubscriptionLifecycle.paidPeriodStart(b, now, grace))
        }

        @Test
        fun `odnowienie w trakcie okresu przedluza od starego konca`() {
            val end = now + days(10)
            val b = billing(ACTIVE, subscriptionEndsAt = end)
            assertEquals(end, SubscriptionLifecycle.paidPeriodStart(b, now, grace))
        }

        @Test
        fun `odnowienie w karencji liczy sie od starego konca - dni karencji sa platne`() {
            // Gdyby nowy okres liczył się od zapłaty, każde spóźnione odnowienie dawałoby
            // do 7 darmowych dni.
            val oldEnd = now - days(5)
            val pastDue = billing(PAST_DUE, subscriptionEndsAt = oldEnd, graceEndsAt = oldEnd + grace)
            assertEquals(oldEnd, SubscriptionLifecycle.paidPeriodStart(pastDue, now, grace))

            // To samo, gdy job jeszcze nie przestawił ACTIVE → PAST_DUE.
            val lateActive = billing(ACTIVE, subscriptionEndsAt = oldEnd)
            assertEquals(oldEnd, SubscriptionLifecycle.paidPeriodStart(lateActive, now, grace))
        }

        @Test
        fun `odnowienie w karencji bez grace_ends_at tez liczy sie od starego konca`() {
            val oldEnd = now - days(5)
            val b = billing(PAST_DUE, subscriptionEndsAt = oldEnd, graceEndsAt = null)
            assertEquals(oldEnd, SubscriptionLifecycle.paidPeriodStart(b, now, grace))
        }

        @Test
        fun `odnowienie po karencji liczy sie od zaplaty`() {
            val oldEnd = now - days(9)
            val pastDue = billing(PAST_DUE, subscriptionEndsAt = oldEnd, graceEndsAt = oldEnd + grace)
            assertEquals(now, SubscriptionLifecycle.paidPeriodStart(pastDue, now, grace))

            val lateActive = billing(ACTIVE, subscriptionEndsAt = oldEnd)
            assertEquals(now, SubscriptionLifecycle.paidPeriodStart(lateActive, now, grace))
        }

        @Test
        fun `odnowienie dokladnie w chwili konca karencji liczy sie od zaplaty`() {
            val oldEnd = now - grace
            val b = billing(PAST_DUE, subscriptionEndsAt = oldEnd, graceEndsAt = now)
            assertEquals(now, SubscriptionLifecycle.paidPeriodStart(b, now, grace))
        }

        @Test
        fun `bez karencji zaplata po koncu okresu liczy sie od zaplaty, a przed koncem od konca`() {
            val end = now
            val b = billing(ACTIVE, subscriptionEndsAt = end)
            val justBefore = end - Duration.ofHours(1)
            val justAfter = end + Duration.ofMinutes(1)

            assertEquals(end, SubscriptionLifecycle.paidPeriodStart(b, justBefore, noGrace))
            assertEquals(justAfter, SubscriptionLifecycle.paidPeriodStart(b, justAfter, noGrace))
            // Dla porównania: ta sama zapłata przy 7 dniach karencji przedłuża od starego końca.
            assertEquals(end, SubscriptionLifecycle.paidPeriodStart(b, justAfter, grace))
        }

        @Test
        fun `EXPIRED i NO_PLAN licza sie od zaplaty, nawet z niedawnym koncem okresu`() {
            val expired = billing(EXPIRED, subscriptionEndsAt = now - days(1), graceEndsAt = now + days(6))
            assertEquals(now, SubscriptionLifecycle.paidPeriodStart(expired, now, grace))

            val noPlan = billing(NO_PLAN, trialEndsAt = now + days(3), subscriptionEndsAt = now + days(3))
            assertEquals(now, SubscriptionLifecycle.paidPeriodStart(noPlan, now, grace))
        }

        @Test
        fun `PAST_DUE bez konca okresu liczy sie od zaplaty`() {
            val b = billing(PAST_DUE, subscriptionEndsAt = null, graceEndsAt = now + days(3))
            assertEquals(now, SubscriptionLifecycle.paidPeriodStart(b, now, grace))
        }

        @Test
        fun `poczatek okresu zalezy od chwili zaplaty, nie od chwili realizacji`() {
            // Realizacja ponowiona po godzinach (worker rekoncyliacji) nie może przesuwać dat:
            // płatność otrzymana w karencji przedłuża od starego końca, choć realizacja
            // przychodzi już po karencji.
            val oldEnd = now - grace + Duration.ofHours(1)
            val b = billing(PAST_DUE, subscriptionEndsAt = oldEnd, graceEndsAt = oldEnd + grace)
            val paidAt = now
            val fulfilledAt = now + Duration.ofHours(2)
            assertEquals(oldEnd, SubscriptionLifecycle.paidPeriodStart(b, paidAt, grace))
            // Kontrast: gdyby wołający podał chwilę realizacji, okres liczyłby się od niej.
            assertEquals(fulfilledAt, SubscriptionLifecycle.paidPeriodStart(b, fulfilledAt, grace))
        }
    }

    // ─── Konfiguracja i polityka z zegarem ───────────────────────────────────

    @Nested
    inner class Polityka {

        private fun policy(graceDays: Long) =
            SubscriptionAccessPolicy(SubscriptionProperties(gracePeriodDays = graceDays), Clock.fixed(now, ZoneOffset.UTC))

        @Test
        fun `domyslna karencja to 7 dni, ujemna konfiguracja to zero, nie cofniecie terminu`() {
            // Literówka w konfiguracji (-7) nie może przesunąć końca dostępu PRZED koniec
            // opłaconego okresu — studio traciłoby opłacone dni.
            assertEquals(Duration.ofDays(7), SubscriptionProperties().gracePeriod)
            assertEquals(Duration.ZERO, SubscriptionProperties(gracePeriodDays = 0).gracePeriod)
            assertEquals(Duration.ZERO, SubscriptionProperties(gracePeriodDays = -7).gracePeriod)

            val b = billing(ACTIVE, subscriptionEndsAt = now + days(1))
            assertEquals(now + days(1), policy(-7).accessEndsAt(b))
            assertTrue(policy(-7).isAccessible(b))
        }

        @Test
        fun `polityka bez podanej chwili pyta zegar`() {
            val endedNow = billing(ACTIVE, subscriptionEndsAt = now)
            assertEquals(now, policy(7).now())
            assertTrue(policy(7).isAccessible(endedNow))
            assertTrue(policy(7).isInGrace(endedNow))
            assertFalse(policy(0).isAccessible(endedNow))
            assertEquals(LifecycleTransition(PAST_DUE, now + grace), policy(7).dueTransition(endedNow))
            assertEquals(LifecycleTransition(EXPIRED, null), policy(0).dueTransition(endedNow))
            assertFalse(policy(7).hasRunningPaidPeriod(endedNow))
        }
    }
}

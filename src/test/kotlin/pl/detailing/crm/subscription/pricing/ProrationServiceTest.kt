package pl.detailing.crm.subscription.pricing

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.shared.SubscriptionStatus.ACTIVE
import pl.detailing.crm.shared.SubscriptionStatus.EXPIRED
import pl.detailing.crm.shared.SubscriptionStatus.NO_PLAN
import pl.detailing.crm.shared.SubscriptionStatus.PAST_DUE
import pl.detailing.crm.shared.SubscriptionStatus.TRIALING
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.subscription.lifecycle.SubscriptionAccessPolicy
import pl.detailing.crm.subscription.lifecycle.SubscriptionProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Proracja zakupów w trakcie okresu. Kwoty to grosze BRUTTO (ceny planów i modułów są
 * brutto) — nic tu nie przechodzi przez netto, więc jedynym zaokrągleniem jest dzielenie
 * przez 30 dni, wykonywane raz, na końcu (audyt, S9: zaokrąglona stawka dzienna dawała
 * 20010 gr zamiast 20000 za pełny okres).
 */
class ProrationServiceTest {

    private val now: Instant = Instant.parse("2026-10-01T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val studioRepository = mockk<StudioRepository>()
    private val accessPolicy = SubscriptionAccessPolicy(SubscriptionProperties(), clock)
    private val service = ProrationService(studioRepository, accessPolicy)
    private val studioId = StudioId.random()

    private fun days(n: Long): Duration = Duration.ofDays(n)

    private fun givenStudio(
        status: SubscriptionStatus,
        trialEndsAt: Instant? = null,
        subscriptionEndsAt: Instant? = null,
        graceEndsAt: Instant? = null
    ) {
        every { studioRepository.findByStudioId(studioId.value) } returns StudioEntity(
            id = studioId.value,
            name = "Studio testowe",
            subscriptionStatus = status,
            trialEndsAt = trialEndsAt,
            subscriptionEndsAt = subscriptionEndsAt,
            trialUsed = trialEndsAt != null,
            createdAt = now - days(60),
            graceEndsAt = graceEndsAt
        )
    }

    private fun givenNoStudio() {
        every { studioRepository.findByStudioId(studioId.value) } returns null
    }

    // ─── Tryb zakupu w trakcie okresu ─────────────────────────────────────────

    @Nested
    inner class TrybZakupu {

        @Test
        fun `trwajacy trial - zmiana bezplatna`() {
            givenStudio(TRIALING, trialEndsAt = now + days(5))
            assertEquals(MidPeriodPurchaseMode.TRIAL_FREE, service.midPeriodPurchaseMode(studioId))
        }

        @Test
        fun `trial, ktory wlasnie minal, a job go nie wygasil - zakup niedozwolony`() {
            // Bez tego studio z minionym trialem dostawałoby moduły za darmo do najbliższego
            // przebiegu joba.
            givenStudio(TRIALING, trialEndsAt = now)
            assertEquals(MidPeriodPurchaseMode.NOT_ALLOWED, service.midPeriodPurchaseMode(studioId))
        }

        @Test
        fun `trwajacy oplacony okres - proracja`() {
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(10))
            assertEquals(MidPeriodPurchaseMode.PRORATED, service.midPeriodPurchaseMode(studioId))
        }

        @Test
        fun `ACTIVE dokladnie w chwili konca okresu - zakup niedozwolony`() {
            givenStudio(ACTIVE, subscriptionEndsAt = now)
            assertEquals(MidPeriodPurchaseMode.NOT_ALLOWED, service.midPeriodPurchaseMode(studioId))
        }

        @Test
        fun `karencja - najpierw odnowienie`() {
            givenStudio(PAST_DUE, subscriptionEndsAt = now - days(2), graceEndsAt = now + days(5))
            assertEquals(MidPeriodPurchaseMode.NOT_ALLOWED, service.midPeriodPurchaseMode(studioId))
        }

        @Test
        fun `wygasla subskrypcja - zakup niedozwolony, dawniej upgrade do FULL za 0 zl (audyt S4)`() {
            givenStudio(EXPIRED, subscriptionEndsAt = now - days(20))
            assertEquals(MidPeriodPurchaseMode.NOT_ALLOWED, service.midPeriodPurchaseMode(studioId))
        }

        @Test
        fun `bez planu i bez studia - zakup niedozwolony`() {
            givenStudio(NO_PLAN)
            assertEquals(MidPeriodPurchaseMode.NOT_ALLOWED, service.midPeriodPurchaseMode(studioId))

            givenNoStudio()
            assertEquals(MidPeriodPurchaseMode.NOT_ALLOWED, service.midPeriodPurchaseMode(studioId))
        }
    }

    // ─── Koniec trwającego okresu i liczba dni ────────────────────────────────

    @Nested
    inner class OkresIDni {

        @Test
        fun `koniec okresu tylko przy trwajacym oplaconym okresie`() {
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(10))
            assertEquals(now + days(10), service.runningPeriodEnd(studioId))

            givenStudio(TRIALING, trialEndsAt = now + days(5), subscriptionEndsAt = now + days(40))
            assertNull(service.runningPeriodEnd(studioId))

            givenStudio(ACTIVE, subscriptionEndsAt = now - days(1))
            assertNull(service.runningPeriodEnd(studioId), "karencja przed przebiegiem joba")

            givenStudio(PAST_DUE, subscriptionEndsAt = now + days(3), graceEndsAt = now + days(10))
            assertNull(service.runningPeriodEnd(studioId), "PAST_DUE nigdy nie ma trwającego okresu")

            givenStudio(EXPIRED, subscriptionEndsAt = now + days(3))
            assertNull(service.runningPeriodEnd(studioId))

            givenNoStudio()
            assertNull(service.runningPeriodEnd(studioId))
            assertNull(service.daysRemainingInPeriod(studioId))
        }

        @Test
        fun `dni do opisu to dni rozpoczete, a kwota liczy sie co do sekundy`() {
            // Dawniej dni zaokrąglane w dół: zakup na 10 dni i 23 h kosztował jak na 10 dni,
            // choć moduł działał prawie 11 (przegląd planu naprawczego).
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(10) + Duration.ofHours(23))
            assertEquals(11L, service.daysRemainingInPeriod(studioId))
            // 3000 × (10 d 23 h / 30 d) = 1095,83 → 1096 (dawniej 3000 × 10/30 = 1000).
            assertEquals(1_096L, service.calculateAddOnActivation(studioId, 3_000)!!.proratedAmountCents)

            givenStudio(ACTIVE, subscriptionEndsAt = now + days(30))
            assertEquals(30L, service.daysRemainingInPeriod(studioId))

            givenStudio(ACTIVE, subscriptionEndsAt = now + days(30) - Duration.ofSeconds(1))
            assertEquals(30L, service.daysRemainingInPeriod(studioId))
        }

        @Test
        fun `ostatnia doba okresu liczy sie jako jeden dzien, nie zero`() {
            // Zero dni dawałoby zakup za 0 zł na kilka godzin przed końcem okresu.
            givenStudio(ACTIVE, subscriptionEndsAt = now + Duration.ofHours(1))
            assertEquals(1L, service.daysRemainingInPeriod(studioId))

            givenStudio(ACTIVE, subscriptionEndsAt = now + Duration.ofSeconds(1))
            assertEquals(1L, service.daysRemainingInPeriod(studioId))
        }
    }

    // ─── prorate() ────────────────────────────────────────────────────────────

    @Nested
    inner class Zaokraglenie {

        @Test
        fun `pelny okres to dokladnie roznica cen - bez groszy z zaokraglonej stawki dziennej (audyt S9)`() {
            // (29900 − 9900) / 30 = 666,67 gr dziennie. Dawniej 667 × 30 = 20010 gr.
            assertEquals(20000L, ProrationService.prorate(29900 - 9900, 30))
        }

        @Test
        fun `polowa grosza zaokraglana w gore (HALF_UP), nie do parzystej`() {
            // HALF_EVEN dałby 2 zamiast 3 przy 2,5 gr i 0 zamiast 1 przy 0,5 gr.
            assertEquals(1L, ProrationService.prorate(3, 5))   // 15/30 = 0,5
            assertEquals(2L, ProrationService.prorate(9, 5))   // 45/30 = 1,5
            assertEquals(3L, ProrationService.prorate(15, 5))  // 75/30 = 2,5
        }

        @Test
        fun `mniej niz pol grosza zaokraglane w dol`() {
            assertEquals(0L, ProrationService.prorate(1, 14))      // 14/30 = 0,47
            assertEquals(163L, ProrationService.prorate(4900, 1))  // 163,33
            assertEquals(0L, ProrationService.prorate(0, 30))
        }

        @Test
        fun `dokupienie modulu w polowie okresu kosztuje polowe ceny`() {
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(15))
            val result = service.calculateAddOnActivation(studioId, 4900)!!
            assertEquals(15L, result.daysRemaining)
            assertEquals(now + days(15), result.periodEndsAt)
            assertEquals(2450L, result.proratedAmountCents)
            assertEquals("PLN", result.currency)
        }

        @Test
        fun `dokupienie modulu bez trwajacego okresu - brak proracji`() {
            givenStudio(TRIALING, trialEndsAt = now + days(5))
            assertNull(service.calculateAddOnActivation(studioId, 4900))

            givenStudio(EXPIRED, subscriptionEndsAt = now - days(1))
            assertNull(service.calculateAddOnActivation(studioId, 4900))
        }
    }

    // ─── Upgrade planu ────────────────────────────────────────────────────────

    @Nested
    inner class UpgradePlanu {

        private val basic = 9900L
        private val full = 29900L

        @Test
        fun `upgrade bez modulow - roznica cen za pozostale dni`() {
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(15))
            val result = service.calculatePlanUpgrade(studioId, basic, full)!!
            assertEquals(15L, result.daysRemaining)
            assertEquals(10000L, result.proratedAmountCents)
        }

        @Test
        fun `zmiana na plan nie drozszy to nie upgrade - brak proracji`() {
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(15))
            assertNull(service.calculatePlanUpgrade(studioId, full, basic))
            assertNull(service.calculatePlanUpgrade(studioId, basic, basic))
        }

        @Test
        fun `upgrade bez trwajacego okresu - brak proracji`() {
            givenStudio(EXPIRED, subscriptionEndsAt = now - days(10))
            assertNull(service.calculatePlanUpgrade(studioId, basic, full))

            givenStudio(PAST_DUE, subscriptionEndsAt = now - days(1), graceEndsAt = now + days(6))
            assertNull(service.calculatePlanUpgrade(studioId, basic, full))
        }

        @Test
        fun `oplacony modul zawarty w planie docelowym jest zaliczany (audyt S7)`() {
            // Upgrade kasuje moduł, a plan docelowy go zawiera — dawniej klient płacił za
            // te same dni drugi raz.
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(15))
            val result = service.calculatePlanUpgrade(
                studioId, basic, full,
                paidAddOns = listOf(PaidAddOnCredit(monthlyPriceCents = 4900, cancelAt = null))
            )!!
            // (20000 − 4900) × 15 / 30
            assertEquals(7550L, result.proratedAmountCents)
        }

        @Test
        fun `modul z zaplanowanym wylaczeniem zaliczany tylko do dnia wylaczenia`() {
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(15))
            val result = service.calculatePlanUpgrade(
                studioId, basic, full,
                paidAddOns = listOf(PaidAddOnCredit(monthlyPriceCents = 4900, cancelAt = now + days(5)))
            )!!
            // (20000 × 15 − 4900 × 5) / 30 = 275500 / 30 = 9183,33
            assertEquals(9183L, result.proratedAmountCents)
        }

        @Test
        fun `wylaczenie po koncu okresu nie wydluza zaliczenia ponad okres`() {
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(15))
            val result = service.calculatePlanUpgrade(
                studioId, basic, full,
                paidAddOns = listOf(PaidAddOnCredit(monthlyPriceCents = 4900, cancelAt = now + days(40)))
            )!!
            assertEquals(7550L, result.proratedAmountCents)
        }

        @Test
        fun `modul juz wylaczony albo wylaczany teraz nie daje zaliczenia`() {
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(15))
            val past = service.calculatePlanUpgrade(
                studioId, basic, full,
                paidAddOns = listOf(PaidAddOnCredit(monthlyPriceCents = 4900, cancelAt = now - days(1)))
            )!!
            assertEquals(10000L, past.proratedAmountCents)

            val atNow = service.calculatePlanUpgrade(
                studioId, basic, full,
                paidAddOns = listOf(PaidAddOnCredit(monthlyPriceCents = 4900, cancelAt = now))
            )!!
            assertEquals(10000L, atNow.proratedAmountCents)
        }

        @Test
        fun `upgrade nigdy nie jest ujemny, gdy moduly kosztuja wiecej niz roznica planow`() {
            // BASIC + wszystkie moduły > FULL — zaliczenie nie może zamienić się w zwrot.
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(20))
            val result = service.calculatePlanUpgrade(
                studioId, 9900, 14900,
                paidAddOns = listOf(
                    PaidAddOnCredit(4900, null),
                    PaidAddOnCredit(2900, null),
                    PaidAddOnCredit(4900, null)
                )
            )!!
            assertEquals(0L, result.proratedAmountCents)
            assertEquals(20L, result.daysRemaining)
        }

        @Test
        fun `zaokraglenie raz, na koncu - nie osobno dla planu i dla modulu`() {
            // 1 dzień: plan 40/30 = 1,33 → 1, moduł 20/30 = 0,67 → 1. Osobne zaokrąglenia
            // dałyby 0 gr, jedno zaokrąglenie różnicy 20/30 = 0,67 → 1 gr.
            givenStudio(ACTIVE, subscriptionEndsAt = now + days(1))
            val result = service.calculatePlanUpgrade(
                studioId, 1000, 1040,
                paidAddOns = listOf(PaidAddOnCredit(20, null))
            )!!
            assertEquals(1L, result.daysRemaining)
            assertEquals(1L, result.proratedAmountCents)
        }

        @Test
        fun `upgrade w ostatniej dobie liczony za jeden dzien`() {
            givenStudio(ACTIVE, subscriptionEndsAt = now + Duration.ofHours(2))
            val result = service.calculatePlanUpgrade(studioId, basic, full)!!
            assertEquals(1L, result.daysRemaining)
            assertEquals(667L, result.proratedAmountCents) // 20000 / 30 = 666,67
        }
    }
}

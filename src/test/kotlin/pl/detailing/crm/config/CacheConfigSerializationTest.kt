package pl.detailing.crm.config

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.cache.Cache
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import pl.detailing.crm.shared.SubscriptionStatus
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.domain.AddOnKey
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import pl.detailing.crm.subscription.entitlement.domain.StudioEntitlements
import pl.detailing.crm.subscription.lifecycle.BillingSnapshot
import java.time.Instant

/**
 * Wartości cache'u przez PRAWDZIWY serializer Redisa. Testy integracyjne subskrypcji biegną
 * na cache'u w pamięci, który nic nie serializuje — dlatego `Instant` w stanie rozliczeniowym
 * bez JavaTimeModule przeszedł przez nie niezauważony, a na produkcji każdy zapis uprawnień
 * do Redisa rzucałby wyjątek.
 */
class CacheConfigSerializationTest {

    private val serializer = GenericJackson2JsonRedisSerializer(CacheConfig.redisObjectMapper())

    @Test
    fun `uprawnienia studia ze stanem rozliczen i datami wylaczen przechodza przez Redisa bez zmian`() {
        val entitlements = StudioEntitlements(
            planKey = PlanKey.BASIC,
            planName = "Podstawowy",
            enabledFeatures = linkedSetOf(FeatureKey.CALENDAR, FeatureKey.FINANCE),
            activeAddOnKeys = linkedSetOf(AddOnKey.FINANCE_MODULE),
            billing = BillingSnapshot(
                status = SubscriptionStatus.PAST_DUE,
                trialEndsAt = Instant.parse("2026-09-01T10:00:00Z"),
                subscriptionEndsAt = Instant.parse("2026-10-01T10:00:00.123456Z"),
                graceEndsAt = Instant.parse("2026-10-08T10:00:00.123456Z")
            ),
            addOnCancellations = linkedMapOf(AddOnKey.FINANCE_MODULE to Instant.parse("2026-10-01T10:00:00.123456Z"))
        )

        val restored = serializer.deserialize(serializer.serialize(entitlements))

        assertEquals(entitlements, restored)
    }

    @Test
    fun `brak stanu rozliczen tez przechodzi`() {
        val entitlements = StudioEntitlements(
            planKey = PlanKey.FULL, planName = "Pełny", enabledFeatures = linkedSetOf(FeatureKey.CALENDAR),
            activeAddOnKeys = linkedSetOf()
        )
        assertEquals(entitlements, serializer.deserialize(serializer.serialize(entitlements)))
    }

    @Test
    fun `blad odczytu i zapisu cache'u nie wywraca zadania, blad uniewaznienia tak`() {
        val handler = CacheConfig().errorHandler()
        val cache = mockk<Cache>(relaxed = true)
        val failure = IllegalStateException("redis down")

        assertDoesNotThrow { handler.handleCacheGetError(failure, cache, "k") }
        assertDoesNotThrow { handler.handleCachePutError(failure, cache, "k", "v") }
        val evict = assertThrows<IllegalStateException> { handler.handleCacheEvictError(failure, cache, "k") }
        assertTrue(evict === failure)
    }
}

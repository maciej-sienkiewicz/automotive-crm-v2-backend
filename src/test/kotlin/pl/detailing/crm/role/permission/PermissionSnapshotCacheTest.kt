package pl.detailing.crm.role.permission

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.cache.CacheManager
import org.springframework.cache.annotation.EnableCaching
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

/**
 * Runs [PermissionSnapshotCache.snapshot] through a real Spring caching proxy with an
 * in-memory cache manager, so the `@Cacheable` SpEL key expression is actually evaluated.
 *
 * Regression guard: UserId/StudioId are Kotlin value classes and are inlined to raw UUIDs
 * in bytecode — a key expression referencing their `.value` property blows up at runtime
 * with SpEL EL1008E even though the code compiles. A plain unit test with MockK never
 * evaluates the expression; this test does.
 */
class PermissionSnapshotCacheTest {

    private val userRepository = mockk<UserRepository>()
    private val roleRepository = mockk<RoleRepository>()

    private lateinit var context: AnnotationConfigApplicationContext

    @Configuration
    @EnableCaching
    open class CachingTestConfig {
        @Bean open fun cacheManager(): CacheManager = ConcurrentMapCacheManager()
    }

    @AfterEach
    fun closeContext() {
        if (::context.isInitialized) context.close()
    }

    private fun proxiedCache(): PermissionSnapshotCache {
        context = AnnotationConfigApplicationContext()
        context.register(CachingTestConfig::class.java)
        // Jawny Supplier: goła lambda dopasowuje się do przeciążenia z BeanDefinitionCustomizer
        // i Spring próbuje autowire'ować konstruktor zamiast użyć naszej fabryki.
        context.registerBean(
            PermissionSnapshotCache::class.java,
            java.util.function.Supplier {
                PermissionSnapshotCache(
                    userRepository,
                    roleRepository,
                    context.getBean(CacheManager::class.java),
                    mockk<StringRedisTemplate>(relaxed = true)
                )
            }
        )
        context.refresh()
        return context.getBean(PermissionSnapshotCache::class.java)
    }

    @Test
    fun `key expression evaluates and the second call is served from cache`() {
        val cache = proxiedCache()
        val userId = UUID.randomUUID()
        val studioId = UUID.randomUUID()
        every { userRepository.findByIdAndStudioId(userId, studioId) } returns null

        val first = cache.snapshot(userId, studioId)
        val second = cache.snapshot(userId, studioId)

        assertEquals(first, second)
        verify(exactly = 1) { userRepository.findByIdAndStudioId(userId, studioId) }
    }

    @Test
    fun `cache key separates users and studios`() {
        val cache = proxiedCache()
        val studioId = UUID.randomUUID()
        val userA = UUID.randomUUID()
        val userB = UUID.randomUUID()
        every { userRepository.findByIdAndStudioId(any(), any()) } returns null

        cache.snapshot(userA, studioId)
        cache.snapshot(userB, studioId)

        verify(exactly = 1) { userRepository.findByIdAndStudioId(userA, studioId) }
        verify(exactly = 1) { userRepository.findByIdAndStudioId(userB, studioId) }
    }
}

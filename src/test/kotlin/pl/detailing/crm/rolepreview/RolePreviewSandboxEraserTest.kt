package pl.detailing.crm.rolepreview

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.session.SessionRepository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.rolepreview.infrastructure.RolePreviewSandboxEntity
import pl.detailing.crm.studio.domain.StudioKind
import pl.detailing.crm.studio.reset.S3StudioPurger
import pl.detailing.crm.studio.reset.StudioDataPurger
import pl.detailing.crm.studio.reset.StudioResetContext
import pl.detailing.crm.studio.reset.StudioResetStep
import java.time.Instant
import java.util.UUID

/** Wykonuje callbacki synchronicznie — testujemy kolejność, nie transakcje. */
private class NoOpTransactionManager : PlatformTransactionManager {
    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
    override fun commit(status: TransactionStatus) = Unit
    override fun rollback(status: TransactionStatus) = Unit
}

/**
 * Usuwanie piaskownicy: najpierw dostęp, potem dane, na końcu studio - i nigdy studio,
 * które nie jest piaskownicą. Ten ostatni warunek siedzi zarówno w sprawdzeniu na wejściu,
 * jak i w samym zapytaniu DELETE, więc błąd w rejestrze nie skasuje prawdziwego klienta.
 */
class RolePreviewSandboxEraserTest {

    private val studios = mockk<RolePreviewStudios>(relaxed = true)
    private val purger = mockk<StudioDataPurger>()
    private val s3StudioPurger = mockk<S3StudioPurger>(relaxed = true)
    private val sessionRepository = mockk<SessionRepository<*>>(relaxed = true)
    private val redisTemplate = mockk<StringRedisTemplate>(relaxed = true)
    private val effects = mockk<RolePreviewEffects>(relaxed = true)
    private val entityManager = mockk<EntityManager>()
    private val queries = mutableListOf<String>()
    private val kindParameters = mutableListOf<Any?>()
    private val purgedContexts = mutableListOf<StudioResetContext>()

    private val eraser = RolePreviewSandboxEraser(
        studios = studios,
        purger = purger,
        s3StudioPurger = s3StudioPurger,
        transactionTemplate = TransactionTemplate(NoOpTransactionManager()),
        jdbcTemplate = mockk<JdbcTemplate>(relaxed = true),
        sessionRepository = sessionRepository,
        redisTemplate = redisTemplate,
        effects = effects,
        entityManager = entityManager
    )

    private val studioId = UUID.randomUUID()

    init {
        every { purger.steps() } returns listOf(
            StudioResetStep("Dane studia") { purgedContexts += it }
        )
        every { entityManager.createQuery(any<String>()) } answers {
            queries += firstArg<String>()
            val query = mockk<Query>()
            every { query.setParameter(any<String>(), any()) } answers {
                if (firstArg<String>() == "kind") kindParameters += secondArg<Any?>()
                query
            }
            every { query.executeUpdate() } returns 1
            query
        }
    }

    @Test
    fun `prawdziwego studia nie usuwa nigdy`() {
        listOf(StudioKind.REGULAR, StudioKind.DEMO).forEach { kind ->
            every { studios.kindOf(studioId) } returns kind

            assertThrows<IllegalStateException>(kind.name) { eraser.erase(sandbox()) }
        }

        verify(exactly = 0) { sessionRepository.deleteById(any()) }
        verify(exactly = 0) { purger.steps() }
        verify(exactly = 0) { s3StudioPurger.purge(any()) }
        assertTrue(queries.isEmpty())
    }

    @Test
    fun `najpierw odcina dostep, potem usuwa dane, pliki i na koncu studio`() {
        every { studios.kindOf(studioId) } returns StudioKind.ROLE_PREVIEW

        eraser.erase(sandbox(sessionId = "sesja-piaskownicy"))

        verifyOrder {
            sessionRepository.deleteById("sesja-piaskownicy")
            purger.steps()
            s3StudioPurger.purge(studioId)
            effects.clear(studioId)
            studios.forget(studioId)
        }
        // Reset konta nie oszczędza żadnego konta piaskownicy - także właściciela.
        assertEquals(StudioResetContext(studioId, UUID(0L, 0L), wipeCompanyData = true), purgedContexts.single())
    }

    @Test
    fun `wiersz studia usuwa zapytanie z warunkiem rodzaju ROLE_PREVIEW`() {
        every { studios.kindOf(studioId) } returns StudioKind.ROLE_PREVIEW

        eraser.erase(sandbox())

        val studioDelete = queries.single { it.startsWith("DELETE FROM StudioEntity") }
        assertTrue(studioDelete.contains("s.kind = :kind"), studioDelete)
        assertEquals(listOf<Any?>(StudioKind.ROLE_PREVIEW), kindParameters)
        assertTrue(queries.last().startsWith("DELETE FROM RolePreviewSandboxEntity"), queries.last())
    }

    @Test
    fun `przerwane wczesniej usuwanie mozna dokonczyc gdy studia juz nie ma`() {
        every { studios.kindOf(studioId) } returns null

        eraser.eraseOrphan(studioId)

        verify(exactly = 1) { s3StudioPurger.purge(studioId) }
        verify(exactly = 0) { sessionRepository.deleteById(any()) }
    }

    private fun sandbox(sessionId: String? = null): RolePreviewSandboxEntity {
        val now = Instant.now()
        return RolePreviewSandboxEntity(
            id = UUID.randomUUID(),
            sandboxStudioId = studioId,
            sourceStudioId = UUID.randomUUID(),
            createdByUserId = UUID.randomUUID(),
            createdByName = "Anna Właścicielka",
            ownerUserId = UUID.randomUUID(),
            employeeUserId = UUID.randomUUID(),
            roleId = UUID.randomUUID(),
            roleName = "Recepcja",
            initialPermissions = "",
            initialTrackWorkTime = false,
            entryCodeHash = "0".repeat(64),
            entryCodeExpiresAt = now,
            sessionId = sessionId,
            createdAt = now,
            lastActivityAt = now,
            expiresAt = now
        )
    }
}

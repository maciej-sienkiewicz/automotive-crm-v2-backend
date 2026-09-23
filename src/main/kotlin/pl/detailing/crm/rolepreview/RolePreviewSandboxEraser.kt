package pl.detailing.crm.rolepreview

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ScanOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.session.SessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.rolepreview.infrastructure.RolePreviewSandboxEntity
import pl.detailing.crm.studio.domain.StudioKind
import pl.detailing.crm.studio.reset.S3StudioPurger
import pl.detailing.crm.studio.reset.StudioDataPurger
import pl.detailing.crm.studio.reset.StudioResetContext
import java.util.UUID

/**
 * Usuwa piaskownicę podglądu roli bez śladu: dostęp, dane, poświadczenia, pliki i wpisy w Redisie.
 *
 * Kolejność ma znaczenie:
 * 1. Najpierw dostęp - sesja pracownika piaskownicy. Od tej chwili nikt już do piaskownicy
 *    nie wejdzie, nawet jeśli któryś z dalszych kroków zawiedzie.
 * 2. Dane studia krokami resetu konta ([StudioDataPurger]) - ten sam, sprawdzony w boju
 *    porządek pod klucze obce, bez wyjątków: nie zostaje żadne konto, żadna rola, żadne
 *    hasło CardDAV, urządzenie push, tablet, token Karty Wizyty ani webhook formularza.
 * 3. To, co reset konta celowo zostawia prawdziwemu studiu (plan, rozliczenia, dziennik
 *    zdarzeń, ustawienia) - piaskownica nie ma czego zachowywać.
 * 4. Pliki w S3 i wpisy w Redisie zawierające identyfikator piaskownicy.
 * 5. Na końcu wiersz studia i wpis w rejestrze. Jeśli coś wcześniej zawiedzie, oba zostają -
 *    zadanie sprzątające spróbuje ponownie, a dostęp i tak jest już odcięty.
 *
 * Twarda zasada: usuwamy wyłącznie studio rodzaju ROLE_PREVIEW. Żaden błąd w rejestrze nie
 * może skończyć się usunięciem prawdziwego studia.
 */
@Service
class RolePreviewSandboxEraser(
    private val studios: RolePreviewStudios,
    private val purger: StudioDataPurger,
    private val s3StudioPurger: S3StudioPurger,
    private val transactionTemplate: TransactionTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val sessionRepository: SessionRepository<*>,
    private val redisTemplate: StringRedisTemplate,
    private val effects: RolePreviewEffects,
    @PersistenceContext private val entityManager: EntityManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun erase(sandbox: RolePreviewSandboxEntity) =
        erase(studioId = sandbox.sandboxStudioId, sessionId = sandbox.sessionId)

    /**
     * Piaskownica bez wpisu w rejestrze (np. po awarii w połowie usuwania): zostało samo
     * studio rodzaju ROLE_PREVIEW. Sesji nie znamy, ale ta i tak przestaje działać, gdy
     * znika studio - filtr uznaje sesję nieistniejącego studia za martwą.
     */
    fun eraseOrphan(studioId: UUID) = erase(studioId = studioId, sessionId = null)

    private fun erase(studioId: UUID, sessionId: String?) {
        // Wiersz studia mógł już zniknąć przy poprzedniej, przerwanej próbie - wtedy
        // dokańczamy sprzątanie. Każdy inny rodzaj niż ROLE_PREVIEW to błąd, nie piaskownica.
        val kind = studios.kindOf(studioId)
        check(kind == null || kind == StudioKind.ROLE_PREVIEW) {
            "Odmowa usunięcia: studio $studioId nie jest piaskownicą podglądu roli (rodzaj: $kind)"
        }

        // 1. Dostęp.
        sessionId?.let { id ->
            runCatching { sessionRepository.deleteById(id) }
                .onFailure { logger.warn("Piaskownica {}: nie udało się usunąć sesji: {}", studioId, it.message) }
        }

        // 2. Dane studia - wszystkie konta, bez wyjątku dla właściciela.
        val context = StudioResetContext(studioId = studioId, keepUserId = NOBODY, wipeCompanyData = true)
        purger.steps().forEach { step ->
            transactionTemplate.executeWithoutResult { step.execute(context) }
        }

        // 3. Tabele, które reset konta zostawia prawdziwemu studiu.
        transactionTemplate.executeWithoutResult {
            // Moduły dodatkowe przed planem: to encje-dzieci, nie kolekcja elementów planu.
            entityManager.createQuery(
                """DELETE FROM StudioAddOnEntity a WHERE a.studioSubscriptionPlan.id IN
                   (SELECT p.id FROM StudioSubscriptionPlanEntity p WHERE p.studioId = :studioId)"""
            ).setParameter("studioId", studioId).executeUpdate()
            TENANT_ENTITIES.forEach { entity ->
                entityManager.createQuery("DELETE FROM $entity e WHERE e.studioId = :studioId")
                    .setParameter("studioId", studioId)
                    .executeUpdate()
            }
        }
        deleteInstagramVectors(studioId)

        // 4. Pliki i Redis.
        s3StudioPurger.purge(studioId)
        deleteRedisKeysMentioning(studioId)
        effects.clear(studioId)

        // 5. Studio i rejestr - tylko rodzaju ROLE_PREVIEW, warunek w samym zapytaniu.
        transactionTemplate.executeWithoutResult {
            entityManager.createQuery("DELETE FROM StudioEntity s WHERE s.id = :studioId AND s.kind = :kind")
                .setParameter("studioId", studioId)
                .setParameter("kind", StudioKind.ROLE_PREVIEW)
                .executeUpdate()
            entityManager.createQuery("DELETE FROM RolePreviewSandboxEntity s WHERE s.sandboxStudioId = :studioId")
                .setParameter("studioId", studioId)
                .executeUpdate()
        }
        studios.forget(studioId)
        logger.info("Usunięto piaskownicę podglądu roli {}", studioId)
    }

    /**
     * Wektory postów Instagrama (pgvector) trzymają studio w metadanych, nie w kolumnie. Tabelę
     * zakłada Spring AI przy starcie - tam, gdzie jej nie ma, nie ma też czego usuwać.
     */
    private fun deleteInstagramVectors(studioId: UUID) {
        runCatching {
            val exists = jdbcTemplate.queryForObject(
                "SELECT to_regclass('instagram_post_vectors') IS NOT NULL", Boolean::class.java
            ) == true
            if (exists) {
                jdbcTemplate.update(
                    "DELETE FROM instagram_post_vectors WHERE metadata->>'studio_id' = ?",
                    studioId.toString()
                )
            }
        }.onFailure { logger.warn("Piaskownica {}: nie udało się usunąć wektorów Instagrama: {}", studioId, it.message) }
    }

    /**
     * Wpisy w Redisie z identyfikatorem piaskownicy w kluczu: cache uprawnień, modułów i tagów,
     * konteksty wgrywania zdjęć z telefonu, liczniki prób PIN, limity. Identyfikator jest
     * losowym UUID, więc nie trafi przypadkiem w klucz innego studia. SCAN, nie KEYS: nie
     * blokuje Redisa na czas przeglądania.
     */
    private fun deleteRedisKeysMentioning(studioId: UUID) {
        runCatching {
            val keys = mutableListOf<String>()
            redisTemplate.scan(ScanOptions.scanOptions().match("*$studioId*").count(1000).build()).use { cursor ->
                cursor.forEachRemaining { keys += it }
            }
            if (keys.isNotEmpty()) redisTemplate.delete(keys)
        }.onFailure { logger.warn("Piaskownica {}: nie udało się usunąć wpisów w Redisie: {}", studioId, it.message) }
    }

    private companion object {
        /** Użytkownik, którego nie ma - reset konta nie oszczędzi żadnego konta piaskownicy. */
        val NOBODY: UUID = UUID(0L, 0L)

        /** Tabele, które reset konta świadomie zostawia prawdziwemu studiu (patrz [StudioDataPurger]). */
        val TENANT_ENTITIES = listOf(
            "StudioSubscriptionPlanEntity",
            "PendingPlanChangeEntity",
            "SubscriptionPaymentLogEntity",
            "PaymentOrderEntity",
            "SmsCreditBalanceEntity",
            "SmsCreditTransactionEntity",
            "StudioSettingsEntity",
            "AuditLogEntity",
            "StudioResetJobEntity"
        )
    }
}

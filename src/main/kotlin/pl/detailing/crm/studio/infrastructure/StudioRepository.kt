package pl.detailing.crm.studio.infrastructure

import pl.detailing.crm.studio.domain.StudioKind
import jakarta.persistence.LockModeType
import jakarta.persistence.QueryHint
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.QueryHints
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface StudioRepository : JpaRepository<StudioEntity, UUID> {

    @Query("SELECT s FROM StudioEntity s WHERE s.id = :id")
    fun findByStudioId(@Param("id") id: UUID): StudioEntity?

    /** Sam rodzaj studia - bez ładowania encji; null, gdy studia nie ma. */
    @Query("SELECT s.kind FROM StudioEntity s WHERE s.id = :id")
    fun findKindById(@Param("id") id: UUID): StudioKind?

    @Query("SELECT s.id FROM StudioEntity s WHERE s.kind = :kind AND s.createdAt < :createdBefore")
    fun findIdsByKindCreatedBefore(
        @Param("kind") kind: StudioKind,
        @Param("createdBefore") createdBefore: Instant
    ): List<UUID>

    @Query("SELECT s FROM StudioEntity s WHERE s.name = :name")
    fun findByName(@Param("name") name: String): StudioEntity?

    @Query("SELECT s FROM StudioEntity s WHERE s.emailAlias = :emailAlias")
    fun findByEmailAlias(@Param("emailAlias") emailAlias: String): StudioEntity?

    /**
     * Wiersz studia z blokadą (`SELECT … FOR UPDATE`). Każda mutacja subskrypcji zaczyna od
     * niej — zakupy, zmiany planu, przejścia cyklu życia i realizacje zamówień jednego studia
     * idą po kolei, a decyzja zapada na stanie odczytanym POD blokadą (audyt, inwariant 2).
     * Kolejność blokad w całym module: studio → plan → moduły → zamówienie.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StudioEntity s WHERE s.id = :id")
    fun lockById(@Param("id") id: UUID): StudioEntity?

    /**
     * Jak [lockById], ale z `SKIP LOCKED`: zajęty wiersz zwraca null zamiast czekać. Dla
     * jobów — druga instancja (albo zakup w toku) pomija studio, kolejny przebieg je dokończy.
     * Wartość -2 to w Hibernate [org.hibernate.LockOptions.SKIP_LOCKED].
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT s FROM StudioEntity s WHERE s.id = :id")
    fun tryLockById(@Param("id") id: UUID): StudioEntity?

    /**
     * Studia z należnym przejściem cyklu życia (trial minął, okres minął, karencja minęła).
     * Same ID, porcjami: job decyduje dopiero pod blokadą wiersza, a granica czasu (`<=`)
     * jest ta sama, co w [pl.detailing.crm.subscription.lifecycle.SubscriptionLifecycle].
     *
     * PAST_DUE bez `grace_ends_at` (wiersz sprzed V172) kończy karencję razem z
     * `subscription_ends_at + karencja` — [graceCutoff] to `now − karencja`. Bez tego warunku
     * takie studio wracałoby w każdej porcji przez cały okres karencji i przy pełnych porcjach
     * blokowało przejścia pozostałych.
     */
    @Query("""
        SELECT s.id FROM StudioEntity s
        WHERE (s.subscriptionStatus = 'TRIALING' AND s.trialEndsAt <= :now)
           OR (s.subscriptionStatus = 'ACTIVE' AND s.subscriptionEndsAt <= :now)
           OR (s.subscriptionStatus = 'PAST_DUE' AND s.graceEndsAt <= :now)
           OR (s.subscriptionStatus = 'PAST_DUE' AND s.graceEndsAt IS NULL
               AND (s.subscriptionEndsAt IS NULL OR s.subscriptionEndsAt <= :graceCutoff))
        ORDER BY s.id
    """)
    fun findIdsDueForLifecycleTransition(
        @Param("now") now: Instant,
        @Param("graceCutoff") graceCutoff: Instant,
        pageable: Pageable
    ): List<UUID>
}

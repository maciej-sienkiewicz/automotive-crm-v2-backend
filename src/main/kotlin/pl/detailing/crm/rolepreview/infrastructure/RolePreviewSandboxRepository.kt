package pl.detailing.crm.rolepreview.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Repository
interface RolePreviewSandboxRepository : JpaRepository<RolePreviewSandboxEntity, UUID> {

    fun findBySandboxStudioId(sandboxStudioId: UUID): RolePreviewSandboxEntity?

    fun findByEntryCodeHash(entryCodeHash: String): RolePreviewSandboxEntity?

    /**
     * Wymienia kod wejścia na wejście - dokładnie raz. Warunek w samym UPDATE-cie, więc
     * dwa równoczesne wejścia tym samym kodem nie mogą oba wygrać: drugie dostaje 0.
     */
    @Modifying
    @Transactional
    @Query(
        """
        UPDATE RolePreviewSandboxEntity s
        SET s.enteredAt = :now, s.lastActivityAt = :now
        WHERE s.entryCodeHash = :codeHash
          AND s.enteredAt IS NULL
          AND s.entryCodeExpiresAt > :now
          AND s.expiresAt > :now
        """
    )
    fun consumeEntryCode(@Param("codeHash") codeHash: String, @Param("now") now: Instant): Int

    @Modifying
    @Transactional
    @Query("UPDATE RolePreviewSandboxEntity s SET s.sessionId = :sessionId WHERE s.id = :id")
    fun bindSession(@Param("id") id: UUID, @Param("sessionId") sessionId: String): Int

    @Modifying
    @Transactional
    @Query("UPDATE RolePreviewSandboxEntity s SET s.lastActivityAt = :now WHERE s.id = :id AND s.lastActivityAt < :now")
    fun touch(@Param("id") id: UUID, @Param("now") now: Instant): Int

    /** Piaskownice aktywne (niewygasłe) otwarte z danego prawdziwego studia - do limitu. */
    @Query(
        """
        SELECT COUNT(s) FROM RolePreviewSandboxEntity s
        WHERE s.sourceStudioId = :sourceStudioId
          AND s.expiresAt > :now
          AND s.lastActivityAt > :idleCutoff
        """
    )
    fun countActiveBySourceStudio(
        @Param("sourceStudioId") sourceStudioId: UUID,
        @Param("now") now: Instant,
        @Param("idleCutoff") idleCutoff: Instant
    ): Long

    /**
     * Piaskownice do usunięcia: po czasie życia, po bezczynności, albo takie, do których
     * nikt nie wszedł, choć kod wejścia już wygasł.
     */
    @Query(
        """
        SELECT s FROM RolePreviewSandboxEntity s
        WHERE s.expiresAt <= :now
           OR s.lastActivityAt <= :idleCutoff
           OR (s.enteredAt IS NULL AND s.entryCodeExpiresAt <= :now)
        """
    )
    fun findEnded(@Param("now") now: Instant, @Param("idleCutoff") idleCutoff: Instant): List<RolePreviewSandboxEntity>

    @Modifying
    @Transactional
    @Query("DELETE FROM RolePreviewSandboxEntity s WHERE s.id = :id")
    fun deleteByIdQuery(@Param("id") id: UUID): Int
}

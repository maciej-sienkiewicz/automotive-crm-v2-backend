package pl.detailing.crm.employee.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EmployeeRepository : JpaRepository<EmployeeEntity, UUID> {

    @Query("SELECT e FROM EmployeeEntity e WHERE e.id = :id AND e.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): EmployeeEntity?

    @Query("SELECT e FROM EmployeeEntity e WHERE e.studioId = :studioId ORDER BY e.lastName, e.firstName")
    fun findByStudioId(@Param("studioId") studioId: UUID): List<EmployeeEntity>

    @Query("""
        SELECT COUNT(e) > 0 FROM EmployeeEntity e
        WHERE e.studioId = :studioId AND e.email = :email
    """)
    fun existsByStudioIdAndEmail(
        @Param("studioId") studioId: UUID,
        @Param("email") email: String
    ): Boolean

    @Query("""
        SELECT COUNT(e) > 0 FROM EmployeeEntity e
        WHERE e.studioId = :studioId AND e.userId = :userId
    """)
    fun existsByStudioIdAndUserId(
        @Param("studioId") studioId: UUID,
        @Param("userId") userId: UUID
    ): Boolean

    @Query("SELECT e FROM EmployeeEntity e WHERE e.studioId = :studioId AND e.userId = :userId")
    fun findByStudioIdAndUserId(
        @Param("studioId") studioId: UUID,
        @Param("userId") userId: UUID
    ): EmployeeEntity?

    /**
     * Blokuje wiersz pracownika do końca bieżącej transakcji (SELECT … FOR UPDATE).
     *
     * Wnioski urlopowe jednej osoby nie mogą się nakładać, a sprawdzenie „czy termin wolny"
     * i zapis to dwa kroki. Bez blokady dwa równoległe złożenia (albo złożenie i decyzja)
     * na ten sam termin oba widziałyby wolne. EXCLUDE USING gist odpada: wymagałby
     * btree_gist i warunku na wartościach enuma (NoEnumCheckConstraintsTest).
     */
    @Query(
        value = "SELECT id FROM employees WHERE id = :id AND studio_id = :studioId FOR UPDATE",
        nativeQuery = true
    )
    fun lockForUpdate(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): UUID?
}

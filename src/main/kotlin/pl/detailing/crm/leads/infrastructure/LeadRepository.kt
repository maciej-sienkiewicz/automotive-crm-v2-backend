package pl.detailing.crm.leads.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.LeadStatus
import java.time.Instant
import java.util.UUID

@Repository
interface LeadRepository : JpaRepository<LeadEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): LeadEntity?

    fun findByAppointmentId(appointmentId: UUID): LeadEntity?

    /**
     * Anonimizacja leadów usuwanego klienta (RODO). Zapytaniem, nie iteracją po
     * encjach: [LeadEntity.contactIdentifier] jest celowo niemutowalne (val) w
     * zwykłym cyklu życia leada, a wymazanie danych osobowych to jedyny wyjątek.
     * Wiersze zostają — statystyki (skuteczność, wartości, trendy) liczą się z
     * kwot i statusów, nie z nazwisk.
     */
    @Modifying
    @Query(
        """
        UPDATE LeadEntity l
        SET l.contactIdentifier = :marker, l.customerName = null, l.initialMessage = null
        WHERE l.studioId = :studioId AND l.customerId = :customerId
        """
    )
    fun anonymizeByCustomer(
        @Param("studioId") studioId: UUID,
        @Param("customerId") customerId: UUID,
        @Param("marker") marker: String
    ): Int

    fun findByThreadId(threadId: UUID): LeadEntity?

    /**
     * [awaitingReply] zawęża listę do leadów, w których ostatnie słowo należy do klienta
     * — czyli do tych, gdzie zalegamy z odpowiedzią. Warunek liczy się z wiadomości,
     * a nie z pola na leadzie: pole trzeba by utrzymywać przy każdym mailu w obie strony,
     * a przeoczony haczyk zostawiłby lead z informacją wyglądającą na aktualną.
     *
     * Warunek brzmi „jest wiadomość od klienta i nie ma po niej naszej" — a nie
     * „ostatnia przychodząca jest nowsza od wychodzącej". Obie wersje znaczą to samo,
     * ale ta nie potrzebuje sztucznej daty granicznej dla wątku, w którym jeszcze nic
     * nie odpisaliśmy, a takich jest najwięcej wśród tych naprawdę zaległych.
     */
    @Query(
        """SELECT l FROM LeadEntity l
           WHERE l.studioId = :studioId
             AND (:status IS NULL OR l.status = :status)
             AND (:query IS NULL
                  OR LOWER(l.contactIdentifier) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%')
                  OR LOWER(COALESCE(l.customerName, '')) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%'))
             AND (:awaitingReply = FALSE
                  OR (l.threadId IS NOT NULL
                      AND EXISTS (SELECT 1 FROM CommMessageEntity mi
                                  WHERE mi.threadId = l.threadId
                                    AND mi.direction = pl.detailing.crm.comms.domain.CommDirection.INBOUND)
                      AND NOT EXISTS (SELECT 1 FROM CommMessageEntity mo
                                      WHERE mo.threadId = l.threadId
                                        AND mo.direction = pl.detailing.crm.comms.domain.CommDirection.OUTBOUND
                                        AND mo.sentAt > (SELECT MAX(mi2.sentAt) FROM CommMessageEntity mi2
                                                         WHERE mi2.threadId = l.threadId
                                                           AND mi2.direction = pl.detailing.crm.comms.domain.CommDirection.INBOUND))))
           ORDER BY l.createdAt DESC"""
    )
    fun search(
        @Param("studioId") studioId: UUID,
        @Param("status") status: LeadStatus?,
        @Param("query") query: String?,
        @Param("awaitingReply") awaitingReply: Boolean,
        pageable: Pageable
    ): Page<LeadEntity>

    fun findByStudioIdAndContactIdentifierOrderByCreatedAtDesc(
        studioId: UUID,
        contactIdentifier: String
    ): List<LeadEntity>

    /**
     * Wszystkie otwarte leady studia, bez względu na to, kiedy wpłynęły.
     *
     * Zaległa odpowiedź nie przestaje być zaległa dlatego, że użytkownik przełączył
     * widok na „ostatnie 30 dni". Rozmowa sprzed czterdziestu dni, w której klient
     * wciąż czeka, jest dokładnie tą, na którą trzeba odpisać najpilniej — filtrowanie
     * jej po oknie raportu ukryłoby największy dług.
     */
    fun findByStudioIdAndStatusIn(studioId: UUID, statuses: Collection<LeadStatus>): List<LeadEntity>

    fun findByStudioIdAndCreatedAtBetween(
        studioId: UUID,
        from: Instant,
        to: Instant
    ): List<LeadEntity>

    fun countByStudioIdAndStatus(studioId: UUID, status: LeadStatus): Long
}

@Repository
interface LeadServiceItemRepository : JpaRepository<LeadServiceItemEntity, UUID> {

    fun findByLeadIdOrderByCreatedAtAsc(leadId: UUID): List<LeadServiceItemEntity>

    fun findByLeadIdIn(leadIds: Collection<UUID>): List<LeadServiceItemEntity>

    @Modifying
    @Query("DELETE FROM LeadServiceItemEntity i WHERE i.leadId = :leadId")
    fun deleteByLeadId(@Param("leadId") leadId: UUID)
}

@Repository
interface LeadStatusHistoryRepository : JpaRepository<LeadStatusHistoryEntity, UUID> {

    /** Historia całej paczki leadów jednym zapytaniem — analityka liczy ją dla setek naraz. */
    fun findByLeadIdIn(leadIds: Collection<UUID>): List<LeadStatusHistoryEntity>

    fun findByLeadIdOrderByCreatedAtAsc(leadId: UUID): List<LeadStatusHistoryEntity>

    fun findByStudioIdAndCreatedAtBetween(
        studioId: UUID,
        from: Instant,
        to: Instant
    ): List<LeadStatusHistoryEntity>
}

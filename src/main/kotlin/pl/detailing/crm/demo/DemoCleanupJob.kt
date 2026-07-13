package pl.detailing.crm.demo

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.customer.notes.CustomerNoteRepository
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramStorySnapshotRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

@Component
class DemoCleanupJob(
    private val demoAccountRepository: DemoAccountRepository,
    private val visitRepository: VisitRepository,
    private val appointmentRepository: AppointmentRepository,
    private val appointmentColorRepository: AppointmentColorRepository,
    private val serviceRepository: ServiceRepository,
    private val vehicleRepository: VehicleRepository,
    private val customerNoteRepository: CustomerNoteRepository,
    private val customerRepository: CustomerRepository,
    private val studioInstagramProfileRepository: StudioInstagramProfileRepository,
    private val instagramProfileRepository: InstagramProfileRepository,
    private val instagramPostSnapshotRepository: InstagramPostSnapshotRepository,
    private val instagramStorySnapshotRepository: InstagramStorySnapshotRepository,
    private val instagramProfileMetricsSnapshotRepository: InstagramProfileMetricsSnapshotRepository,
    private val userRepository: UserRepository,
    private val studioRepository: StudioRepository,
    private val transactionTemplate: TransactionTemplate,
    @PersistenceContext private val entityManager: EntityManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRate = 900_000)
    fun cleanupExpiredDemoAccounts() {
        val now = Instant.now()
        val expired = demoAccountRepository.findExpired(now)

        if (expired.isEmpty()) return

        logger.info("Cleaning up {} expired demo accounts", expired.size)

        // Each account is cleaned in its own transaction so a failure on one account
        // rolls back only that account and does not poison the cleanup of the others.
        expired.forEach { demo ->
            try {
                transactionTemplate.executeWithoutResult {
                    cleanupSingleDemoAccount(demo)
                }
            } catch (e: Exception) {
                logger.error("Failed to clean up demo account studioId={}: {}", demo.studioId, e.message, e)
            }
        }
    }

    private fun cleanupSingleDemoAccount(demo: DemoAccountEntity) {
        val studioId = demo.studioId
        logger.info("Cleaning demo account: studioId={}", studioId)

        // 1. Delete visit comment revisions (FK: comment_id -> visit_comments.id)
        entityManager.createQuery(
            """DELETE FROM VisitCommentRevisionEntity r
               WHERE r.commentId IN (
                   SELECT c.id FROM VisitCommentEntity c
                   WHERE c.visitId IN (SELECT v.id FROM VisitEntity v WHERE v.studioId = :studioId)
               )"""
        ).setParameter("studioId", studioId).executeUpdate()

        // 2. Delete visit comments (FK: visit_id -> visits.id)
        entityManager.createQuery(
            """DELETE FROM VisitCommentEntity c
               WHERE c.visitId IN (SELECT v.id FROM VisitEntity v WHERE v.studioId = :studioId)"""
        ).setParameter("studioId", studioId).executeUpdate()

        // 2a. Delete visit documents (FK: visit_id -> visits.id, NOT cascaded from VisitEntity).
        // Check-in registers damage maps, generated protocols and signed PDFs here — without
        // this step deleting visits violates the visit_documents FK constraint.
        entityManager.createQuery(
            """DELETE FROM VisitDocumentEntity d
               WHERE d.visit.id IN (SELECT v.id FROM VisitEntity v WHERE v.studioId = :studioId)"""
        ).setParameter("studioId", studioId).executeUpdate()

        // 2a'. Delete visit journal entries (FK: visit_id -> visits.id, NOT cascaded)
        entityManager.createQuery(
            """DELETE FROM VisitJournalEntryEntity j
               WHERE j.visit.id IN (SELECT v.id FROM VisitEntity v WHERE v.studioId = :studioId)"""
        ).setParameter("studioId", studioId).executeUpdate()

        // 2b. Delete signature audit events and signature requests (tablet signing sessions)
        entityManager.createQuery(
            "DELETE FROM SignatureAuditEventEntity e WHERE e.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()
        entityManager.createQuery(
            "DELETE FROM SignatureRequestEntity r WHERE r.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()

        // 2c. Delete visit protocols (intake/pickup documents generated during check-in)
        entityManager.createQuery(
            "DELETE FROM VisitProtocolEntity p WHERE p.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()
        entityManager.flush()

        // 3. Delete visits (cascades service items + photos via CascadeType.ALL)
        val visits = visitRepository.findByStudioId(studioId)
        visitRepository.deleteAll(visits)
        entityManager.flush()

        // 4. Delete appointments (cascades line items via CascadeType.ALL)
        val appointments = appointmentRepository.findByStudioId(studioId)
        appointmentRepository.deleteAll(appointments)
        entityManager.flush()

        // 5. Delete appointment colors
        val colors = appointmentColorRepository.findByStudioId(studioId)
        appointmentColorRepository.deleteAll(colors)

        // 6. Delete category service assignments (FK: service_id -> services.id)
        entityManager.createQuery(
            "DELETE FROM CategoryServiceAssignmentEntity a WHERE a.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()

        // 7. Delete service categories
        entityManager.createQuery(
            "DELETE FROM ServiceCategoryEntity c WHERE c.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()
        entityManager.flush()

        // 8. Delete services
        val services = serviceRepository.findByStudioId(studioId)
        serviceRepository.deleteAll(services)

        // 9. Delete vehicle notes (FK: vehicle_id -> vehicles.id)
        entityManager.createQuery(
            "DELETE FROM VehicleNoteEntity n WHERE n.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()

        // 10. Delete vehicle owners (junction table – no FK cascade from vehicle)
        val customers = customerRepository.findByStudioId(studioId)
        val vehicles = vehicleRepository.findByStudioId(studioId)

        vehicles.forEach { vehicle ->
            entityManager.createQuery(
                "DELETE FROM VehicleOwnerEntity vo WHERE vo.id.vehicleId = :vehicleId"
            ).setParameter("vehicleId", vehicle.id).executeUpdate()
        }
        entityManager.flush()

        // 11. Delete vehicles (cascades vehicle_photos via CascadeType.ALL)
        vehicleRepository.deleteAll(vehicles)
        entityManager.flush()

        // 12. Delete customer notes
        customers.forEach { customer ->
            val notes = customerNoteRepository.findByCustomerIdAndStudioIdOrderByCreatedAtDesc(customer.id, studioId)
            customerNoteRepository.deleteAll(notes)
        }

        // 13. Delete customer documents
        entityManager.createQuery(
            "DELETE FROM CustomerDocumentEntity cd WHERE cd.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()
        entityManager.flush()

        // 14. Delete customers
        customerRepository.deleteAll(customers)
        entityManager.flush()

        // 15. Delete communication logs
        entityManager.createQuery(
            "DELETE FROM CommunicationLogEntity c WHERE c.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()
        entityManager.flush()

        // 16. Delete lead estimation items (cascade doesn't fire on JPQL DELETE)
        entityManager.createQuery(
            """DELETE FROM LeadEstimationItemEntity i
               WHERE i.estimation.id IN (
                   SELECT e.id FROM LeadEstimationEntity e WHERE e.studioId = :studioId
               )"""
        ).setParameter("studioId", studioId).executeUpdate()

        // 17. Delete lead estimations
        entityManager.createQuery(
            "DELETE FROM LeadEstimationEntity e WHERE e.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()

        // 18. Delete lead user quote items
        entityManager.createQuery(
            """DELETE FROM LeadUserQuoteItemEntity i
               WHERE i.quote.id IN (
                   SELECT q.id FROM LeadUserQuoteEntity q WHERE q.studioId = :studioId
               )"""
        ).setParameter("studioId", studioId).executeUpdate()

        // 19. Delete lead user quotes
        entityManager.createQuery(
            "DELETE FROM LeadUserQuoteEntity q WHERE q.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()

        // 20. Delete leads
        entityManager.createQuery(
            "DELETE FROM LeadEntity l WHERE l.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()
        entityManager.flush()

        // 21. Delete studio instagram profile links
        val studioProfiles = studioInstagramProfileRepository.findByStudioId(studioId)
        studioInstagramProfileRepository.deleteAll(studioProfiles)
        entityManager.flush()

        // 22. Remove orphaned global instagram profiles and their snapshots
        studioProfiles.forEach { sp ->
            val remainingRefs = studioInstagramProfileRepository.countByProfileIdAndStatus(
                sp.profileId, InstagramProfileStatus.ACTIVE
            )
            if (remainingRefs == 0L) {
                instagramPostSnapshotRepository.deleteByProfileId(sp.profileId)
                instagramStorySnapshotRepository.deleteByProfileId(sp.profileId)
                instagramProfileMetricsSnapshotRepository.deleteByProfileId(sp.profileId)
                instagramProfileRepository.deleteById(sp.profileId)
            }
        }
        entityManager.flush()

        // 23. Delete user
        userRepository.deleteById(demo.userId)
        entityManager.flush()

        // 24. Delete studio
        studioRepository.deleteById(studioId)
        entityManager.flush()

        // 25. Delete demo account record
        demoAccountRepository.delete(demo)

        logger.info("Demo account cleanup complete: studioId={}", studioId)
    }
}

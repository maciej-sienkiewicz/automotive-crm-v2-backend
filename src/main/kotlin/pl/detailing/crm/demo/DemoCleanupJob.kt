package pl.detailing.crm.demo

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
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
    @PersistenceContext private val entityManager: EntityManager
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedRate = 900_000)
    @Transactional
    fun cleanupExpiredDemoAccounts() {
        val now = Instant.now()
        val expired = demoAccountRepository.findExpired(now)

        if (expired.isEmpty()) return

        logger.info("Cleaning up {} expired demo accounts", expired.size)

        expired.forEach { demo ->
            try {
                cleanupSingleDemoAccount(demo)
            } catch (e: Exception) {
                logger.error("Failed to clean up demo account studioId={}: {}", demo.studioId, e.message, e)
            }
        }
    }

    private fun cleanupSingleDemoAccount(demo: DemoAccountEntity) {
        val studioId = demo.studioId
        logger.info("Cleaning demo account: studioId={}", studioId)

        // 1. Delete visits (cascades service items + photos via CascadeType.ALL)
        val visits = visitRepository.findByStudioId(studioId)
        visitRepository.deleteAll(visits)
        entityManager.flush()

        // 2. Delete appointments (cascades line items via CascadeType.ALL)
        val appointments = appointmentRepository.findByStudioId(studioId)
        appointmentRepository.deleteAll(appointments)
        entityManager.flush()

        // 3. Delete appointment colors
        val colors = appointmentColorRepository.findByStudioId(studioId)
        appointmentColorRepository.deleteAll(colors)

        // 4. Delete services
        val services = serviceRepository.findByStudioId(studioId)
        serviceRepository.deleteAll(services)

        // 5. Delete vehicle owners (junction table – no FK cascade from vehicle)
        val customers = customerRepository.findByStudioId(studioId)
        val vehicles = vehicleRepository.findByStudioId(studioId)

        vehicles.forEach { vehicle ->
            entityManager.createQuery(
                "DELETE FROM VehicleOwnerEntity vo WHERE vo.id.vehicleId = :vehicleId"
            ).setParameter("vehicleId", vehicle.id).executeUpdate()
        }
        entityManager.flush()

        // 6. Delete vehicles (cascades vehicle_photos via CascadeType.ALL)
        vehicleRepository.deleteAll(vehicles)
        entityManager.flush()

        // 7. Delete customer notes
        customers.forEach { customer ->
            val notes = customerNoteRepository.findByCustomerIdAndStudioIdOrderByCreatedAtDesc(customer.id, studioId)
            customerNoteRepository.deleteAll(notes)
        }

        // 8. Delete customer documents via native query (no repository method needed)
        entityManager.createQuery(
            "DELETE FROM CustomerDocumentEntity cd WHERE cd.studioId = :studioId"
        ).setParameter("studioId", studioId).executeUpdate()
        entityManager.flush()

        // 9. Delete customers
        customerRepository.deleteAll(customers)
        entityManager.flush()

        // 10. Delete studio instagram profile links
        val studioProfiles = studioInstagramProfileRepository.findByStudioId(studioId)
        studioInstagramProfileRepository.deleteAll(studioProfiles)
        entityManager.flush()

        // 11. Remove orphaned global instagram profiles and their snapshots
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

        // 12. Delete user
        userRepository.deleteById(demo.userId)
        entityManager.flush()

        // 13. Delete studio
        studioRepository.deleteById(studioId)
        entityManager.flush()

        // 14. Delete demo account record
        demoAccountRepository.delete(demo)

        logger.info("Demo account cleanup complete: studioId={}", studioId)
    }
}

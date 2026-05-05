package pl.detailing.crm.smscredits.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class SmsCreditPackageSeeder(
    private val jpa: SmsCreditPackageJpaRepository
) : ApplicationRunner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        if (jpa.existsByIsActiveTrue()) return

        val packages = listOf(
            SmsCreditPackageEntity(UUID.fromString("a1000000-0000-0000-0000-000000000001"), "Starter",       50,   1999L, "PLN", true, sortOrder = 0),
            SmsCreditPackageEntity(UUID.fromString("a1000000-0000-0000-0000-000000000002"), "Basic",         100,  3499L, "PLN", true, sortOrder = 1),
            SmsCreditPackageEntity(UUID.fromString("a1000000-0000-0000-0000-000000000003"), "Standard",      250,  7999L, "PLN", true, sortOrder = 2),
            SmsCreditPackageEntity(UUID.fromString("a1000000-0000-0000-0000-000000000004"), "Professional",  500, 14999L, "PLN", true, sortOrder = 3),
            SmsCreditPackageEntity(UUID.fromString("a1000000-0000-0000-0000-000000000005"), "Business",     1000, 24999L, "PLN", true, sortOrder = 4),
            SmsCreditPackageEntity(UUID.fromString("a1000000-0000-0000-0000-000000000006"), "Enterprise",   2500, 49999L, "PLN", true, sortOrder = 5)
        )

        jpa.saveAll(packages)
        logger.info("Seeded ${packages.size} SMS credit packages")
    }
}

package pl.detailing.crm.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class DatabaseInitializer(
    private val jdbcTemplate: JdbcTemplate
) {
    private val logger = LoggerFactory.getLogger(DatabaseInitializer::class.java)

    @PostConstruct
    fun initializeDatabase() {
        try {
            // Drop old unique indexes if they exist
            dropIndexIfExists("idx_customers_studio_email")
            dropIndexIfExists("idx_customers_studio_phone")

            // Create partial unique indexes that ignore null and empty values
            // This allows multiple customers with null/empty email or phone in the same studio
            // but enforces uniqueness for non-empty values
            createPartialUniqueIndex(
                indexName = "idx_customers_studio_email",
                tableName = "customers",
                columns = "studio_id, email",
                condition = "email IS NOT NULL AND email != ''"
            )

            createPartialUniqueIndex(
                indexName = "idx_customers_studio_phone",
                tableName = "customers",
                columns = "studio_id, phone",
                condition = "phone IS NOT NULL AND phone != ''"
            )

            logger.info("Database partial unique indexes created successfully")
        } catch (e: Exception) {
            logger.error("Error creating partial unique indexes", e)
            throw e
        }
    }

    private fun dropIndexIfExists(indexName: String) {
        try {
            jdbcTemplate.execute("DROP INDEX IF EXISTS $indexName")
            logger.debug("Dropped index if existed: $indexName")
        } catch (e: Exception) {
            logger.warn("Could not drop index $indexName: ${e.message}")
        }
    }

    private fun createPartialUniqueIndex(
        indexName: String,
        tableName: String,
        columns: String,
        condition: String
    ) {
        val sql = """
            CREATE UNIQUE INDEX IF NOT EXISTS $indexName
            ON $tableName ($columns)
            WHERE $condition
        """.trimIndent()

        jdbcTemplate.execute(sql)
        logger.info("Created partial unique index: $indexName on $tableName($columns) where $condition")
    }
}

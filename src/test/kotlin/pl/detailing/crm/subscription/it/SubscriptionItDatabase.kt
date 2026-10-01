package pl.detailing.crm.subscription.it

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager

/**
 * Osobna baza Postgresa dla KAŻDEJ klasy testów integracyjnych subskrypcji.
 *
 * Klasy testowe biegną równolegle (`junit-platform.properties`), a każda zakłada schemat
 * przez `create-drop` i czyści tabele przed każdym testem. Na wspólnej bazie jedna klasa
 * kasowałaby dane drugiej w połowie scenariusza — błąd wyglądałby na losowy i wskazywał
 * na niewinny test. Nazwa bazy zawiera PID, więc dwa równoległe przebiegi Gradle'a na tym
 * samym serwerze też się nie spotkają.
 *
 * Serwer: `SUBSCRIPTION_IT_JDBC_URL` (np. lokalny Postgres bez Dockera; baza z adresu służy
 * tylko do zakładania baz testowych), a bez niej — jeden kontener Testcontainers na JVM.
 */
object SubscriptionItDatabase {

    private val externalUrl: String? = System.getenv("SUBSCRIPTION_IT_JDBC_URL")
    private val externalUser: String = System.getenv("SUBSCRIPTION_IT_DB_USER") ?: "postgres"
    private val externalPassword: String = System.getenv("SUBSCRIPTION_IT_DB_PASSWORD") ?: "postgres"

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:16-alpine")
            .withCommand("postgres", "-c", "max_connections=300")
            .also { it.start() }
    }

    private data class Server(val adminUrl: String, val user: String, val password: String)

    private val server: Server by lazy {
        if (externalUrl != null) Server(externalUrl, externalUser, externalPassword)
        else Server(container.jdbcUrl, container.username, container.password)
    }

    fun register(registry: DynamicPropertyRegistry, name: String) {
        val database = "it_${name.lowercase().replace(Regex("[^a-z0-9_]"), "_")}_${ProcessHandle.current().pid()}"
        val url by lazy { create(database) }
        registry.add("spring.datasource.url") { url }
        registry.add("spring.datasource.username") { server.user }
        registry.add("spring.datasource.password") { server.password }
    }

    @Synchronized
    private fun create(database: String): String {
        DriverManager.getConnection(server.adminUrl, server.user, server.password).use { connection ->
            connection.createStatement().use { statement ->
                // Bazy po przebiegach, których JVM już nie żyje. Sprzątanie przy zamknięciu
                // JVM ścigałoby się z `create-drop` Hibernate'a (drop bazy w trakcie drop tabel).
                val stale = statement.executeQuery("SELECT datname FROM pg_database WHERE datname LIKE 'it\\_%'").use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }.filter { name ->
                    val pid = name.substringAfterLast('_').toLongOrNull()
                    pid != null && pid != ProcessHandle.current().pid() && ProcessHandle.of(pid).isEmpty
                }
                stale.forEach { statement.execute("DROP DATABASE IF EXISTS $it WITH (FORCE)") }

                statement.execute("DROP DATABASE IF EXISTS $database WITH (FORCE)")
                statement.execute("CREATE DATABASE $database")
            }
        }
        return server.adminUrl.replace(Regex("/[^/?]+(\\?|$)"), "/$database$1")
    }
}

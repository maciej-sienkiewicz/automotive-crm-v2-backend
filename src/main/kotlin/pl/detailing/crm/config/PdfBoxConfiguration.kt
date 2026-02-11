package pl.detailing.crm.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Configuration for Apache PDFBox to optimize font cache performance.
 *
 * PDFBox by default scans all system fonts and builds a cache. This can take
 * 10+ seconds on first run. By setting a persistent cache directory, we ensure
 * the cache is built only once and reused across application restarts.
 */
@Configuration
class PdfBoxConfiguration {

    private val logger = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun configurePdfBoxCache() {
        try {
            // Set PDFBox font cache to a persistent directory
            val cacheDir = Paths.get(System.getProperty("user.home"), ".pdfbox-cache")

            // Create directory if it doesn't exist
            if (!Files.exists(cacheDir)) {
                Files.createDirectories(cacheDir)
                logger.info("Created PDFBox font cache directory: $cacheDir")
            }

            // Set system property for PDFBox to use this cache directory
            System.setProperty("pdfbox.fontcache", cacheDir.toString())

            logger.info("PDFBox font cache configured at: $cacheDir")
            logger.info("This will significantly speed up PDF form filling operations")
        } catch (e: Exception) {
            logger.error("Failed to configure PDFBox font cache, performance may be degraded", e)
        }
    }
}

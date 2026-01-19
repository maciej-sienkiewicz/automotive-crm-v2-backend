package pl.detailing.crm.visit.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import pl.detailing.crm.visit.domain.DamagePoint
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Service for generating damage map images by overlaying damage points on a car schematic.
 *
 * Technical Details:
 * - Uses java.awt.Graphics2D for image manipulation
 * - Coordinates are percentage-based (0-100) and translated to absolute pixels
 * - Damage points are drawn as red circles with white text labels
 * - Output is JPG format with 85% quality for optimal file size
 *
 * Drawing Styles:
 * - Circle: Red (#EF4444), diameter 30px (scalable based on image size)
 * - Text: White, bold, centered inside circle
 * - Anti-aliasing: Enabled for smooth edges
 */
@Service
class DamageMarkingService {

    companion object {
        private val logger = LoggerFactory.getLogger(DamageMarkingService::class.java)
        private const val CAR_SCHEMATIC_TEMPLATE = "images/car_schematic.jpg"
        private const val DAMAGE_CIRCLE_DIAMETER = 30
        private val DAMAGE_CIRCLE_COLOR = Color(0xEF, 0x44, 0x44) // #EF4444 Red
        private val TEXT_COLOR = Color.WHITE
        private const val JPG_QUALITY = 0.85f
    }

    /**
     * Generate a damage map image by burning damage points into the car schematic.
     *
     * @param damagePoints List of damage coordinates (percentage-based)
     * @return ByteArray containing the generated JPG image, or null if no damage points provided
     * @throws IllegalStateException if the car schematic template is not found
     */
    suspend fun generateDamageMap(damagePoints: List<DamagePoint>): ByteArray? = withContext(Dispatchers.IO) {
        // If no damage points, return null (skip image generation)
        if (damagePoints.isEmpty()) {
            logger.debug("No damage points provided, skipping damage map generation")
            return@withContext null
        }

        try {
            // Load the base car schematic template
            val templateResource = ClassPathResource(CAR_SCHEMATIC_TEMPLATE)
            if (!templateResource.exists()) {
                throw IllegalStateException("Car schematic template not found at: $CAR_SCHEMATIC_TEMPLATE")
            }

            val baseImage = templateResource.inputStream.use { inputStream ->
                ImageIO.read(inputStream)
                    ?: throw IllegalStateException("Failed to read car schematic template")
            }

            // Create a copy of the base image to draw on
            val outputImage = BufferedImage(
                baseImage.width,
                baseImage.height,
                BufferedImage.TYPE_INT_RGB
            )

            val graphics = outputImage.createGraphics()

            try {
                // Enable anti-aliasing for smooth circles and text
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

                // Draw the base image
                graphics.drawImage(baseImage, 0, 0, null)

                // Calculate circle size based on image dimensions
                val imageWidth = baseImage.width
                val imageHeight = baseImage.height
                val circleDiameter = DAMAGE_CIRCLE_DIAMETER

                // Draw each damage point
                damagePoints.forEach { point ->
                    // Convert percentage coordinates to absolute pixels
                    val absoluteX = (point.x / 100.0 * imageWidth).toInt()
                    val absoluteY = (point.y / 100.0 * imageHeight).toInt()

                    // Draw filled red circle
                    graphics.color = DAMAGE_CIRCLE_COLOR
                    graphics.fillOval(
                        absoluteX - circleDiameter / 2,
                        absoluteY - circleDiameter / 2,
                        circleDiameter,
                        circleDiameter
                    )

                    // Draw white text (damage point ID) centered in the circle
                    graphics.color = TEXT_COLOR
                    val font = Font("Arial", Font.BOLD, 16)
                    graphics.font = font

                    val text = point.id.toString()
                    val fontMetrics = graphics.fontMetrics
                    val textWidth = fontMetrics.stringWidth(text)
                    val textHeight = fontMetrics.height

                    // Center the text in the circle
                    val textX = absoluteX - textWidth / 2
                    val textY = absoluteY + textHeight / 4 // Slightly adjusted for visual centering

                    graphics.drawString(text, textX, textY)
                }

                // Convert BufferedImage to JPG byte array
                val outputStream = ByteArrayOutputStream()
                ImageIO.write(outputImage, "jpg", outputStream)
                val imageBytes = outputStream.toByteArray()

                logger.info("Generated damage map with {} damage points, image size: {} bytes",
                    damagePoints.size, imageBytes.size)

                return@withContext imageBytes

            } finally {
                // Properly dispose Graphics2D to avoid memory leaks
                graphics.dispose()
            }

        } catch (e: Exception) {
            logger.error("Failed to generate damage map", e)
            throw IllegalStateException("Failed to generate damage map: ${e.message}", e)
        }
    }
}

package pl.detailing.crm.visit.infrastructure

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Utility to generate a simple placeholder car schematic for development/testing.
 *
 * This is NOT meant for production use. In production, a professional car schematic
 * should be placed at src/main/resources/images/car_schematic.jpg
 *
 * Usage:
 * ```
 * fun main() {
 *     CarSchematicPlaceholderGenerator.generatePlaceholder(
 *         File("src/main/resources/images/car_schematic.jpg")
 *     )
 * }
 * ```
 */
object CarSchematicPlaceholderGenerator {

    private const val WIDTH = 1200
    private const val HEIGHT = 800
    private val BACKGROUND_COLOR = Color(0xF5, 0xF5, 0xF5) // Light gray
    private val CAR_COLOR = Color(0x33, 0x33, 0x33) // Dark gray
    private val LINE_COLOR = Color(0x99, 0x99, 0x99) // Medium gray

    fun generatePlaceholder(outputFile: File) {
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()

        try {
            // Enable anti-aliasing
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // Draw background
            g.color = BACKGROUND_COLOR
            g.fillRect(0, 0, WIDTH, HEIGHT)

            // Draw a simple car outline (top-down view)
            g.color = CAR_COLOR
            g.stroke = BasicStroke(3f)

            // Car body (simplified rectangle with rounded corners)
            val carX = 300
            val carY = 200
            val carWidth = 600
            val carHeight = 400

            g.drawRoundRect(carX, carY, carWidth, carHeight, 50, 50)

            // Draw front windshield area
            g.color = LINE_COLOR
            g.drawLine(carX + 50, carY + 100, carX + carWidth - 50, carY + 100)

            // Draw rear windshield area
            g.drawLine(carX + 50, carY + carHeight - 100, carX + carWidth - 50, carY + carHeight - 100)

            // Draw doors (vertical lines)
            g.drawLine(carX + carWidth / 2, carY, carX + carWidth / 2, carY + carHeight)

            // Add labels
            g.color = Color.BLACK
            g.font = Font("Arial", Font.BOLD, 24)
            g.drawString("FRONT", carX + carWidth / 2 - 50, carY + 50)
            g.drawString("REAR", carX + carWidth / 2 - 40, carY + carHeight - 30)
            g.drawString("LEFT", carX - 80, carY + carHeight / 2 + 10)
            g.drawString("RIGHT", carX + carWidth + 20, carY + carHeight / 2 + 10)

            // Add watermark
            g.font = Font("Arial", Font.PLAIN, 16)
            g.color = Color(0x99, 0x99, 0x99)
            g.drawString("PLACEHOLDER - Replace with professional schematic", 30, HEIGHT - 30)

            // Save to file
            outputFile.parentFile?.mkdirs()
            ImageIO.write(image, "jpg", outputFile)

            println("Generated placeholder car schematic at: ${outputFile.absolutePath}")

        } finally {
            g.dispose()
        }
    }
}

// Uncomment to generate placeholder
// fun main() {
//     CarSchematicPlaceholderGenerator.generatePlaceholder(
//         File("src/main/resources/images/car_schematic.jpg")
//     )
// }

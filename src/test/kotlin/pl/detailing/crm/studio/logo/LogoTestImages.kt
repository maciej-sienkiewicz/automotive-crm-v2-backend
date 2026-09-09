package pl.detailing.crm.studio.logo

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Obrazy testowe generowane w locie — bez binarnych fixture'ów w repo. */
object LogoTestImages {

    /**
     * PNG z przezroczystym tłem i nieprzezroczystym prostokątem o zadanym rozmiarze,
     * otoczonym marginesem [margin] px z każdej strony.
     */
    fun transparentPngWithBox(boxWidth: Int, boxHeight: Int, margin: Int): ByteArray {
        val image = BufferedImage(boxWidth + 2 * margin, boxHeight + 2 * margin, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.color = Color(0x11, 0x17, 0x29)
            g.fillRect(margin, margin, boxWidth, boxHeight)
        } finally {
            g.dispose()
        }
        return encode(image, "png")
    }

    fun opaqueJpeg(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        try {
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            g.color = Color.RED
            g.fillOval(width / 4, height / 4, width / 2, height / 2)
        } finally {
            g.dispose()
        }
        return encode(image, "jpeg")
    }

    fun encode(image: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().use { out ->
            check(ImageIO.write(image, format, out)) { "No ImageIO writer for $format" }
            out.toByteArray()
        }

    fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(bytes.inputStream())

    /** Poziomy logotyp 400×100 z klasą w <style> (jak eksport z Illustratora). */
    const val WIDE_SVG = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 400 100" width="400" height="100">
  <style>.st0{fill:#111729;}</style>
  <rect class="st0" x="0" y="0" width="400" height="100"/>
</svg>"""
}

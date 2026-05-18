package pl.detailing.crm.visit.infrastructure

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.visit.domain.DamagePoint
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.io.File

@Service
class DamageMapReportService(
    private val damageMarkingService: DamageMarkingService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(DamageMapReportService::class.java)

        private const val MARGIN = 36f
        private val PAGE_WIDTH = PDRectangle.A4.width
        private val PAGE_HEIGHT = PDRectangle.A4.height
        private val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN

        private const val HEADER_HEIGHT = 42f
        private const val SECTION_GAP = 12f
        private const val LEGEND_TITLE_HEIGHT = 28f
        private const val LEGEND_ROW_HEIGHT = 22f
        private const val LEGEND_PADDING_V = 8f
        private const val MARKER_RADIUS = 8f
        private const val TWO_COLUMN_THRESHOLD = 10

        private val BRAND_BLUE = Color(0x1E, 0x40, 0xAF)
        private val DAMAGE_RED = Color(0xEF, 0x44, 0x44)
        private val BORDER_GRAY = Color(0xE2, 0xE8, 0xF0)
        private val ROW_ALT_BG = Color(0xF8, 0xFA, 0xFC)
        private val TEXT_PRIMARY = Color(0x1E, 0x29, 0x3B)
        private val TEXT_MUTED = Color(0x64, 0x74, 0x8B)

        private val SYSTEM_FONTS_REGULAR = listOf(
            // Liberation Sans (Arial-compatible, professional) — preferred
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/liberation/LiberationSans-Regular.ttf",
            // DejaVu Sans — fallback
            "/usr/share/fonts/truetype/DejaVu/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans.ttf",
        )
        private val SYSTEM_FONTS_BOLD = listOf(
            // Liberation Sans Bold — preferred
            "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
            "/usr/share/fonts/liberation/LiberationSans-Bold.ttf",
            // DejaVu Sans Bold — fallback
            "/usr/share/fonts/truetype/DejaVu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
            "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
        )
    }

    /**
     * Generates a single-page A4 PDF containing:
     *  - the car schematic with numbered damage markers (upper section)
     *  - a legend table mapping each marker number to its description (lower section)
     *
     * Layout adapts dynamically: up to 10 points use a single-column legend;
     * 11-20 points switch to two columns so the diagram always gets ≥ 55% of page height.
     *
     * @return PDF bytes, or null if [damagePoints] is empty
     */
    suspend fun generateReport(damagePoints: List<DamagePoint>): ByteArray? = withContext(Dispatchers.IO) {
        if (damagePoints.isEmpty()) return@withContext null

        val sorted = damagePoints.sortedBy { it.id }
        val markedImageBytes = damageMarkingService.generateDamageMap(sorted) ?: return@withContext null

        try {
            PDDocument().use { doc ->
                val regular = loadFont(doc, bold = false)
                val bold = loadFont(doc, bold = true)

                val legendHeight = computeLegendBlockHeight(sorted.size)
                val imageAreaHeight = (PAGE_HEIGHT - 2 * MARGIN - HEADER_HEIGHT - SECTION_GAP - legendHeight - SECTION_GAP)
                    .coerceAtLeast(180f)

                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)

                PDPageContentStream(doc, page).use { cs ->
                    renderHeader(cs, bold)
                    renderDiagram(cs, doc, markedImageBytes, imageAreaHeight)
                    renderLegend(cs, sorted, regular, bold, imageAreaHeight)
                }

                ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
            }
        } catch (e: Exception) {
            logger.error("Failed to generate damage map PDF", e)
            throw IllegalStateException("Damage map PDF generation failed: ${e.message}", e)
        }
    }

    // ─── Layout helpers ────────────────────────────────────────────────────────

    /**
     * Returns total pixel height needed by the legend block (title + table rows).
     * Two-column layout halves the row count when point count exceeds [TWO_COLUMN_THRESHOLD].
     */
    private fun computeLegendBlockHeight(count: Int): Float {
        val rows = if (count > TWO_COLUMN_THRESHOLD) (count + 1) / 2 else count
        return LEGEND_TITLE_HEIGHT + LEGEND_PADDING_V + rows * LEGEND_ROW_HEIGHT + LEGEND_PADDING_V
    }

    // ─── Rendering ─────────────────────────────────────────────────────────────

    private fun renderHeader(cs: PDPageContentStream, bold: PDFont) {
        cs.beginText()
        cs.setFont(bold, 13f)
        cs.setNonStrokingColor(BRAND_BLUE)
        cs.newLineAtOffset(MARGIN, PAGE_HEIGHT - MARGIN - 24f)
        cs.showText(bold.safe("Protokół uszkodzeń pojazdu"))
        cs.endText()

        val lineY = PAGE_HEIGHT - MARGIN - HEADER_HEIGHT
        cs.setStrokingColor(BORDER_GRAY)
        cs.setLineWidth(1f)
        cs.moveTo(MARGIN, lineY)
        cs.lineTo(PAGE_WIDTH - MARGIN, lineY)
        cs.stroke()
    }

    private fun renderDiagram(
        cs: PDPageContentStream,
        doc: PDDocument,
        imageBytes: ByteArray,
        areaHeight: Float
    ) {
        val pdImage = PDImageXObject.createFromByteArray(doc, imageBytes, "diagram")
        val imageAspect = pdImage.width.toFloat() / pdImage.height

        val (drawW, drawH) = fitIntoArea(CONTENT_WIDTH, areaHeight, imageAspect)
        val imageX = MARGIN + (CONTENT_WIDTH - drawW) / 2f
        val imageY = PAGE_HEIGHT - MARGIN - HEADER_HEIGHT - SECTION_GAP - drawH

        cs.drawImage(pdImage, imageX, imageY, drawW, drawH)
    }

    private fun renderLegend(
        cs: PDPageContentStream,
        points: List<DamagePoint>,
        regular: PDFont,
        bold: PDFont,
        imageAreaHeight: Float
    ) {
        val legendTopY = PAGE_HEIGHT - MARGIN - HEADER_HEIGHT - SECTION_GAP - imageAreaHeight - SECTION_GAP

        cs.beginText()
        cs.setFont(bold, 8f)
        cs.setNonStrokingColor(TEXT_MUTED)
        cs.newLineAtOffset(MARGIN, legendTopY - 17f)
        cs.showText(bold.safe("LEGENDA USZKODZEN"))
        cs.endText()

        val tableTopY = legendTopY - LEGEND_TITLE_HEIGHT

        if (points.size > TWO_COLUMN_THRESHOLD) {
            val colW = (CONTENT_WIDTH - 6f) / 2f
            val mid = points.size / 2 + points.size % 2
            renderTable(cs, points.subList(0, mid), regular, bold, MARGIN, tableTopY, colW)
            renderTable(cs, points.subList(mid, points.size), regular, bold, MARGIN + colW + 6f, tableTopY, colW)
        } else {
            renderTable(cs, points, regular, bold, MARGIN, tableTopY, CONTENT_WIDTH)
        }
    }

    private fun renderTable(
        cs: PDPageContentStream,
        points: List<DamagePoint>,
        regular: PDFont,
        bold: PDFont,
        x: Float,
        topY: Float,
        width: Float
    ) {
        if (points.isEmpty()) return

        val numColW = 34f
        val descColW = width - numColW
        val tableH = LEGEND_PADDING_V + points.size * LEGEND_ROW_HEIGHT + LEGEND_PADDING_V

        // Table background + border
        cs.setNonStrokingColor(Color.WHITE)
        cs.addRect(x, topY - tableH, width, tableH)
        cs.fill()

        cs.setStrokingColor(BORDER_GRAY)
        cs.setLineWidth(0.75f)
        cs.addRect(x, topY - tableH, width, tableH)
        cs.stroke()

        points.forEachIndexed { idx, point ->
            val rowTopY = topY - LEGEND_PADDING_V - idx * LEGEND_ROW_HEIGHT
            val rowBottomY = rowTopY - LEGEND_ROW_HEIGHT
            val rowMidY = rowBottomY + LEGEND_ROW_HEIGHT / 2f

            // Alternating row stripe
            if (idx % 2 == 1) {
                cs.setNonStrokingColor(ROW_ALT_BG)
                cs.addRect(x + 0.5f, rowBottomY, width - 1f, LEGEND_ROW_HEIGHT)
                cs.fill()
            }

            // Damage marker circle
            drawFilledCircle(cs, x + numColW / 2f, rowMidY, MARKER_RADIUS, DAMAGE_RED)

            // Marker ID centered in circle
            val idText = point.id.toString()
            val idSize = 7.5f
            val idW = bold.getStringWidth(idText) / 1000f * idSize
            cs.beginText()
            cs.setFont(bold, idSize)
            cs.setNonStrokingColor(Color.WHITE)
            cs.newLineAtOffset(x + numColW / 2f - idW / 2f, rowMidY - 3f)
            cs.showText(idText)  // digits are always safe
            cs.endText()

            // Description — truncated to fit column width
            val maxDescW = descColW - 12f
            val rawNote = regular.safe(point.note?.trim()?.ifBlank { null } ?: "-")
            val noteText = truncateText(rawNote, regular, 8f, maxDescW)
            cs.beginText()
            cs.setFont(regular, 8f)
            cs.setNonStrokingColor(TEXT_PRIMARY)
            cs.newLineAtOffset(x + numColW + 6f, rowMidY - 3f)
            cs.showText(noteText)
            cs.endText()

            // Row separator (skip after last row)
            if (idx < points.size - 1) {
                cs.setStrokingColor(BORDER_GRAY)
                cs.setLineWidth(0.4f)
                cs.moveTo(x + 1f, rowBottomY)
                cs.lineTo(x + width - 1f, rowBottomY)
                cs.stroke()
            }
        }

        // Vertical divider between ID column and description column
        cs.setStrokingColor(BORDER_GRAY)
        cs.setLineWidth(0.5f)
        cs.moveTo(x + numColW, topY - LEGEND_PADDING_V / 2f)
        cs.lineTo(x + numColW, topY - tableH + LEGEND_PADDING_V / 2f)
        cs.stroke()
    }

    // ─── Drawing primitives ────────────────────────────────────────────────────

    /** Draws a filled circle using cubic Bézier approximation (k ≈ 0.5523). */
    private fun drawFilledCircle(cs: PDPageContentStream, cx: Float, cy: Float, r: Float, color: Color) {
        val k = 0.5522847f * r
        cs.setNonStrokingColor(color)
        cs.moveTo(cx - r, cy)
        cs.curveTo(cx - r, cy + k, cx - k, cy + r, cx, cy + r)
        cs.curveTo(cx + k, cy + r, cx + r, cy + k, cx + r, cy)
        cs.curveTo(cx + r, cy - k, cx + k, cy - r, cx, cy - r)
        cs.curveTo(cx - k, cy - r, cx - r, cy - k, cx - r, cy)
        cs.closePath()
        cs.fill()
    }

    // ─── Text helpers ──────────────────────────────────────────────────────────

    /**
     * Returns [text] safe for this font: passes through unchanged for Unicode fonts (PDType0Font),
     * transliterates Polish characters to ASCII equivalents for Helvetica (PDType1Font).
     */
    private fun PDFont.safe(text: String): String =
        if (this is PDType1Font) toWinAnsiSafe(text) else text

    /**
     * Truncates [text] so its rendered width at [fontSize] fits within [maxWidth] points.
     * Appends "..." if truncation occurred.
     */
    private fun truncateText(text: String, font: PDFont, fontSize: Float, maxWidth: Float): String {
        val sanitized = text.replace('\n', ' ').replace('\r', ' ')
        if (font.getStringWidth(sanitized) / 1000f * fontSize <= maxWidth) return sanitized

        var truncated = sanitized
        val ellipsis = "..."
        val ellipsisW = font.getStringWidth(ellipsis) / 1000f * fontSize
        while (truncated.isNotEmpty() && font.getStringWidth(truncated) / 1000f * fontSize + ellipsisW > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated$ellipsis"
    }

    // ─── Geometry helpers ──────────────────────────────────────────────────────

    /**
     * Returns (width, height) that fits [sourceAspect] within ([areaW] × [areaH])
     * while maintaining aspect ratio.
     */
    private fun fitIntoArea(areaW: Float, areaH: Float, sourceAspect: Float): Pair<Float, Float> {
        val areaAspect = areaW / areaH
        return if (sourceAspect > areaAspect) {
            areaW to (areaW / sourceAspect)
        } else {
            (areaH * sourceAspect) to areaH
        }
    }

    // ─── Font loading ──────────────────────────────────────────────────────────

    /**
     * Loads Liberation Sans from the bundled classpath resource first (environment-independent),
     * falls back to DejaVu Sans, then well-known system paths, and finally to Helvetica (ASCII-only).
     * Liberation Sans is Arial-compatible and renders more professionally in client-facing documents.
     */
    private fun loadFont(doc: PDDocument, bold: Boolean): PDFont {
        val classpathFonts = if (bold) {
            listOf("/fonts/LiberationSans-Bold.ttf", "/fonts/DejaVuSans-Bold.ttf")
        } else {
            listOf("/fonts/LiberationSans-Regular.ttf", "/fonts/DejaVuSans.ttf")
        }
        for (classpathName in classpathFonts) {
            javaClass.getResourceAsStream(classpathName)?.use { stream ->
                runCatching {
                    return PDType0Font.load(doc, stream, false)
                }.onFailure {
                    logger.warn("Could not load classpath font '$classpathName': ${it.message}")
                }
            }
        }

        val paths = if (bold) SYSTEM_FONTS_BOLD else SYSTEM_FONTS_REGULAR
        for (path in paths) {
            val file = File(path)
            if (!file.exists()) continue
            runCatching {
                return PDType0Font.load(doc, file.inputStream(), false)
            }.onFailure {
                logger.warn("Could not load system font '$path': ${it.message}")
            }
        }

        val fallbackName = if (bold) Standard14Fonts.FontName.HELVETICA_BOLD else Standard14Fonts.FontName.HELVETICA
        logger.warn("No Unicode font found; falling back to Helvetica — Polish characters will be stripped")
        return PDType1Font(fallbackName)
    }

    /**
     * Removes characters outside WinAnsiEncoding so Helvetica fallback doesn't crash.
     * Only called when a Unicode font could not be loaded.
     */
    private fun toWinAnsiSafe(text: String): String =
        text.map { c ->
            when (c) {
                'ą' -> 'a'; 'ć' -> 'c'; 'ę' -> 'e'; 'ł' -> 'l'; 'ń' -> 'n'
                'ó' -> 'o'; 'ś' -> 's'; 'ź', 'ż' -> 'z'
                'Ą' -> 'A'; 'Ć' -> 'C'; 'Ę' -> 'E'; 'Ł' -> 'L'; 'Ń' -> 'N'
                'Ó' -> 'O'; 'Ś' -> 'S'; 'Ź', 'Ż' -> 'Z'
                else -> if (c.code in 32..255) c else '?'
            }
        }.joinToString("")
}

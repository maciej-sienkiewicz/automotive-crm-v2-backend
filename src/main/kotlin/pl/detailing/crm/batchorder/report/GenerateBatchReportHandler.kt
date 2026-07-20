package pl.detailing.crm.batchorder.report

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryEntity
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Service
class GenerateBatchReportHandler(
    private val contractorRepository: BatchContractorRepository,
    private val entryRepository: BatchOrderEntryRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val documentStorageService: DocumentStorageService
) {
    @Transactional(readOnly = true)
    suspend fun handle(command: GenerateBatchReportCommand): ByteArray {
        val contractor = contractorRepository.findByIdAndStudioId(command.contractorId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Contractor not found")

        val entries = if (command.from != null && command.to != null) {
            entryRepository.findByContractorIdAndStudioIdAndDateRange(
                contractorId = command.contractorId.value,
                studioId = command.studioId.value,
                from = command.from,
                to = command.to
            )
        } else {
            entryRepository.findByContractorIdAndStudioId(
                contractorId = command.contractorId.value,
                studioId = command.studioId.value
            )
        }

        val logoBytes: ByteArray? = studioSettingsRepository.findById(command.studioId.value)
            .orElse(null)?.logoS3Key?.let { key ->
                runCatching { documentStorageService.downloadBytes(key) }.getOrNull()
            }

        return buildPdf(
            contractorName = contractor.name,
            contractorTaxId = contractor.taxId,
            from = command.from,
            to = command.to,
            entries = entries,
            logoBytes = logoBytes
        )
    }

    private fun buildPdf(
        contractorName: String,
        contractorTaxId: String?,
        from: LocalDate?,
        to: LocalDate?,
        entries: List<BatchOrderEntryEntity>,
        logoBytes: ByteArray?
    ): ByteArray {
        val document = PDDocument()
        val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy")

        val regular = PDType0Font.load(
            document,
            GenerateBatchReportHandler::class.java.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!,
            true
        )
        val bold = PDType0Font.load(
            document,
            GenerateBatchReportHandler::class.java.getResourceAsStream("/fonts/LiberationSans-Bold.ttf")!!,
            true
        )

        val pageWidth = PDRectangle.A4.width
        val pageHeight = PDRectangle.A4.height
        val margin = 40f
        val usableWidth = pageWidth - 2 * margin

        val colDate = 70f
        val colVehicle = 130f
        val colServices = 200f
        val colNet = 75f
        val colGross = 75f
        val colNotes = usableWidth - colDate - colVehicle - colServices - colNet - colGross

        val headerFontSize = 14f
        val subheaderFontSize = 10f
        val tableFontSize = 8f
        val tableHeaderFontSize = 8f
        val rowMinHeight = 14f
        val logoMaxW = 120f
        val logoMaxH = 50f

        fun newPage(): Pair<PDPage, PDPageContentStream> {
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            val cs = PDPageContentStream(document, page)
            return Pair(page, cs)
        }

        fun formatMoney(cents: Long): String {
            val amount = cents / 100.0
            return "%.2f zł".format(amount)
        }

        fun drawText(cs: PDPageContentStream, text: String, font: PDFont, size: Float, x: Float, y: Float) {
            cs.beginText()
            cs.setFont(font, size)
            cs.newLineAtOffset(x, y)
            cs.showText(text)
            cs.endText()
        }

        fun truncateText(text: String, font: PDFont, fontSize: Float, maxWidth: Float): String {
            var result = text
            try {
                while (result.isNotEmpty() && font.getStringWidth(result) / 1000 * fontSize > maxWidth) {
                    result = result.dropLast(1)
                }
                if (result.length < text.length && result.isNotEmpty()) {
                    result = result.dropLast(3) + "..."
                }
            } catch (_: Exception) {
                result = text.take(20)
            }
            return result
        }

        var (_, cs) = newPage()
        var currentY = pageHeight - margin

        // ---- LOGO (top-right corner, drawn first so text renders on top if they overlap) ----
        if (logoBytes != null) {
            runCatching {
                val pdImage = PDImageXObject.createFromByteArray(document, logoBytes, "logo")
                val aspect = pdImage.width.toFloat() / pdImage.height
                val (drawW, drawH) = if (aspect > logoMaxW / logoMaxH) {
                    logoMaxW to (logoMaxW / aspect)
                } else {
                    (logoMaxH * aspect) to logoMaxH
                }
                cs.drawImage(pdImage, pageWidth - margin - drawW, currentY - drawH, drawW, drawH)
            }
        }

        // ---- TITLE + META (left side; logo on the right shares the same vertical zone) ----
        drawText(cs, "ZESTAWIENIE ZBIORCZE", bold, headerFontSize, margin, currentY)
        currentY -= 20f

        drawText(cs, "Kontrahent: $contractorName", bold, subheaderFontSize, margin, currentY)
        if (contractorTaxId != null) {
            drawText(cs, "NIP: $contractorTaxId", regular, subheaderFontSize, margin + 280f, currentY)
        }
        currentY -= 16f

        drawText(cs, "Liczba wpisów: ${entries.size}", regular, subheaderFontSize, margin, currentY)
        currentY -= 16f

        // Ensure full-width elements begin below the logo area
        currentY = minOf(currentY, pageHeight - margin - logoMaxH - 4f)

        // ---- PERIOD BAND (full-width dark-blue highlight) ----
        val periodText = when {
            from != null && to != null -> "Okres: ${from.format(dateFormat)} – ${to.format(dateFormat)}"
            from != null -> "Od: ${from.format(dateFormat)}"
            to != null -> "Do: ${to.format(dateFormat)}"
            else -> "Wszystkie wpisy"
        }
        cs.setNonStrokingColor(0.12f, 0.27f, 0.67f)
        cs.addRect(margin, currentY - 20f, usableWidth, 22f)
        cs.fill()
        cs.setNonStrokingColor(1f, 1f, 1f)
        drawText(cs, periodText, bold, subheaderFontSize, margin + 6f, currentY - 14f)
        cs.setNonStrokingColor(0f, 0f, 0f)
        currentY -= 26f

        // ---- TABLE HEADER ----
        val totalNet = entries.sumOf { it.netAmountCents }
        val totalGross = entries.sumOf { it.grossAmountCents }

        cs.setNonStrokingColor(0.15f, 0.15f, 0.15f)
        cs.addRect(margin, currentY - 14f, usableWidth, 16f)
        cs.fill()

        cs.setNonStrokingColor(1f, 1f, 1f)
        var colX = margin + 3f
        drawText(cs, "Data", bold, tableHeaderFontSize, colX, currentY - 10f)
        colX += colDate
        drawText(cs, "Pojazd", bold, tableHeaderFontSize, colX, currentY - 10f)
        colX += colVehicle
        drawText(cs, "Usługi", bold, tableHeaderFontSize, colX, currentY - 10f)
        colX += colServices
        drawText(cs, "Netto", bold, tableHeaderFontSize, colX, currentY - 10f)
        colX += colNet
        drawText(cs, "Brutto", bold, tableHeaderFontSize, colX, currentY - 10f)
        colX += colGross
        drawText(cs, "Uwagi", bold, tableHeaderFontSize, colX, currentY - 10f)
        cs.setNonStrokingColor(0f, 0f, 0f)

        currentY -= 16f

        // ---- TABLE ROWS ----
        var rowAlt = false
        for (entry in entries) {
            val vehicleText = buildString {
                if (!entry.vehicleMake.isNullOrBlank()) append(entry.vehicleMake)
                if (!entry.vehicleModel.isNullOrBlank()) {
                    if (isNotEmpty()) append(" ")
                    append(entry.vehicleModel)
                }
                if (!entry.vehicleLicensePlate.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(entry.vehicleLicensePlate)
                }
            }.ifBlank { "-" }

            val servicesText = entry.services.joinToString("\n").ifBlank { "-" }
            val notesText = entry.notes ?: ""

            val vehicleLines = vehicleText.split("\n")
            val serviceLines = servicesText.split("\n")
            val notesLines = if (notesText.isNotBlank()) notesText.split("\n") else listOf("")
            val maxLines = maxOf(vehicleLines.size, serviceLines.size, notesLines.size, 1)
            val rowHeight = maxOf(rowMinHeight, maxLines * (tableFontSize + 3f) + 6f)

            if (currentY - rowHeight < margin + 30f) {
                cs.setStrokingColor(0.7f, 0.7f, 0.7f)
                cs.moveTo(margin, currentY)
                cs.lineTo(margin + usableWidth, currentY)
                cs.stroke()
                cs.close()

                val (_, newCs) = newPage()
                cs = newCs
                currentY = pageHeight - margin

                cs.setNonStrokingColor(0.15f, 0.15f, 0.15f)
                cs.addRect(margin, currentY - 14f, usableWidth, 16f)
                cs.fill()
                cs.setNonStrokingColor(1f, 1f, 1f)
                colX = margin + 3f
                drawText(cs, "Data", bold, tableHeaderFontSize, colX, currentY - 10f)
                colX += colDate
                drawText(cs, "Pojazd", bold, tableHeaderFontSize, colX, currentY - 10f)
                colX += colVehicle
                drawText(cs, "Usługi", bold, tableHeaderFontSize, colX, currentY - 10f)
                colX += colServices
                drawText(cs, "Netto", bold, tableHeaderFontSize, colX, currentY - 10f)
                colX += colNet
                drawText(cs, "Brutto", bold, tableHeaderFontSize, colX, currentY - 10f)
                colX += colGross
                drawText(cs, "Uwagi", bold, tableHeaderFontSize, colX, currentY - 10f)
                cs.setNonStrokingColor(0f, 0f, 0f)
                currentY -= 16f
                rowAlt = false
            }

            if (rowAlt) {
                cs.setNonStrokingColor(0.96f, 0.96f, 0.97f)
                cs.addRect(margin, currentY - rowHeight, usableWidth, rowHeight)
                cs.fill()
                cs.setNonStrokingColor(0f, 0f, 0f)
            }

            val textY = currentY - tableFontSize - 3f
            colX = margin + 3f

            drawText(cs, entry.serviceDate.format(dateFormat), regular, tableFontSize, colX, textY)
            colX += colDate

            vehicleLines.forEachIndexed { idx, line ->
                drawText(cs, truncateText(line, regular, tableFontSize, colVehicle - 4f), regular, tableFontSize, colX, textY - idx * (tableFontSize + 3f))
            }
            colX += colVehicle

            serviceLines.forEachIndexed { idx, line ->
                drawText(cs, truncateText(line, regular, tableFontSize, colServices - 4f), regular, tableFontSize, colX, textY - idx * (tableFontSize + 3f))
            }
            colX += colServices

            drawText(cs, formatMoney(entry.netAmountCents), regular, tableFontSize, colX, textY)
            colX += colNet

            drawText(cs, formatMoney(entry.grossAmountCents), regular, tableFontSize, colX, textY)
            colX += colGross

            notesLines.forEachIndexed { idx, line ->
                drawText(cs, truncateText(line, regular, tableFontSize, colNotes - 4f), regular, tableFontSize, colX, textY - idx * (tableFontSize + 3f))
            }

            cs.setStrokingColor(0.85f, 0.85f, 0.85f)
            cs.moveTo(margin, currentY - rowHeight)
            cs.lineTo(margin + usableWidth, currentY - rowHeight)
            cs.stroke()

            currentY -= rowHeight
            rowAlt = !rowAlt
        }

        // ---- SUMMARY FOOTER (only place where totals appear) ----
        currentY -= 10f
        if (currentY < margin + 40f) {
            cs.close()
            val (_, newCs) = newPage()
            cs = newCs
            currentY = pageHeight - margin - 20f
        }

        cs.setNonStrokingColor(0.92f, 0.95f, 1.0f)
        cs.addRect(margin, currentY - 20f, usableWidth, 22f)
        cs.fill()
        cs.setNonStrokingColor(0f, 0f, 0f)

        drawText(cs, "PODSUMOWANIE:", bold, subheaderFontSize, margin + 3f, currentY - 14f)
        drawText(cs, "Łącznie netto: ${formatMoney(totalNet)}", bold, subheaderFontSize, margin + 200f, currentY - 14f)
        drawText(cs, "Łącznie brutto: ${formatMoney(totalGross)}", bold, subheaderFontSize, margin + 380f, currentY - 14f)

        cs.close()

        val output = ByteArrayOutputStream()
        document.save(output)
        document.close()
        return output.toByteArray()
    }
}

data class GenerateBatchReportCommand(
    val studioId: StudioId,
    val contractorId: BatchContractorId,
    val from: LocalDate?,
    val to: LocalDate?
)

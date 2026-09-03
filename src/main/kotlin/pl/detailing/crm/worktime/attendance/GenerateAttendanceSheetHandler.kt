package pl.detailing.crm.worktime.attendance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.EmployeeId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import pl.detailing.crm.worktime.formatMinutes
import pl.detailing.crm.worktime.infrastructure.PeriodStatus
import pl.detailing.crm.worktime.infrastructure.WorkTimeEntryRepository
import pl.detailing.crm.worktime.infrastructure.WorkTimePeriodRepository
import java.io.ByteArrayOutputStream
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * Lista obecności — arkusz na jeden miesiąc: kolumny to pracownicy, wiersze to
 * kolejne dni miesiąca.
 *
 * Rysowana PDFBox-em (tak jak zestawienie zbiorcze kontrahenta), ale w szacie
 * graficznej protokołu przyjęcia pojazdu: granatowe belki nagłówków (#111729),
 * szare pola (#EDEEEE), ten sam układ „belka + pole" i ta sama typografia —
 * Liberation Sans, czyli font, którym backend wypełnia pola protokołów.
 *
 * W komórkach stoją godziny z kart czasu pracy pracowników — to samo źródło, które
 * widzi moduł Czasu pracy. Dzień bez wpisu zostaje pusty (nieobecność albo dzień
 * jeszcze nieuzupełniony), a ostatni wiersz sumuje miesiąc per pracownik: bez sumy
 * arkusz nie odpowiada na jedyne pytanie, które się nad nim zadaje.
 */
@Service
class GenerateAttendanceSheetHandler(
    private val employeeRepository: EmployeeRepository,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val documentStorageService: DocumentStorageService,
    private val workTimeEntryRepository: WorkTimeEntryRepository,
    private val workTimePeriodRepository: WorkTimePeriodRepository
) {
    companion object {
        /** Tyle kolumn mieści się na stronie, żeby w komórce dało się złożyć podpis. */
        private const val MAX_EMPLOYEES_PER_PAGE = 7

        /** Kolory protokołu przyjęcia pojazdu (protokol_przyjecia_pojazdu.html). */
        private val NAVY = Triple(17f / 255f, 23f / 255f, 41f / 255f)      // #111729
        private val GRAY = Triple(237f / 255f, 238f / 255f, 238f / 255f)   // #EDEEEE
        private val INK = Triple(8f / 255f, 6f / 255f, 6f / 255f)          // #080606
        private val WHITE = Triple(1f, 1f, 1f)
        private val HAIRLINE = Triple(0.80f, 0.82f, 0.85f)

        private val POLISH: Locale = Locale.forLanguageTag("pl-PL")
        private val MONTH_FORMAT = DateTimeFormatter.ofPattern("LLLL yyyy", POLISH)
        private val GENERATED_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy", POLISH)
        private val WEEKDAYS = mapOf(
            DayOfWeek.MONDAY to "pn", DayOfWeek.TUESDAY to "wt", DayOfWeek.WEDNESDAY to "śr",
            DayOfWeek.THURSDAY to "cz", DayOfWeek.FRIDAY to "pt", DayOfWeek.SATURDAY to "sb",
            DayOfWeek.SUNDAY to "nd"
        )
    }

    /**
     * @param employeeIds pracownicy zaznaczeni na liście. Kolejność kolumn wynika
     *        z nazwiska, a nie z kolejności klikania — dwa wydruki tego samego
     *        miesiąca mają dać się porównać.
     */
    @Transactional(readOnly = true)
    suspend fun handle(command: GenerateAttendanceSheetCommand): ByteArray = withContext(Dispatchers.IO) {
        if (command.employeeIds.isEmpty()) {
            throw ValidationException("Zaznacz co najmniej jednego pracownika.")
        }

        val employees = command.employeeIds
            .distinct()
            .map { employeeId ->
                employeeRepository.findByIdAndStudioId(employeeId.value, command.studioId.value)
                    ?: throw ValidationException("Nie znaleziono pracownika o id: $employeeId")
            }
            .filter { hasWorkTimeModule(it.userId, command.studioId) }
            .sortedWith(compareBy({ it.lastName.lowercase(POLISH) }, { it.firstName.lowercase(POLISH) }))

        // Ta sama bramka co w interfejsie (przycisk zostaje nieaktywny), ale to backend
        // odpowiada za to, czego na liście nie widać: pracownik bez modułu Czasu pracy
        // nie prowadzi karty, więc jego kolumna byłaby pustym miejscem bez znaczenia.
        if (employees.isEmpty()) {
            throw ValidationException(
                "Żaden z zaznaczonych pracowników nie ma włączonego modułu Czasu pracy."
            )
        }

        val settings = studioSettingsRepository.findById(command.studioId.value).orElse(null)
        val logoBytes = settings?.logoS3Key?.let { key ->
            runCatching { documentStorageService.downloadBytes(key) }.getOrNull()
        }

        // Godziny biorą się z tych samych wpisów, które pracownik widzi w module
        // Czasu pracy — arkusz jest ich wydrukiem, a nie osobnym źródłem prawdy.
        val from = command.period.atDay(1)
        val to = command.period.atEndOfMonth()
        val columns = employees.map { employee ->
            val userId = employee.userId!!  // hasWorkTimeModule() przepuszcza tylko konta z rolą
            EmployeeColumn(
                name = "${employee.firstName} ${employee.lastName}",
                minutesByDay = workTimeEntryRepository
                    .findByUserIdAndStudioIdAndDateBetween(userId, command.studioId.value, from, to)
                    .associate { it.date to it.minutes },
                status = workTimePeriodRepository
                    .findByUserIdAndStudioIdAndPeriod(userId, command.studioId.value, command.period.toString())
                    ?.status
            )
        }

        buildPdf(
            period = command.period,
            columns = columns,
            studioName = settings?.name?.trim()?.ifBlank { null },
            logoBytes = logoBytes
        )
    }

    private fun hasWorkTimeModule(userId: UUID?, studioId: StudioId): Boolean {
        val user = userId?.let { userRepository.findByIdAndStudioId(it, studioId.value) } ?: return false
        if (user.isOwner) return false
        val roleId = user.customRoleId ?: return false
        return roleRepository.findByIdAndStudioId(roleId, studioId.value)?.trackWorkTime == true
    }

    // ── Rysowanie ─────────────────────────────────────────────────────────────

    private fun buildPdf(
        period: YearMonth,
        columns: List<EmployeeColumn>,
        studioName: String?,
        logoBytes: ByteArray?
    ): ByteArray {
        val document = PDDocument()

        val regular = PDType0Font.load(
            document,
            GenerateAttendanceSheetHandler::class.java.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")!!,
            true
        )
        val bold = PDType0Font.load(
            document,
            GenerateAttendanceSheetHandler::class.java.getResourceAsStream("/fonts/LiberationSans-Bold.ttf")!!,
            true
        )

        // Marginesy jak w protokole: treść od 30.24pt do 565.68pt na stronie A4.
        val pageWidth = PDRectangle.A4.width
        val pageHeight = PDRectangle.A4.height
        val left = 30.24f
        val right = pageWidth - 29.76f

        val chunks = columns.chunked(MAX_EMPLOYEES_PER_PAGE)
        chunks.forEachIndexed { index, pageColumns ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { cs ->
                drawPage(
                    cs = cs,
                    document = document,
                    regular = regular,
                    bold = bold,
                    period = period,
                    columns = pageColumns,
                    studioName = studioName,
                    logoBytes = logoBytes,
                    left = left,
                    right = right,
                    pageHeight = pageHeight,
                    pageNumber = index + 1,
                    pageCount = chunks.size,
                    employeeCount = columns.size
                )
            }
        }

        val output = ByteArrayOutputStream()
        document.save(output)
        document.close()
        return output.toByteArray()
    }

    private fun drawPage(
        cs: PDPageContentStream,
        document: PDDocument,
        regular: PDFont,
        bold: PDFont,
        period: YearMonth,
        columns: List<EmployeeColumn>,
        studioName: String?,
        logoBytes: ByteArray?,
        left: Float,
        right: Float,
        pageHeight: Float,
        pageNumber: Int,
        pageCount: Int,
        employeeCount: Int
    ) {
        val contentWidth = right - left
        var y = pageHeight - 22.32f

        // ── Nagłówek: logo studia po lewej, belka USŁUGODAWCA po prawej ──────
        val providerWidth = 126.48f
        val providerX = right - providerWidth
        if (logoBytes != null) {
            runCatching {
                val image = PDImageXObject.createFromByteArray(document, logoBytes, "logo")
                val maxW = 120f
                val maxH = 34f
                val aspect = image.width.toFloat() / image.height
                val drawW: Float
                val drawH: Float
                if (aspect > maxW / maxH) {
                    drawW = maxW; drawH = maxW / aspect
                } else {
                    drawW = maxH * aspect; drawH = maxH
                }
                cs.drawImage(image, left, y - drawH, drawW, drawH)
            }
        }
        drawTab(cs, bold, "USŁUGODAWCA", providerX, y - 13.92f, providerWidth)
        drawBox(cs, providerX, y - 34.62f, providerWidth, 18.42f)
        drawText(cs, studioName ?: "-", regular, 7f, providerX + 2f, y - 28.5f, INK, providerWidth - 4f)

        y -= 68.65f

        // ── Tytuł: granatowy akcent od krawędzi strony + nagłówek ────────────
        cs.setNonStrokingColor(NAVY.first, NAVY.second, NAVY.third)
        cs.addRect(0f, y - 13.92f, 30.48f, 13.92f)
        cs.fill()
        drawText(cs, "LISTA OBECNOŚCI", bold, 15f, left + 5.52f, y - 11f, NAVY)

        y -= 33f

        // ── Metryka: miesiąc / liczba pracowników / data wygenerowania ───────
        val metaGap = 12f
        val metaWidth = (contentWidth - 2 * metaGap) / 3f
        val metaTop = y
        listOf(
            "MIESIĄC" to period.format(MONTH_FORMAT).replaceFirstChar { it.titlecase(POLISH) },
            "LICZBA PRACOWNIKÓW" to employeeCount.toString(),
            "DATA WYGENEROWANIA" to LocalDate.now().format(GENERATED_FORMAT)
        ).forEachIndexed { index, entry ->
            val x = left + index * (metaWidth + metaGap)
            drawTab(cs, bold, entry.first, x, metaTop - 13.92f, metaWidth)
            drawBox(cs, x, metaTop - 34.89f, metaWidth, 18.42f)
            drawText(cs, entry.second, regular, 7f, x + 2f, metaTop - 28.77f, INK, metaWidth - 4f)
        }

        y = metaTop - 34.89f - 18f

        // ── Tabela: wiersze = dni miesiąca, kolumny = pracownicy ─────────────
        val dayColWidth = 62f
        val employeeColWidth = (contentWidth - dayColWidth) / columns.size
        val headerHeight = 30f
        val daysInMonth = period.lengthOfMonth()

        // Wysokość wiersza liczona z tego, co zostało: arkusz ma się zmieścić na
        // jednej stronie niezależnie od tego, czy miesiąc ma 28 czy 31 dni.
        // Wiersz sumy liczy się do wysokości tak samo jak dzień.
        val bottomLimit = 64f
        val available = y - headerHeight - bottomLimit
        val rowHeight = (available / (daysInMonth + 1)).coerceIn(13f, 22f)

        // Nagłówek tabeli — granatowa belka na całą szerokość, jak `.tab` w protokole.
        cs.setNonStrokingColor(NAVY.first, NAVY.second, NAVY.third)
        cs.addRect(left, y - headerHeight, contentWidth, headerHeight)
        cs.fill()
        drawText(cs, "DZIEŃ", bold, 8f, left + 6f, y - headerHeight / 2f - 3f, WHITE)

        columns.forEachIndexed { index, column ->
            val x = left + dayColWidth + index * employeeColWidth
            val lines = splitName(column.name, bold, 7.5f, employeeColWidth - 8f)
            // Dwie linie (imię / nazwisko) wyśrodkowane w pionie w belce nagłówka.
            val firstLineY = y - headerHeight / 2f - 3f + (lines.size - 1) * 4.5f
            lines.forEachIndexed { lineIndex, line ->
                drawText(
                    cs, line, bold, 7.5f,
                    x + centeringOffset(line, bold, 7.5f, employeeColWidth),
                    firstLineY - lineIndex * 9f,
                    WHITE, employeeColWidth - 4f
                )
            }
        }

        var rowY = y - headerHeight
        for (day in 1..daysInMonth) {
            val date = period.atDay(day)
            val isWeekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

            // Weekend na szaro — tym samym szarym, którym protokół oznacza pola formularza.
            if (isWeekend) {
                cs.setNonStrokingColor(GRAY.first, GRAY.second, GRAY.third)
                cs.addRect(left, rowY - rowHeight, contentWidth, rowHeight)
                cs.fill()
            }

            val label = "%02d %s".format(day, WEEKDAYS[date.dayOfWeek])
            val textY = rowY - rowHeight / 2f - 3f
            drawText(cs, label, if (isWeekend) bold else regular, 8f, left + 6f, textY, INK)

            // Godziny z karty czasu pracy. Dzień bez wpisu zostaje pusty — zero
            // wpisane w każdą kratkę zamieniłoby nieobecność w twierdzenie.
            columns.forEachIndexed { index, column ->
                val minutes = column.minutesByDay[date] ?: return@forEachIndexed
                val text = formatMinutes(minutes)
                val x = left + dayColWidth + index * employeeColWidth
                drawText(
                    cs, text, regular, 8f,
                    x + centeringOffset(text, regular, 8f, employeeColWidth), textY,
                    INK, employeeColWidth - 4f
                )
            }

            cs.setStrokingColor(HAIRLINE.first, HAIRLINE.second, HAIRLINE.third)
            cs.setLineWidth(0.5f)
            cs.moveTo(left, rowY - rowHeight)
            cs.lineTo(right, rowY - rowHeight)
            cs.stroke()

            rowY -= rowHeight
        }

        // ── Wiersz sumy — po to się tę listę drukuje ─────────────────────────
        cs.setNonStrokingColor(GRAY.first, GRAY.second, GRAY.third)
        cs.addRect(left, rowY - rowHeight, contentWidth, rowHeight)
        cs.fill()
        val totalsY = rowY - rowHeight / 2f - 3f
        drawText(cs, "RAZEM", bold, 8f, left + 6f, totalsY, INK)
        columns.forEachIndexed { index, column ->
            val text = formatMinutes(column.totalMinutes)
            val x = left + dayColWidth + index * employeeColWidth
            drawText(
                cs, text, bold, 8f,
                x + centeringOffset(text, bold, 8f, employeeColWidth), totalsY,
                INK, employeeColWidth - 4f
            )
        }
        cs.setStrokingColor(HAIRLINE.first, HAIRLINE.second, HAIRLINE.third)
        cs.setLineWidth(0.5f)
        cs.moveTo(left, rowY - rowHeight); cs.lineTo(right, rowY - rowHeight); cs.stroke()
        rowY -= rowHeight

        // Pionowe linie kolumn — po wierszach, żeby przecinały każdy z nich.
        val tableBottom = rowY
        cs.setStrokingColor(HAIRLINE.first, HAIRLINE.second, HAIRLINE.third)
        cs.setLineWidth(0.5f)
        for (index in 0..columns.size) {
            val x = left + dayColWidth + index * employeeColWidth
            cs.moveTo(x, y - headerHeight)
            cs.lineTo(x, tableBottom)
            cs.stroke()
        }
        cs.moveTo(left, y - headerHeight); cs.lineTo(left, tableBottom); cs.stroke()
        cs.moveTo(right, y - headerHeight); cs.lineTo(right, tableBottom); cs.stroke()

        // ── Stopka: stan kart, podpis osoby potwierdzającej, numeracja stron ─
        // Bez tej linii nie wiadomo, czy godziny są zatwierdzone, czy dopiero wpisane
        // przez pracownika — a to decyduje, czy arkusz nadaje się pod podpis.
        val statuses = columns.mapNotNull { it.status }.toSet()
        val statusLabel = when {
            columns.all { it.status == PeriodStatus.APPROVED } -> "Karty czasu pracy: zatwierdzone"
            statuses.isEmpty() -> "Karty czasu pracy: brak wpisów za ten miesiąc"
            else -> "Karty czasu pracy: " + columns.joinToString(", ") {
                it.name + " — " + describeStatus(it.status)
            }
        }
        drawText(cs, statusLabel, regular, 7f, left, tableBottom - 12f, INK, right - left)

        val footerY = tableBottom - 30f
        drawText(cs, "Podpis osoby potwierdzającej:", regular, 8f, left, footerY, INK)
        cs.setStrokingColor(HAIRLINE.first, HAIRLINE.second, HAIRLINE.third)
        cs.moveTo(left + 132f, footerY - 2f)
        cs.lineTo(left + 302f, footerY - 2f)
        cs.stroke()

        if (pageCount > 1) {
            val pageLabel = "Strona $pageNumber z $pageCount"
            drawText(cs, pageLabel, regular, 8f, right - textWidth(pageLabel, regular, 8f), footerY, INK)
        }
    }

    // ── Prymitywy rysowania ───────────────────────────────────────────────────

    /** Granatowa belka nagłówka z wyśrodkowanym białym napisem — `.tab` z protokołu. */
    private fun drawTab(cs: PDPageContentStream, font: PDFont, text: String, x: Float, y: Float, width: Float) {
        cs.setNonStrokingColor(NAVY.first, NAVY.second, NAVY.third)
        cs.addRect(x, y, width, 13.92f)
        cs.fill()
        drawText(cs, text, font, 9f, x + centeringOffset(text, font, 9f, width), y + 4.2f, WHITE, width - 4f)
    }

    /** Szare pole formularza — `.box` z protokołu. */
    private fun drawBox(cs: PDPageContentStream, x: Float, y: Float, width: Float, height: Float) {
        cs.setNonStrokingColor(GRAY.first, GRAY.second, GRAY.third)
        cs.addRect(x, y, width, height)
        cs.fill()
    }

    private fun drawText(
        cs: PDPageContentStream,
        text: String,
        font: PDFont,
        size: Float,
        x: Float,
        y: Float,
        color: Triple<Float, Float, Float>,
        maxWidth: Float? = null
    ) {
        val value = if (maxWidth != null) truncate(text, font, size, maxWidth) else text
        if (value.isEmpty()) return
        cs.setNonStrokingColor(color.first, color.second, color.third)
        cs.beginText()
        cs.setFont(font, size)
        cs.newLineAtOffset(x, y)
        cs.showText(value)
        cs.endText()
    }

    private fun textWidth(text: String, font: PDFont, size: Float): Float =
        runCatching { font.getStringWidth(text) / 1000f * size }.getOrDefault(text.length * size * 0.5f)

    private fun centeringOffset(text: String, font: PDFont, size: Float, width: Float): Float =
        ((width - textWidth(text, font, size)) / 2f).coerceAtLeast(2f)

    private fun truncate(text: String, font: PDFont, size: Float, maxWidth: Float): String {
        if (textWidth(text, font, size) <= maxWidth) return text
        var result = text
        while (result.isNotEmpty() && textWidth("$result…", font, size) > maxWidth) result = result.dropLast(1)
        return if (result.isEmpty()) "" else "$result…"
    }

    /**
     * Nagłówek kolumny łamie się na imię i nazwisko, bo kolumna jest wąska —
     * ucięte nazwisko czyni kolumnę bezużyteczną przy podpisywaniu.
     */
    private fun describeStatus(status: PeriodStatus?): String = when (status) {
        PeriodStatus.APPROVED -> "zatwierdzona"
        PeriodStatus.SUBMITTED -> "złożona, niezatwierdzona"
        PeriodStatus.RETURNED -> "zwrócona do poprawy"
        PeriodStatus.DRAFT -> "w trakcie uzupełniania"
        null -> "brak wpisów"
    }

    private fun splitName(name: String, font: PDFont, size: Float, maxWidth: Float): List<String> {
        if (textWidth(name, font, size) <= maxWidth) return listOf(name)
        val parts = name.split(" ", limit = 2)
        if (parts.size < 2) return listOf(truncate(name, font, size, maxWidth))
        return listOf(
            truncate(parts[0], font, size, maxWidth),
            truncate(parts[1], font, size, maxWidth)
        )
    }
}

/**
 * Kolumna arkusza: pracownik razem z jego kartą czasu pracy za wybrany miesiąc.
 */
private data class EmployeeColumn(
    val name: String,
    val minutesByDay: Map<LocalDate, Int>,
    val status: PeriodStatus?
) {
    val totalMinutes: Int get() = minutesByDay.values.sum()
}

data class GenerateAttendanceSheetCommand(
    val studioId: StudioId,
    val period: YearMonth,
    val employeeIds: List<EmployeeId>
)

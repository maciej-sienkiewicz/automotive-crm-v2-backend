package pl.detailing.crm.ownerreport.domain

/**
 * Mediana liczb raportu z kilku okresów — punkt odniesienia „typowy tydzień".
 *
 * Mediana, nie średnia: jeden tydzień z dużym zleceniem flotowym albo jeden
 * tydzień urlopu przesunąłby średnią tak, że każdy zwykły tydzień wyglądałby
 * jak spadek albo wzrost. Mediana tego nie widzi.
 *
 * Każda liczba osobno: mediana sprzedaży i mediana liczby wizyt mogą pochodzić
 * z różnych tygodni. To zamierzone — to są dwa osobne punkty odniesienia, a nie
 * „tydzień wzorcowy".
 *
 * Kwota mediany nie jest ceną wpisaną przez człowieka, tylko statystyką; przy
 * parzystej liczbie okresów to średnia dwóch środkowych, zaokrąglona do grosza.
 */
object MetricsMedian {

    fun of(periods: List<PeriodMetrics>): PeriodMetrics {
        require(periods.isNotEmpty()) { "Mediana z pustej listy okresów" }
        fun int(select: (PeriodMetrics) -> Int) = median(periods.map { select(it).toLong() })!!.toInt()
        fun long(select: (PeriodMetrics) -> Long) = median(periods.map(select))!!

        val instagram = periods.mapNotNull { it.instagram }
        return PeriodMetrics(
            visitsStarted = int { it.visitsStarted },
            reservationsCreated = int { it.reservationsCreated },
            closed = ClosedVisits(
                count = int { it.closed.count },
                grossCents = long { it.closed.grossCents },
                netCents = long { it.closed.netCents }
            ),
            costsNetCents = long { it.costsNetCents },
            emails = EmailMetrics(
                sentByTeam = int { it.emails.sentByTeam },
                sentAutomated = int { it.emails.sentAutomated },
                replies = ReplyTimes(
                    inquiries = int { it.emails.replies.inquiries },
                    answered = int { it.emails.replies.answered },
                    answeredWithinHour = int { it.emails.replies.answeredWithinHour },
                    medianMinutes = median(periods.mapNotNull { it.emails.replies.medianMinutes })
                )
            ),
            upsell = UpsellMetrics(
                suggested = int { it.upsell.suggested },
                suggestedGrossCents = long { it.upsell.suggestedGrossCents },
                visitsWithSuggestions = int { it.upsell.visitsWithSuggestions },
                accepted = int { it.upsell.accepted },
                acceptedGrossCents = long { it.upsell.acceptedGrossCents }
            ),
            visitCardsSent = int { it.visitCardsSent },
            batch = BatchMetrics(
                vehicles = int { it.batch.vehicles },
                grossCents = long { it.batch.grossCents },
                contractors = int { it.batch.contractors }
            ),
            // Profil wskazany dopiero niedawno: mediana z okresów, w których był.
            instagram = if (instagram.isEmpty()) null else InstagramMetrics(
                posts = median(instagram.map { it.posts.toLong() })!!.toInt(),
                likes = median(instagram.map { it.likes })!!,
                comments = median(instagram.map { it.comments })!!
            )
        )
    }

    /** Null dla pustej listy. Przy parzystej liczbie: średnia dwóch środkowych, połówka w górę. */
    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else Math.floorDiv(sorted[mid - 1] + sorted[mid] + 1, 2L)
    }
}

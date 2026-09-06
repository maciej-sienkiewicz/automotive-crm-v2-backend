package pl.detailing.crm.instagram.ads

/**
 * Zamiana list na tekst w kolumnie i z powrotem.
 *
 * Lokalizacje i rozbicie zasięgu czytamy zawsze w całości i nigdy po nich nie
 * filtrujemy, więc osobne tabele byłyby kosztem bez zwrotu. Separatory usuwamy
 * z wartości przy zapisie — nazwa lokalizacji przychodzi od Meta i nie mamy
 * gwarancji, że nie zawiera średnika.
 */
object MetaAdCodec {

    private const val ROW = "|"
    private const val FIELD = ";"

    fun encodeLocations(locations: List<RawAdLocation>): String =
        locations.joinToString(ROW) { loc ->
            listOf(clean(loc.name), clean(loc.type), if (loc.excluded) "1" else "0").joinToString(FIELD)
        }

    fun decodeLocations(raw: String): List<RawAdLocation> =
        raw.split(ROW)
            .filter { it.isNotBlank() }
            .mapNotNull { row ->
                val parts = row.split(FIELD)
                if (parts.size < 3) return@mapNotNull null
                RawAdLocation(name = parts[0], type = parts[1], excluded = parts[2] == "1")
            }

    fun encodeBreakdown(buckets: List<RawAgeGenderReach>): String =
        buckets.joinToString(ROW) { b ->
            listOf(clean(b.ageRange), b.male.toString(), b.female.toString(), b.unknown.toString())
                .joinToString(FIELD)
        }

    fun decodeBreakdown(raw: String): List<RawAgeGenderReach> =
        raw.split(ROW)
            .filter { it.isNotBlank() }
            .mapNotNull { row ->
                val parts = row.split(FIELD)
                if (parts.size < 4) return@mapNotNull null
                RawAgeGenderReach(
                    ageRange = parts[0],
                    male = parts[1].toIntOrNull() ?: 0,
                    female = parts[2].toIntOrNull() ?: 0,
                    unknown = parts[3].toIntOrNull() ?: 0
                )
            }

    fun encodePlatforms(platforms: List<String>): String =
        platforms.map { it.trim().uppercase() }.filter { it.isNotBlank() }.distinct().joinToString(",")

    fun decodePlatforms(raw: String): List<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotBlank() }

    private fun clean(value: String): String =
        value.replace(ROW, " ").replace(FIELD, " ").trim()
}

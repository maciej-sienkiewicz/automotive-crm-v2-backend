package pl.detailing.crm.livemetrics.prometheus

import pl.detailing.crm.studio.reset.S3StudioPurger
import java.util.UUID

/**
 * Do kogo należy obiekt w buckecie — odczytane WYŁĄCZNIE z klucza.
 *
 * Układ kluczy jest ten sam, który czyści [S3StudioPurger.prefixesOf]:
 *
 *  - `{studioId}/{obszar}/…`         → studio, obszar = drugi segment (visits, protocols, logo…),
 *  - `thumbs/{studioId}/…`           → studio, obszar `thumbs`,
 *  - `temp/{studioId}/…`             → studio, obszar `temp` (sesje zdjęć),
 *  - `temp/uploads/{studioId}/…`     → studio, obszar `temp` (zdjęcia z telefonu przy przyjęciu),
 *  - wszystko inne                   → brak studia (np. wspólne ikony podpisu maila).
 *
 * Obiekt bez studia nie ginie: trafia do puli wspólnej, więc suma per tenant plus pula
 * wspólna zawsze daje cały bucket. Gdyby zgubić go po cichu, dashboard pokazywałby mniej,
 * niż płacimy.
 *
 * Obszar jest etykietą Prometheusa, więc jego zbiór musi być zamknięty: segment spoza
 * wzorca (np. nazwa pliku leżącego wprost pod `{studioId}/`) ląduje w `other`, zamiast
 * tworzyć serię na każdy plik.
 */
data class StorageKeyOwner(val studioId: UUID?, val area: String) {

    companion object {
        const val OTHER = "other"
        const val THUMBS = "thumbs"
        const val TEMP = "temp"

        private val UUID_PATTERN =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val AREA_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{0,39}$")

        fun of(key: String): StorageKeyOwner {
            val s = key.split('/')
            uuid(s[0])?.let { return StorageKeyOwner(it, if (s.size > 2) area(s[1]) else OTHER) }
            if (s[0] == THUMBS && s.size > 2) uuid(s[1])?.let { return StorageKeyOwner(it, THUMBS) }
            if (s[0] == TEMP && s.size > 2) {
                uuid(s[1])?.let { return StorageKeyOwner(it, TEMP) }
                if (s[1] == "uploads" && s.size > 3) uuid(s[2])?.let { return StorageKeyOwner(it, TEMP) }
            }
            return StorageKeyOwner(null, if (s.size > 1) area(s[0]) else OTHER)
        }

        private fun uuid(segment: String): UUID? =
            if (UUID_PATTERN.matches(segment)) UUID.fromString(segment) else null

        private fun area(segment: String): String =
            if (AREA_PATTERN.matches(segment)) segment else OTHER
    }
}

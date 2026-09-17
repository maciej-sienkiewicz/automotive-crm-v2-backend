package pl.detailing.crm.product.domain

/**
 * Słownik pojęć modułu produktów. Trzymamy je razem, bo są małe i czyta się je
 * naraz — wzorzec z `subscription/entitlement/domain/EntitlementDomain.kt`.
 *
 * Cały moduł jest ŚWIADOMIE informacyjny: produkt nie liczy się do żadnej
 * statystyki, nie ma zużycia ani kosztu materiału. „Do tej wizyty użyliśmy tego
 * produktu" to jedyne, co niesie relacja z wizytą. Cena jednostkowa istnieje
 * wyłącznie jako notatka handlowa studia (nakładka `product_studio`), nigdy jako
 * podstawa wyliczeń — dlatego w tym module nie ma ani jednego mnożenia VAT.
 */

/** Jednostka, w której studio myśli o produkcie. Wielkość opakowania nosi tę samą. */
enum class UnitOfMeasure(val displayName: String) {
    ML("ml"),
    L("l"),
    G("g"),
    KG("kg"),
    PIECE("szt."),
    PAIR("para"),
    M("m"),
    M2("m²");

    companion object {
        fun fromCode(code: String?): UnitOfMeasure? =
            entries.find { it.name.equals(code?.trim(), ignoreCase = true) }
    }
}

/** Skąd wzięły się dane w globalnym wierszu katalogu. */
enum class ProductSource {
    MANUAL,   // wpisał człowiek
    AI,       // model językowy + weryfikator
    GS1,      // rejestr GS1 / GEPIR
    CURATED   // moderacja platformy
}

/**
 * Klasa zaufania do danych globalnych. Rośnie WYŁĄCZNIE przez niezależne
 * potwierdzenie — nic nie awansuje samo z upływem czasu ani przez samo użycie.
 *
 * Kolejność deklaracji = porządek zaufania; [atLeast] korzysta z `ordinal`.
 */
enum class VerificationLevel {
    UNVERIFIED,        // wpis w pełni ręczny
    AI_SUGGESTED,      // odczyt modelu, przeszedł weryfikatora — WYMAGA sprawdzenia etykiety
    GS1_VERIFIED,      // rejestr GS1
    STUDIO_CONFIRMED,  // człowiek potwierdził zgodność z etykietą
    CURATED;           // moderacja platformy

    fun atLeast(other: VerificationLevel): Boolean = ordinal >= other.ordinal

    /** Poziomy, których zwykły PATCH nie edytuje in-place (patrz governance §2.4 architektury). */
    val isProtected: Boolean
        get() = this == GS1_VERIFIED || this == STUDIO_CONFIRMED || this == CURATED
}

/**
 * Gabaryty fizyczne opakowania w milimetrach — do planowania półki, opcjonalne.
 * NIE mylić z wielkością opakowania (ile jest w środku), która decyduje o tym,
 * jak studio o produkcie myśli, i siedzi w [ProductSpec.packageSizeValue].
 */
data class PackageDimensions(
    val heightMm: Int?,
    val widthMm: Int?,
    val depthMm: Int?
) {
    val isEmpty: Boolean get() = heightMm == null && widthMm == null && depthMm == null

    companion object {
        val EMPTY = PackageDimensions(null, null, null)
    }
}

/** Skąd przyszły dane i jak bardzo im ufamy — jeden pakiet, przechodzi przez granice. */
data class Provenance(
    val source: ProductSource,
    val verificationLevel: VerificationLevel,
    /** 0.0–1.0; wypełnione TYLKO dla [ProductSource.AI], w pozostałych null. */
    val confidence: Double?
)

/**
 * Specyfikacja produktu — to, co jest na etykiecie i co wolno współdzielić między
 * wszystkimi tenantami. Bez ceny, bez notatek, bez oceny (te są prywatne studia).
 */
data class ProductSpec(
    val gtin: String?,
    val name: String,
    val brand: String,
    val manufacturerName: String,
    val unitOfMeasure: UnitOfMeasure,
    val packageSizeValue: java.math.BigDecimal,
    val packageSizeUnit: UnitOfMeasure,
    val dimensions: PackageDimensions,
    val description: String?,
    val imageFileId: String?
)

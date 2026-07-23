package pl.detailing.crm.visit.domain

/**
 * Represents a damage point on a vehicle schematic
 *
 * Coordinates are stored as percentages (0.0 - 100.0) of the image dimensions,
 * making them resolution-independent and easily translatable to absolute pixel positions.
 *
 * A damage point can carry photos documenting the damage. Each photo may have
 * freehand annotation strokes drawn on it (also in percentage coordinates of the
 * photo), used to point at the exact spot of the damage.
 */
data class DamagePoint(
    val id: Int,
    val x: Double,
    val y: Double,
    val note: String?,
    val photos: List<DamagePhoto> = emptyList()
) {
    init {
        require(x in 0.0..100.0) { "X coordinate must be between 0 and 100, got: $x" }
        require(y in 0.0..100.0) { "Y coordinate must be between 0 and 100, got: $y" }
        require(id > 0) { "ID must be positive, got: $id" }
    }
}

/**
 * A photo attached to a damage point.
 *
 * @param photoId  ID of the uploaded photo (QR checkin photo or photo-session photo)
 * @param strokes  freehand annotation strokes drawn over the photo
 */
data class DamagePhoto(
    val photoId: String,
    val strokes: List<DamageAnnotationStroke> = emptyList()
)

/**
 * A single freehand stroke drawn over a damage photo.
 *
 * @param color  hex color, e.g. "#EF4444"
 * @param width  stroke width as a percentage of the photo width (e.g. 1.0)
 * @param points polyline points in percentage coordinates (0.0 - 100.0) of the photo
 */
data class DamageAnnotationStroke(
    val color: String,
    val width: Double,
    val points: List<DamageAnnotationPoint>
) {
    init {
        require(width in 0.0..100.0) { "Stroke width must be between 0 and 100, got: $width" }
    }
}

data class DamageAnnotationPoint(
    val x: Double,
    val y: Double
) {
    init {
        require(x in 0.0..100.0) { "Annotation X must be between 0 and 100, got: $x" }
        require(y in 0.0..100.0) { "Annotation Y must be between 0 and 100, got: $y" }
    }
}

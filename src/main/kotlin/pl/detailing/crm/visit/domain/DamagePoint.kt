package pl.detailing.crm.visit.domain

/**
 * Represents a damage point on a vehicle schematic
 *
 * Coordinates are stored as percentages (0.0 - 100.0) of the image dimensions,
 * making them resolution-independent and easily translatable to absolute pixel positions.
 */
data class DamagePoint(
    val id: Int,
    val x: Double,
    val y: Double,
    val note: String?
) {
    init {
        require(x in 0.0..100.0) { "X coordinate must be between 0 and 100, got: $x" }
        require(y in 0.0..100.0) { "Y coordinate must be between 0 and 100, got: $y" }
        require(id > 0) { "ID must be positive, got: $id" }
    }
}

package pl.detailing.crm.gallery

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * SQL-side gallery query: filtering, sorting and pagination all happen in the
 * database, so the cost of a request is proportional to the page size instead
 * of the total number of photos in the studio.
 *
 * The three photo sources (visit, vehicle, batch order) are combined with
 * UNION ALL; tag AND-filtering is pushed into per-branch subqueries against
 * photo_tags, and brand/model filters are applied per branch on the columns
 * each source stores them in.
 */
@Repository
class GalleryPhotoQueryDao(
    private val jdbcTemplate: NamedParameterJdbcTemplate
) {

    data class GalleryPhotoRow(
        val photoId: UUID,
        val source: GalleryPhotoSource,
        val fileId: String,
        val thumbnailFileId: String?,
        val fileName: String,
        val description: String?,
        val uploadedAt: Instant,
        val uploadedBy: UUID?,
        val uploadedByName: String?,
        val vehicleId: UUID?,
        val vehicleBrand: String?,
        val vehicleModel: String?,
        val vehicleLicensePlate: String?,
        val vehicleYear: Int?,
        val visitId: UUID?,
        val visitNumber: String?,
        val customerId: UUID?,
        val batchOrderEntryId: UUID?,
        val contractorName: String?
    )

    companion object {
        // Column list is identical across the three UNION ALL branches.
        private val UNION_SQL = """
            SELECT vp.id AS photo_id, 'VISIT' AS source, vp.file_id, vp.thumbnail_file_id,
                   vp.file_name, vp.description, vp.uploaded_at, vp.uploaded_by, vp.uploaded_by_name,
                   v.vehicle_id, v.brand_snapshot AS vehicle_brand, v.model_snapshot AS vehicle_model,
                   v.license_plate_snapshot AS vehicle_license_plate,
                   v.year_of_production_snapshot AS vehicle_year,
                   v.id AS visit_id, v.visit_number, v.customer_id,
                   NULL::uuid AS batch_order_entry_id, NULL::varchar AS contractor_name
            FROM visit_photos vp
            JOIN visits v ON v.id = vp.visit_id
            WHERE v.studio_id = :studioId
              AND v.status <> 'DRAFT'
              AND v.deleted_at IS NULL
              AND (cast(:brand AS text) IS NULL OR lower(v.brand_snapshot) = lower(cast(:brand AS text)))
              AND (cast(:model AS text) IS NULL OR lower(v.model_snapshot) = lower(cast(:model AS text)))
              AND (:tagCount = 0 OR vp.id IN (
                    SELECT pt.photo_id FROM photo_tags pt
                    WHERE pt.studio_id = :studioId AND pt.photo_type = 'VISIT_PHOTO' AND pt.tag_name IN (:tags)
                    GROUP BY pt.photo_id HAVING COUNT(DISTINCT pt.tag_name) = :tagCount))

            UNION ALL

            SELECT p.id, 'VEHICLE', p.file_id, p.thumbnail_file_id,
                   p.file_name, p.description, p.uploaded_at, p.uploaded_by, p.uploaded_by_name,
                   veh.id, veh.brand, veh.model, veh.license_plate, veh.year_of_production,
                   NULL::uuid, NULL::varchar, NULL::uuid,
                   NULL::uuid, NULL::varchar
            FROM vehicle_photos p
            JOIN vehicles veh ON veh.id = p.vehicle_id
            WHERE veh.studio_id = :studioId
              AND (cast(:brand AS text) IS NULL OR lower(veh.brand) = lower(cast(:brand AS text)))
              AND (cast(:model AS text) IS NULL OR lower(veh.model) = lower(cast(:model AS text)))
              AND (:tagCount = 0 OR p.id IN (
                    SELECT pt.photo_id FROM photo_tags pt
                    WHERE pt.studio_id = :studioId AND pt.photo_type = 'VEHICLE_PHOTO' AND pt.tag_name IN (:tags)
                    GROUP BY pt.photo_id HAVING COUNT(DISTINCT pt.tag_name) = :tagCount))

            UNION ALL

            SELECT bp.id, 'BATCH_ORDER', bp.file_id, bp.thumbnail_file_id,
                   bp.file_name, bp.description, bp.uploaded_at, bp.uploaded_by, bp.uploaded_by_name,
                   NULL::uuid, e.vehicle_make, e.vehicle_model, e.vehicle_license_plate, NULL::int,
                   NULL::uuid, NULL::varchar, NULL::uuid,
                   bp.entry_id, c.name
            FROM batch_order_entry_photos bp
            LEFT JOIN batch_order_entries e ON e.id = bp.entry_id
            LEFT JOIN batch_contractors c ON c.id = bp.contractor_id
            WHERE bp.studio_id = :studioId
              AND (cast(:brand AS text) IS NULL OR lower(e.vehicle_make) = lower(cast(:brand AS text)))
              AND (cast(:model AS text) IS NULL OR lower(e.vehicle_model) = lower(cast(:model AS text)))
              AND (:tagCount = 0 OR bp.id IN (
                    SELECT pt.photo_id FROM photo_tags pt
                    WHERE pt.studio_id = :studioId AND pt.photo_type = 'BATCH_ORDER_PHOTO' AND pt.tag_name IN (:tags)
                    GROUP BY pt.photo_id HAVING COUNT(DISTINCT pt.tag_name) = :tagCount))
        """
    }

    fun countPhotos(
        studioId: UUID,
        tags: List<String>,
        brand: String?,
        model: String?
    ): Int = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM ($UNION_SQL) g",
        params(studioId, tags, brand, model),
        Int::class.java
    ) ?: 0

    fun findPage(
        studioId: UUID,
        tags: List<String>,
        brand: String?,
        model: String?,
        sortOrder: GallerySortOrder,
        limit: Int,
        offset: Int
    ): List<GalleryPhotoRow> {
        // Sort direction is interpolated from a validated enum, never from user input.
        val direction = if (sortOrder == GallerySortOrder.ASC) "ASC" else "DESC"
        val sql = "SELECT * FROM ($UNION_SQL) g ORDER BY g.uploaded_at $direction, g.photo_id LIMIT :limit OFFSET :offset"

        val parameters = params(studioId, tags, brand, model)
            .addValue("limit", limit)
            .addValue("offset", offset)

        return jdbcTemplate.query(sql, parameters) { rs, _ ->
            GalleryPhotoRow(
                photoId = rs.getObject("photo_id", UUID::class.java),
                source = GalleryPhotoSource.valueOf(rs.getString("source")),
                fileId = rs.getString("file_id"),
                thumbnailFileId = rs.getString("thumbnail_file_id"),
                fileName = rs.getString("file_name"),
                description = rs.getString("description"),
                uploadedAt = rs.getTimestamp("uploaded_at").toInstant(),
                uploadedBy = rs.getObject("uploaded_by", UUID::class.java),
                uploadedByName = rs.getString("uploaded_by_name"),
                vehicleId = rs.getObject("vehicle_id", UUID::class.java),
                vehicleBrand = rs.getString("vehicle_brand"),
                vehicleModel = rs.getString("vehicle_model"),
                vehicleLicensePlate = rs.getString("vehicle_license_plate"),
                vehicleYear = rs.getObject("vehicle_year")?.let { (it as Number).toInt() },
                visitId = rs.getObject("visit_id", UUID::class.java),
                visitNumber = rs.getString("visit_number"),
                customerId = rs.getObject("customer_id", UUID::class.java),
                batchOrderEntryId = rs.getObject("batch_order_entry_id", UUID::class.java),
                contractorName = rs.getString("contractor_name")
            )
        }
    }

    private fun params(
        studioId: UUID,
        tags: List<String>,
        brand: String?,
        model: String?
    ): MapSqlParameterSource = MapSqlParameterSource()
        .addValue("studioId", studioId)
        .addValue("brand", brand)
        .addValue("model", model)
        // IN (:tags) must never be empty — when no tag filter is active the
        // :tagCount = 0 guard disables the subquery entirely.
        .addValue("tags", tags.ifEmpty { listOf("") })
        .addValue("tagCount", tags.size)
}

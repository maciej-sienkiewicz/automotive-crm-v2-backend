package pl.detailing.crm.role.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.domain.PermissionHierarchy
import pl.detailing.crm.role.domain.Role
import pl.detailing.crm.shared.RoleId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "studio_roles",
    indexes = [
        Index(name = "idx_studio_roles_studio_id", columnList = "studio_id"),
        Index(name = "idx_studio_roles_studio_name", columnList = "studio_id, name", unique = true)
    ]
)
class RoleEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "description", length = 500)
    var description: String?,

    // Stored as raw codes (not @Enumerated) so rows written before the catalog became a
    // tree don't break entity loading; Permission.fromStoredCode maps legacy codes on read.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "role_permissions",
        joinColumns = [JoinColumn(name = "role_id")],
        indexes = [Index(name = "idx_role_permissions_role_id", columnList = "role_id")]
    )
    @Column(name = "permission", nullable = false, length = 100)
    var permissions: MutableSet<String>,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): Role = Role(
        id = RoleId(id),
        studioId = StudioId(studioId),
        name = name,
        description = description,
        // Legacy codes are remapped and the set re-closed over the tree, so roles saved
        // under the old flat catalog stay internally consistent without a data migration.
        permissions = PermissionHierarchy.close(
            permissions.mapNotNull { Permission.fromStoredCode(it) }.toSet()
        ),
        createdBy = UserId(createdBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(role: Role): RoleEntity = RoleEntity(
            id = role.id.value,
            studioId = role.studioId.value,
            name = role.name,
            description = role.description,
            permissions = role.permissions.map { it.name }.toMutableSet(),
            createdBy = role.createdBy.value,
            createdAt = role.createdAt,
            updatedAt = role.updatedAt
        )
    }
}

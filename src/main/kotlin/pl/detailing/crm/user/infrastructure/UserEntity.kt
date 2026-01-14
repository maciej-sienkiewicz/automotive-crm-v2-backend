package pl.detailing.crm.user.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.user.domain.User
import java.time.Instant
import java.util.UUID


@Entity
@Table(
    name = "users",
    indexes = [
        Index(name = "idx_users_studio_email", columnList = "studio_id, email", unique = true),
        Index(name = "idx_users_studio_id", columnList = "studio_id"),
        Index(name = "idx_users_email", columnList = "email")
    ]
)
class UserEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "email", nullable = false, length = 255)
    var email: String,

    @Column(name = "password_hash", nullable = false, length = 255)
    var passwordHash: String,

    @Column(name = "first_name", nullable = false, length = 100)
    var firstName: String,

    @Column(name = "last_name", nullable = false, length = 100)
    var lastName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    var role: UserRole,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(): User = User(
        id = UserId(id),
        studioId = StudioId(studioId),
        email = email,
        passwordHash = passwordHash,
        firstName = firstName,
        lastName = lastName,
        role = role,
        isActive = isActive,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(user: User): UserEntity = UserEntity(
            id = user.id.value,
            studioId = user.studioId.value,
            email = user.email,
            passwordHash = user.passwordHash,
            firstName = user.firstName,
            lastName = user.lastName,
            role = user.role,
            isActive = user.isActive,
            createdAt = user.createdAt
        )
    }
}
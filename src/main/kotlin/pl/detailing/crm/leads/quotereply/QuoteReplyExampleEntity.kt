package pl.detailing.crm.leads.quotereply

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "quote_reply_examples",
    indexes = [Index(name = "idx_quote_reply_examples_studio", columnList = "studio_id")]
)
class QuoteReplyExampleEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "title", nullable = false, columnDefinition = "text")
    var title: String,

    @Column(name = "content", nullable = false, columnDefinition = "text")
    var content: String,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_by_name", nullable = true, columnDefinition = "text")
    val createdByName: String?,

    @Column(name = "updated_by", nullable = true, columnDefinition = "uuid")
    var updatedBy: UUID?,

    @Column(name = "updated_by_name", nullable = true, columnDefinition = "text")
    var updatedByName: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)

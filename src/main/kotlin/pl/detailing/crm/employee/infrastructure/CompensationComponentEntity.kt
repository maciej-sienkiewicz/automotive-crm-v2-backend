package pl.detailing.crm.employee.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.persistence.*
import pl.detailing.crm.employee.domain.CompensationComponent
import pl.detailing.crm.employee.domain.Threshold
import pl.detailing.crm.shared.*
import java.math.BigDecimal
import java.util.UUID

@Entity
@Table(
    name = "compensation_components",
    indexes = [
        Index(name = "idx_comp_components_config", columnList = "compensation_config_id"),
        Index(name = "idx_comp_components_studio", columnList = "studio_id")
    ]
)
class CompensationComponentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "compensation_config_id", nullable = false, columnDefinition = "uuid")
    val compensationConfigId: UUID,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    var type: ComponentType,

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_base", length = 30)
    var calculationBase: CalculationBase?,

    @Column(name = "value", nullable = false, precision = 15, scale = 4)
    var value: BigDecimal,

    @Column(name = "thresholds_json", columnDefinition = "text")
    var thresholdsJson: String = "[]",

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    var frequency: PaymentFrequency,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "description", columnDefinition = "text")
    var description: String?
) {
    fun toDomain(): CompensationComponent = CompensationComponent(
        id = CompensationComponentId(id),
        name = name,
        type = type,
        calculationBase = calculationBase,
        value = value,
        thresholds = mapper.readValue<List<ThresholdJson>>(thresholdsJson).map { it.toDomain() },
        frequency = frequency,
        isActive = isActive,
        description = description
    )

    companion object {
        private val mapper = jacksonObjectMapper()

        fun fromDomain(component: CompensationComponent, configId: UUID, studioId: UUID): CompensationComponentEntity =
            CompensationComponentEntity(
                id = component.id.value,
                studioId = studioId,
                compensationConfigId = configId,
                name = component.name,
                type = component.type,
                calculationBase = component.calculationBase,
                value = component.value,
                thresholdsJson = mapper.writeValueAsString(component.thresholds.map { ThresholdJson.fromDomain(it) }),
                frequency = component.frequency,
                isActive = component.isActive,
                description = component.description
            )
    }
}

private data class ThresholdJson(
    val minValueCents: Long,
    val maxValueCents: Long?,
    val rate: String
) {
    fun toDomain(): Threshold = Threshold(
        minValue = Money.fromCents(minValueCents),
        maxValue = maxValueCents?.let { Money.fromCents(it) },
        rate = rate.toBigDecimal()
    )

    companion object {
        fun fromDomain(t: Threshold): ThresholdJson = ThresholdJson(
            minValueCents = t.minValue.amountInCents,
            maxValueCents = t.maxValue?.amountInCents,
            rate = t.rate.toPlainString()
        )
    }
}

package pl.detailing.crm.leads.estimation.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

data class RelatedVisit(
    val id: String,
    val title: String?
)

@Converter
class RelatedVisitListConverter : AttributeConverter<List<RelatedVisit>, String> {

    private val objectMapper = ObjectMapper().registerKotlinModule()

    override fun convertToDatabaseColumn(attribute: List<RelatedVisit>?): String =
        objectMapper.writeValueAsString(attribute ?: emptyList<RelatedVisit>())

    override fun convertToEntityAttribute(dbData: String?): List<RelatedVisit> =
        if (dbData.isNullOrBlank() || dbData == "null") emptyList()
        else objectMapper.readValue(dbData, object : TypeReference<List<RelatedVisit>>() {})
}

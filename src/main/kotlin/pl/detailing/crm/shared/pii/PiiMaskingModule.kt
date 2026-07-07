package pl.detailing.crm.shared.pii

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import pl.detailing.crm.shared.PII_MASK

/**
 * Jackson module enforcing personal-data masking at the serialization boundary —
 * the single choke point through which every JSON byte leaves the application
 * (REST responses and STOMP payloads share the Spring [com.fasterxml.jackson.databind.ObjectMapper]).
 *
 * Every property annotated with [Pii] is serialized by [PiiMaskingStringSerializer],
 * which consults the thread-bound [PiiAccessContext] at write time. Controllers and
 * services carry no masking logic and cannot opt out.
 */
class PiiMaskingModule : SimpleModule("pii-masking") {
    init {
        setSerializerModifier(PiiSerializerModifier())
    }
}

private class PiiSerializerModifier : BeanSerializerModifier() {
    override fun changeProperties(
        config: SerializationConfig,
        beanDesc: BeanDescription,
        beanProperties: MutableList<BeanPropertyWriter>
    ): MutableList<BeanPropertyWriter> {
        beanProperties.forEach { writer ->
            if (writer.getAnnotation(Pii::class.java) != null) {
                check(writer.type.rawClass == String::class.java) {
                    "@Pii supports only String fields; ${beanDesc.beanClass.name}.${writer.name} " +
                        "is ${writer.type.rawClass.name}. Mask structured data by annotating its leaf fields."
                }
                writer.assignSerializer(PiiMaskingStringSerializer)
            }
        }
        return beanProperties
    }
}

private object PiiMaskingStringSerializer : JsonSerializer<Any?>() {
    override fun serialize(value: Any?, gen: JsonGenerator, serializers: SerializerProvider) {
        when {
            value == null -> gen.writeNull()
            PiiAccessContext.isGranted() -> gen.writeString(value as String)
            else -> gen.writeString(PII_MASK)
        }
    }
}

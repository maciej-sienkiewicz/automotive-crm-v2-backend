package pl.detailing.crm.communication.window

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

@Configuration
@EnableConfigurationProperties(SendWindowProperties::class)
class SendWindowConfig {

    private val logger = LoggerFactory.getLogger(SendWindowConfig::class.java)

    @Bean
    fun sendWindow(properties: SendWindowProperties): SendWindow {
        val window = SendWindow(
            zone = ZoneId.of(properties.zone),
            opensAt = parseTime("communication.send-window.opens-at", properties.opensAt),
            closesAt = parseTime("communication.send-window.closes-at", properties.closesAt),
            enabled = properties.enabled
        )
        if (window.enabled) {
            logger.info(
                "Customer communication send window: {}–{} {}",
                window.opensAt, window.closesAt, window.zone
            )
        } else {
            logger.warn("Customer communication send window is DISABLED — messages go out at any hour")
        }
        return window
    }

    private fun parseTime(property: String, value: String): LocalTime = try {
        LocalTime.parse(value.trim())
    } catch (e: DateTimeParseException) {
        throw IllegalStateException("$property must be HH:mm, got '$value'", e)
    }
}

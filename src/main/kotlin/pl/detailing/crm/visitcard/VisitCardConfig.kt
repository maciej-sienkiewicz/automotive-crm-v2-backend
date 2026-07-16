package pl.detailing.crm.visitcard

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(VisitCardProperties::class)
class VisitCardConfig

@ConfigurationProperties(prefix = "visitcard")
data class VisitCardProperties(
    /** Frontend origin used to build the public card link sent to the customer. */
    val frontendBaseUrl: String = "https://detailboost.pl"
)

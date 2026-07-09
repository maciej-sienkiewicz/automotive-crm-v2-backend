package pl.detailing.crm.payments.p24

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(Przelewy24Properties::class)
class Przelewy24Config

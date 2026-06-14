package pl.detailing.crm.auth.passwordreset

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(PasswordResetProperties::class)
class PasswordResetConfig

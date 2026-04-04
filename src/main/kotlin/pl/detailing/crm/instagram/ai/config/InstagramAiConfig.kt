package pl.detailing.crm.instagram.ai.config

import org.springframework.ai.chat.client.ChatClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Konfiguracja modułu AI dla generowania postów Instagram.
 *
 * Rejestruje bean [ChatClient] z kwalifikatorem "instagramChatClient",
 * aby nie kolidować z potencjalnymi innymi ChatClient w aplikacji.
 */
@Configuration
class InstagramAiConfig {

    @Bean("instagramChatClient")
    fun instagramChatClient(builder: ChatClient.Builder): ChatClient =
        builder.build()
}

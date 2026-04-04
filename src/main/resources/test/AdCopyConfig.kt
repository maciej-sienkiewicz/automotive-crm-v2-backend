package com.example.demo.adcopy.config

import org.springframework.ai.chat.client.ChatClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Konfiguracja modułu AdCopy – rejestruje bean ChatClient.
 */
@Configuration
class AdCopyConfig {

    @Bean
    fun chatClient(builder: ChatClient.Builder): ChatClient {
        return builder.build()
    }
}


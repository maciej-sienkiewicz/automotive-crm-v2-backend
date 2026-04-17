package pl.detailing.crm.smscampaigns.reminder.ai

import org.springframework.ai.chat.client.ChatClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SmsReminderAiConfig {

    @Bean("smsReminderChatClient")
    fun smsReminderChatClient(builder: ChatClient.Builder): ChatClient =
        builder.build()
}

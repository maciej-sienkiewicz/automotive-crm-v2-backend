package pl.detailing.crm.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import pl.detailing.crm.checkin.qr.CheckinPhotoService
import pl.detailing.crm.checkin.qr.CheckinPhotoUploadedMessageListener

/**
 * Redis Pub/Sub configuration.
 *
 * Registers a [RedisMessageListenerContainer] that listens on the
 * "checkin:photo-uploaded" channel and routes messages to
 * [CheckinPhotoUploadedMessageListener], which in turn forwards them
 * to the appropriate WebSocket STOMP topic.
 */
@Configuration
class RedisConfig {

    @Bean
    fun redisMessageListenerContainer(
        connectionFactory: RedisConnectionFactory,
        listener: CheckinPhotoUploadedMessageListener
    ): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        container.addMessageListener(
            listener,
            ChannelTopic(CheckinPhotoService.REDIS_PHOTO_UPLOADED_CHANNEL)
        )
        return container
    }
}

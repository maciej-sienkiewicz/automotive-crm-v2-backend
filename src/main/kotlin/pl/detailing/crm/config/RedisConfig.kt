package pl.detailing.crm.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.data.redis.serializer.RedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer
import pl.detailing.crm.checkin.qr.CheckinDamagePointsService
import pl.detailing.crm.checkin.qr.CheckinDamageUpdatedMessageListener
import pl.detailing.crm.checkin.qr.CheckinPhotoService
import pl.detailing.crm.checkin.qr.CheckinPhotoUploadedMessageListener
import pl.detailing.crm.signing.infrastructure.SignatureEventPublisher
import pl.detailing.crm.signing.infrastructure.SignatureRequestMessageListener

/**
 * Redis Pub/Sub configuration.
 *
 * Registers a [RedisMessageListenerContainer] with listeners for:
 * - "checkin:photo-uploaded"   → [CheckinPhotoUploadedMessageListener]
 * - "checkin:damage-updated"   → [CheckinDamageUpdatedMessageListener]
 * - "signing:request-events"   → [SignatureRequestMessageListener]
 *
 * All listeners forward their messages to the appropriate WebSocket STOMP topic.
 */
@Configuration
class RedisConfig {

    /**
     * String-keyed template with raw byte-array values. Used by the signing flow to
     * cache the pending PDF between "Poproś o podpis" and its delivery to the signing
     * device, so delivery does not need a second round trip to S3.
     */
    @Bean
    fun binaryRedisTemplate(connectionFactory: RedisConnectionFactory): RedisTemplate<String, ByteArray> =
        RedisTemplate<String, ByteArray>().apply {
            setConnectionFactory(connectionFactory)
            keySerializer = StringRedisSerializer()
            valueSerializer = RedisSerializer.byteArray()
            hashKeySerializer = StringRedisSerializer()
            hashValueSerializer = RedisSerializer.byteArray()
        }

    @Bean
    fun redisMessageListenerContainer(
        connectionFactory: RedisConnectionFactory,
        photoListener: CheckinPhotoUploadedMessageListener,
        damageListener: CheckinDamageUpdatedMessageListener,
        signatureListener: SignatureRequestMessageListener
    ): RedisMessageListenerContainer {
        val container = RedisMessageListenerContainer()
        container.setConnectionFactory(connectionFactory)
        container.addMessageListener(
            photoListener,
            ChannelTopic(CheckinPhotoService.REDIS_PHOTO_UPLOADED_CHANNEL)
        )
        container.addMessageListener(
            damageListener,
            ChannelTopic(CheckinDamagePointsService.REDIS_DAMAGE_UPDATED_CHANNEL)
        )
        container.addMessageListener(
            signatureListener,
            ChannelTopic(SignatureEventPublisher.REDIS_CHANNEL)
        )
        return container
    }
}

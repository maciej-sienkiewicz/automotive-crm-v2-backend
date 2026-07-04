package pl.detailing.crm.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.messaging.converter.ByteArrayMessageConverter
import org.springframework.messaging.converter.DefaultContentTypeResolver
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.converter.MessageConverter
import org.springframework.messaging.converter.StringMessageConverter
import org.springframework.messaging.simp.config.ChannelRegistration
import org.springframework.messaging.simp.config.MessageBrokerRegistry
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.util.MimeTypeUtils
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
import org.springframework.web.socket.config.annotation.StompEndpointRegistry
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig(
    private val webSocketSecurityInterceptor: WebSocketSecurityInterceptor,
    private val objectMapper: ObjectMapper
) : WebSocketMessageBrokerConfigurer {

    companion object {
        // Client (socketClient.ts) declares heart-beat:10000,10000. The simple broker
        // defaults to 0,0 (no heartbeats) unless a TaskScheduler is provided, and
        // without heartbeats the browser cannot detect a dead connection — events
        // silently stop until the user refreshes the page.
        private const val HEARTBEAT_MS = 10_000L
    }

    @Bean
    fun webSocketHeartbeatScheduler(): ThreadPoolTaskScheduler =
        ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("ws-heartbeat-")
            initialize()
        }

    override fun configureMessageBroker(registry: MessageBrokerRegistry) {
        registry.enableSimpleBroker("/topic")
            .setHeartbeatValue(longArrayOf(HEARTBEAT_MS, HEARTBEAT_MS))
            .setTaskScheduler(webSocketHeartbeatScheduler())
        registry.setApplicationDestinationPrefixes("/app")
    }

    override fun registerStompEndpoints(registry: StompEndpointRegistry) {
        registry.addEndpoint("/ws-registry")
            .setAllowedOriginPatterns(
                // No trailing slash — the browser's Origin header is scheme://host[:port],
                // so "https://detailboost.pl/" would never match and production
                // cross-origin handshakes would be rejected.
                "https://detailboost.pl",
                "https://*.detailboost.pl",
                "http://localhost:*",
                "http://192.168.*.*:*"
            )
            .withSockJS()
    }

    override fun configureClientInboundChannel(registration: ChannelRegistration) {
        registration.interceptors(webSocketSecurityInterceptor)
    }

    // Use the Spring Boot ObjectMapper (JavaTimeModule, ISO-8601 dates) for STOMP
    // payloads. The broker's default converter uses a bare ObjectMapper that cannot
    // serialize java.time.Instant, so every convertAndSend of an event carrying
    // timestamps would fail before reaching the client.
    override fun configureMessageConverters(messageConverters: MutableList<MessageConverter>): Boolean {
        messageConverters.add(StringMessageConverter())
        messageConverters.add(ByteArrayMessageConverter())
        messageConverters.add(MappingJackson2MessageConverter().apply {
            objectMapper = this@WebSocketConfig.objectMapper
            contentTypeResolver = DefaultContentTypeResolver().apply {
                defaultMimeType = MimeTypeUtils.APPLICATION_JSON
            }
        })
        return false
    }
}
